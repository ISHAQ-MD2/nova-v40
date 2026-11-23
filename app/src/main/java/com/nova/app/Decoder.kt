package com.nova.app

import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

/**
 * Code ki "chhupi hui" (obfuscated) parton ko kholta hai: base64, hex, zlib/gzip/deflate, \xNN escapes, URL-encoding - kai layer tak.
 * Ye asli encryption (AES + password/key) NAHI tod sakta - wo sirf sahi password/key se khulti hai.
 * Sirf apni ya jin files ki ijazat ho unpar istemal karo (author ki license ka khayal rakho).
 */
object Decoder {
    class Result(val text: String, val steps: List<String>, val note: String)

    private fun inflate(b: ByteArray, nowrap: Boolean): ByteArray? = try {
        val inf = Inflater(nowrap); inf.setInput(b)
        val out = ByteArrayOutputStream(); val buf = ByteArray(8192)
        while (!inf.finished() && out.size() < 16_000_000) { val n = inf.inflate(buf); if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break; out.write(buf, 0, n) }
        inf.end(); if (out.size() > 0) out.toByteArray() else null
    } catch (_: Exception) { null }

    private fun gunzip(b: ByteArray): ByteArray? = try { GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() } } catch (_: Exception) { null }

    private fun textLike(b: ByteArray): Boolean {
        if (b.size < 12) return false
        val s = String(b, Charsets.UTF_8)
        var ok = 0
        for (c in s) if (c == '\n' || c == '\r' || c == '\t' || (c in ' '..'~') || c.code >= 160) ok++
        return ok.toDouble() / s.length > 0.92
    }

    private fun decodeAny(s0: String): Pair<String, ByteArray>? {
        val s = s0.replace(Regex("[\\s'\"\\\\]"), "")
        if (s.length < 24) return null
        if (Regex("^[0-9a-fA-F]+$").matches(s) && s.length % 2 == 0) {
            return try { "hex" to ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() } } catch (_: Exception) { null }
        }
        for ((label, flag) in listOf("base64" to Base64.DEFAULT, "base64-url" to Base64.URL_SAFE)) {
            try { val b = Base64.decode(s, flag); if (b.size >= 12) return label to b } catch (_: Exception) {}
        }
        return null
    }

    /** Ek layer kholo: (kya kiya, naya text) ya null */
    private fun oneLayer(text: String): Pair<String, String>? {
        val cands = ArrayList<String>()
        if (Regex("^[A-Za-z0-9+/=_\\-\\s]{24,}$").matches(text.trim())) cands.add(text.trim())
        Regex("(?s)(?:\"\"\"|'''|\"|')([A-Za-z0-9+/=_\\-\\s]{60,}?)(?:\"\"\"|'''|\"|')").findAll(text).forEach { cands.add(it.groupValues[1]) }
        for (c in cands.sortedByDescending { it.length }) {
            val (label, bytes) = decodeAny(c) ?: continue
            val z = inflate(bytes, false) ?: gunzip(bytes) ?: inflate(bytes, true)
            val use = z ?: bytes
            val steps = label + (if (z != null) "+zlib/gzip" else "")
            if (textLike(use)) return steps to String(use, Charsets.UTF_8)
        }
        if (Regex("(\\\\x[0-9a-fA-F]{2}){6,}").containsMatchIn(text))
            return "hex-escapes" to Regex("\\\\x([0-9a-fA-F]{2})").replace(text) { m -> m.groupValues[1].toInt(16).toChar().toString() }
        if (Regex("(%[0-9a-fA-F]{2}.*){8,}", RegexOption.DOT_MATCHES_ALL).containsMatchIn(text))
            return try { "url-encoding" to URLDecoder.decode(text, "UTF-8") } catch (_: Exception) { null }
        return null
    }

    private fun binaryHint(text: String): String {
        val cands = Regex("(?s)(?:\"\"\"|'''|\"|')([A-Za-z0-9+/=_\\-\\s]{60,}?)(?:\"\"\"|'''|\"|')").findAll(text).map { it.groupValues[1] }.toList()
        for (c in cands.sortedByDescending { it.length }) {
            val (_, bytes) = decodeAny(c) ?: continue
            val z = inflate(bytes, false) ?: gunzip(bytes) ?: bytes
            if (!textLike(z)) return if (z.isNotEmpty() && (z[0] == 0xe3.toByte() || z[0] == 'c'.code.toByte())) "Andar Python bytecode (marshal) hai - Termux mein: python3 -c \"import marshal,dis;dis.dis(marshal.loads(open('x.bin','rb').read()))\"" else "Andar binary/encrypted data hai (key/password ke bina nahi khulega)."
        }
        return ""
    }

    fun unwrap(input: String): Result {
        var cur = input; val steps = ArrayList<String>()
        for (i in 0 until 16) { val n = oneLayer(cur) ?: break; steps.add(n.first); cur = n.second }
        val note = when {
            steps.isEmpty() -> "Koi aam encoding (base64/zlib/hex) nahi mili. Asli encryption ho to password/key chahiye, ya kisi aur zuban ka packer ho to Termux/browser mein chalana padega."
            else -> binaryHint(cur)
        }
        return Result(cur, steps, note)
    }

    /** File / zip. Naam -> (decoded?, bytes). Zip ho to har text file par try, nayi zip banti hai. */
    fun processFile(name: String, bytes: ByteArray): Triple<String, ByteArray, String> {
        if (bytes.size > 3 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            val out = ByteArrayOutputStream(); val zos = ZipOutputStream(out); var done = 0; var total = 0
            try {
                ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
                    var e = zin.nextEntry
                    while (e != null) {
                        val data = zin.readBytes()
                        if (!e.isDirectory) {
                            total++
                            var res = data
                            if (textLike(data) && data.size < 5_000_000) { val r = unwrap(String(data, Charsets.UTF_8)); if (r.steps.isNotEmpty()) { res = r.text.toByteArray(); done++ } }
                            zos.putNextEntry(ZipEntry(e.name)); zos.write(res); zos.closeEntry()
                        }
                        e = zin.nextEntry
                    }
                }
            } catch (_: Exception) {}
            zos.close()
            return Triple("decoded_$name", out.toByteArray(), "Zip: $total file mein se $done khul gayi.")
        }
        return if (textLike(bytes)) { val r = unwrap(String(bytes, Charsets.UTF_8)); Triple("decoded_$name.txt", r.text.toByteArray(), (if (r.steps.isEmpty()) "Kuch nahi khula. " else "Layers: " + r.steps.joinToString(" > ") + ". ") + r.note) }
        else Triple("", ByteArray(0), "Ye binary file hai - decode nahi hui.")
    }
}
