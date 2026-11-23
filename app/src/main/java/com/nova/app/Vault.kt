package com.nova.app

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Apni file ko password se encrypt/decrypt (AES-256-GCM). Format: "NOVAENC1" + salt(16) + iv(12) + data. Password bhoolo to file wapas nahi aati. */
object Vault {
    private val MAGIC = "NOVAENC1".toByteArray()

    private fun key(pw: CharArray, salt: ByteArray): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(f.generateSecret(PBEKeySpec(pw, salt, 120_000, 256)).encoded, "AES")
    }

    fun encrypt(data: ByteArray, password: String): ByteArray {
        val rnd = SecureRandom(); val salt = ByteArray(16).also { rnd.nextBytes(it) }; val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key(password.toCharArray(), salt), GCMParameterSpec(128, iv))
        return MAGIC + salt + iv + c.doFinal(data)
    }

    /** null = password ghalat ya file NOVA ki nahi */
    fun decrypt(blob: ByteArray, password: String): ByteArray? {
        if (blob.size < MAGIC.size + 28 || !blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return null
        return try {
            val salt = blob.copyOfRange(8, 24); val iv = blob.copyOfRange(24, 36)
            val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(password.toCharArray(), salt), GCMParameterSpec(128, iv))
            c.doFinal(blob, 36, blob.size - 36)
        } catch (_: Exception) { null }
    }
}
