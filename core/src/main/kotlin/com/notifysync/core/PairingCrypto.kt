package com.notifysync.core

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Schlüssel aus dem Kopplungsgeheimnis. Das Geheimnis wandert nur per QR-Code von Gerät zu
 * Gerät, nie über die Cloud. Daraus werden per HKDF-SHA256 getrennte Schlüssel abgeleitet:
 *  - pairId: öffentlicher Name des Kanals im Relay (verrät nichts über den Schlüssel)
 *  - encKey: AES-256-GCM für jede Meldung (Ende-zu-Ende)
 *  - hashKey: HMAC-Salz für Fingerabdrücke, damit Inhalte nicht zurückgerechnet werden können
 */
class PairingCrypto(secret: ByteArray) {
    init {
        require(secret.size >= 32) { "Kopplungsgeheimnis zu kurz" }
    }

    private val prk = hmac(ByteArray(32), secret)
    val pairId: String = expand("notifysync/pair-id", 16).toHex()
    private val encKey = expand("notifysync/enc", 32)
    private val hashKey = expand("notifysync/hash", 32)
    private val random = SecureRandom()

    fun hash(vararg parts: String?): String {
        val joined = parts.joinToString("\u0000") { it ?: "\u0001" }
        return hmac(hashKey, joined.toByteArray(Charsets.UTF_8)).copyOf(16).toHex()
    }

    /** Ergebnis: 12 Byte IV + Chiffretext mit 16 Byte Tag. */
    fun encrypt(plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encKey, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(pairId.toByteArray())
        return iv + cipher.doFinal(plain)
    }

    /** Wirft eine Exception, wenn das Paket manipuliert wurde oder von einem fremden Paar stammt. */
    fun decrypt(box: ByteArray): ByteArray {
        require(box.size > 12 + 16) { "Paket zu kurz" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(encKey, "AES"),
            GCMParameterSpec(128, box, 0, 12),
        )
        cipher.updateAAD(pairId.toByteArray())
        return cipher.doFinal(box, 12, box.size - 12)
    }

    /** 6-stelliger Prüfcode, den beide Geräte nach der Kopplung anzeigen. */
    fun verificationCode(): String {
        val b = expand("notifysync/verify", 4)
        val n = ((b[0].toInt() and 0x7f) shl 24) or ((b[1].toInt() and 0xff) shl 16) or
            ((b[2].toInt() and 0xff) shl 8) or (b[3].toInt() and 0xff)
        return (n % 1_000_000).toString().padStart(6, '0')
    }

    private fun expand(info: String, length: Int): ByteArray {
        val out = ArrayList<Byte>()
        var t = ByteArray(0)
        var counter = 1
        while (out.size < length) {
            t = hmac(prk, t + info.toByteArray() + byteArrayOf(counter.toByte()))
            out.addAll(t.toList())
            counter++
        }
        return out.take(length).toByteArray()
    }

    companion object {
        fun newSecret(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
            return mac.doFinal(data)
        }

        fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
