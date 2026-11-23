package com.nova.app

import java.util.Locale

/**
 * Urdu / Hindi (Devanagari) awaaz-pehchan ka text -> Roman (Latin) taake commands aur gaanon ke naam samajh aayen.
 * Phone ka offline recognizer ur-PK par Urdu script, hi-IN par Devanagari deta hai - dono yahan Roman ban jate hain.
 */
object Script {
    fun hasUrdu(s: String) = s.any { it in '\u0600'..'\u06FF' || it in '\uFB50'..'\uFEFF' }
    fun hasDeva(s: String) = s.any { it in '\u0900'..'\u097F' }
    fun hasNonLatin(s: String) = hasUrdu(s) || hasDeva(s)
    /** Bolne ke liye: kaunsi zuban ki awaaz chahiye */
    fun speakLang(s: String): String = when { hasUrdu(s) -> "ur"; hasDeva(s) -> "hi"; else -> "en" }

    // ---- poore lafz ka dictionary (command words) ----
    private val WORDS: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        val raw = """
چلاؤ=chalao
چلاو=chalao
چلائیں=chalao
چلا=chala
دو=do
دیں=do
کرو=karo
کریں=karo
کر=kar
کھولو=kholo
کھولیں=kholo
کھول=khol
بند=band
گانا=gaana
گانے=gaane
گیت=gaana
سونگ=song
میوزک=music
موسیقی=music
بجاؤ=chalao
بجاو=chalao
لگاؤ=chalao
لگا=laga
سناؤ=sunao
سنائیں=sunao
کال=call
فون=phone
میسج=message
پیغام=message
بھیجو=bhejo
بھیج=bhej
واٹس=whatsapp
واٹسایپ=whatsapp
وٹس=whatsapp
ایپ=
یاد=yaad
دلاؤ=dilao
دلا=dila
دلانا=dilao
ریمائنڈر=reminder
ریمائنڈ=remind
منٹ=minute
گھنٹے=ghante
گھنٹہ=ghanta
بعد=baad
بجے=baje
ٹارچ=torch
فلیش=flash
لائٹ=light
آن=on
آف=off
موسم=mausam
وقت=time
ٹائم=time
تاریخ=tareekh
نماز=namaz
اذان=azan
بیٹری=battery
آواز=awaaz
کم=kam
زیادہ=zyada
بڑھاؤ=barhao
نووا=nova
نوا=nova
نووہ=nova
ناوا=nova
نوو=nova
یوٹیوب=youtube
سرچ=search
ڈھونڈو=dhoondo
کھیل=khel
کھیلو=khelo
گیم=game
رکھو=rakho
رکھنا=rakhna
بھول=bhool
جاؤ=jao
ترجمہ=tarjuma
انگریزی=english
انگلش=english
اردو=urdu
ہندی=hindi
میں=mein
پر=par
کو=ko
سے=se
کا=ka
کی=ki
کے=ke
اسکرین=screen
سکرین=screen
پڑھو=padho
لاک=lock
ان=un
بیک=back
اپ=up
کلپ=clip
بورڈ=board
کیا=kya
کون=kaun
ہے=hai
ہیں=hain
یہ=ye
وہ=wo
اور=aur
گانہ=gaana
اگلا=agla
پچھلا=pichla
رکو=ruko
روکو=roko
چپ=chup
بولو=bolo
بتاؤ=batao
بتا=bata
کتنے=kitne
کتنا=kitna
چ=ch
چاہیے=chahiye
دوبارہ=dobara
دن=din
آج=aaj
کل=kal
کھولدو=kholo
چلادو=chalao
کردو=karo
بنددو=band
یاددلاؤ=yaad dilao
यादरखो=yaad rakho
चलाओ=chalao
चला=chala
दो=do
करो=karo
कर=kar
खोलो=kholo
खोल=khol
बंद=band
गाना=gaana
गाने=gaane
गीत=gaana
सॉन्ग=song
सोंग=song
म्यूजिक=music
म्यूज़िक=music
कॉल=call
फोन=phone
फ़ोन=phone
मैसेज=message
संदेश=message
भेजो=bhejo
भेज=bhej
व्हाट्सएप=whatsapp
व्हाट्सऐप=whatsapp
वॉट्सऐप=whatsapp
याद=yaad
दिलाओ=dilao
दिला=dila
रिमाइंडर=reminder
मिनट=minute
घंटे=ghante
घंटा=ghanta
बाद=baad
बजे=baje
टॉर्च=torch
टार्च=torch
टोर्च=torch
फ्लैशलाइट=flashlight
ऑन=on
ऑफ=off
मौसम=mausam
समय=time
टाइम=time
तारीख=tareekh
नमाज़=namaz
नमाज=namaz
अज़ान=azan
अजान=azan
बैटरी=battery
आवाज़=awaaz
आवाज=awaaz
कम=kam
ज़्यादा=zyada
ज्यादा=zyada
नोवा=nova
नोबा=nova
नोवो=nova
नॉवा=nova
यूट्यूब=youtube
सर्च=search
खेल=khel
खेलो=khelo
गेम=game
रखो=rakho
रखना=rakhna
भूल=bhool
जाओ=jao
अनुवाद=tarjuma
तर्जुमा=tarjuma
इंग्लिश=english
अंग्रेज़ी=english
अंग्रेजी=english
उर्दू=urdu
हिंदी=hindi
में=mein
पर=par
को=ko
से=se
का=ka
की=ki
के=ke
स्क्रीन=screen
पढ़ो=padho
पढो=padho
लॉक=lock
बैकअप=backup
क्लिपबोर्ड=clipboard
क्या=kya
कौन=kaun
है=hai
हैं=hain
ये=ye
वो=wo
और=aur
अगला=agla
पिछला=pichla
रुको=ruko
बताओ=batao
बता=bata
कितने=kitne
कितना=kitna
दोबारा=dobara
आज=aaj
कल=kal
        """.trimIndent()
        for (line in raw.lines()) { val i = line.indexOf('='); if (i > 0) m[line.substring(0, i).trim()] = line.substring(i + 1).trim() }
        m
    }

    private fun digits(s: String): String {
        val sb = StringBuilder()
        for (c in s) sb.append(when (c) { in '\u06F0'..'\u06F9' -> ('0' + (c - '\u06F0')); in '\u0660'..'\u0669' -> ('0' + (c - '\u0660')); in '\u0966'..'\u096F' -> ('0' + (c - '\u0966')); else -> c })
        return sb.toString()
    }

    /** Command samajhne ke liye: Urdu/Hindi -> Roman; Latin text jaisa hai waisa (lowercase nahi karta). */
    fun normalize(s: String): String {
        if (!hasNonLatin(s)) return s
        val parts = digits(s).split(Regex("[\\s\u060C\u061F\u06D4,.?!]+")).filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        for (w in parts) {
            val k = w.replace("\u200c", "").replace("\u200d", "")
            val hit = WORDS[k]
            if (hit != null) { if (hit.isNotEmpty()) out.add(hit) } else out.add(if (hasNonLatin(k)) toLatin(k) else k)
        }
        return out.joinToString(" ")
    }

    // ---- harf-ba-harf (naam / gaane ke liye) ----
    private val URDU = mapOf(
        'ا' to "a", 'آ' to "aa", 'ب' to "b", 'پ' to "p", 'ت' to "t", 'ٹ' to "t", 'ث' to "s", 'ج' to "j", 'چ' to "ch", 'ح' to "h", 'خ' to "kh",
        'د' to "d", 'ڈ' to "d", 'ذ' to "z", 'ر' to "r", 'ڑ' to "r", 'ز' to "z", 'ژ' to "zh", 'س' to "s", 'ش' to "sh", 'ص' to "s", 'ض' to "z",
        'ط' to "t", 'ظ' to "z", 'ع' to "a", 'غ' to "gh", 'ف' to "f", 'ق' to "q", 'ک' to "k", 'ك' to "k", 'گ' to "g", 'ل' to "l", 'م' to "m", 'ن' to "n",
        'ں' to "n", 'و' to "o", 'ہ' to "h", 'ھ' to "h", 'ہ' to "h", 'ة' to "h", 'ء' to "", 'ی' to "i", 'ي' to "i", 'ے' to "e", 'ئ' to "y", 'ؤ' to "o", 'ۃ' to "h"
    )
    private val DEVA_C = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh",
        'ण' to "n", 'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n", 'प' to "p", 'फ' to "f", 'ब' to "b", 'भ' to "bh", 'म' to "m", 'य' to "y",
        'र' to "r", 'ल' to "l", 'व' to "v", 'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h"
    )
    private val NUKTA = mapOf('ज' to "z", 'ड' to "r", 'ढ' to "rh", 'फ' to "f", 'क' to "q", 'ख' to "kh", 'ग' to "gh")
    private val DEVA_V = mapOf('अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee", 'उ' to "u", 'ऊ' to "oo", 'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'ऑ' to "o", 'ऋ' to "ri")
    private val DEVA_M = mapOf('ा' to "aa", 'ि' to "i", 'ी' to "ee", 'ु' to "u", 'ू' to "oo", 'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au", 'ॉ' to "o", 'ं' to "n", 'ँ' to "n", 'ः' to "h")

    fun toLatin(w: String): String {
        val sb = StringBuilder()
        var i = 0
        val s = w.replace("\u0654", "").replace("\u064E", "").replace("\u064F", "").replace("\u0650", "").replace("\u0651", "").replace("\u0670", "")
        while (i < s.length) {
            val c = s[i]
            when {
                c in '\u0600'..'\u06FF' || c in '\uFB50'..'\uFEFF' -> sb.append(URDU[c] ?: "")
                c in '\u0900'..'\u097F' -> {
                    val nuk = i + 1 < s.length && s[i + 1] == '\u093C'
                    val cons = if (nuk) (NUKTA[c] ?: DEVA_C[c]) else DEVA_C[c]
                    if (cons != null) {
                        sb.append(cons)
                        var j = i + (if (nuk) 2 else 1)
                        val nx = if (j < s.length) s[j] else ' '
                        if (nx == '\u094D') j++
                        else if (!DEVA_M.containsKey(nx) && j < s.length && s[j] in '\u0900'..'\u097F') sb.append("a")
                        i = j; continue
                    }
                    DEVA_V[c]?.let { sb.append(it) } ?: DEVA_M[c]?.let { sb.append(it) }
                }
                c == '\u093C' || c == '\u094D' -> {}
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString().lowercase(Locale.ROOT)
    }

    /** Gaane ke naam ka "dhancha": sirf consonants (Roman/Urdu/Hindi teeno ek jaisa ban jate hain). */
    fun skeleton(s: String): String {
        val lat = (if (hasNonLatin(s)) toLatin(digits(s)) else s).lowercase(Locale.ROOT)
        val sb = StringBuilder()
        for (c in lat) {
            if (c !in 'a'..'z') continue
            if (c in "aeiouwy") continue
            if (sb.isNotEmpty() && sb.last() == c) continue
            sb.append(c)
        }
        return sb.toString().replace("ph", "f").replace("kh", "k").replace("gh", "g").replace("sh", "s").replace("ch", "c").replace("th", "t").replace("dh", "d").replace("bh", "b")
    }

    fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }
}
