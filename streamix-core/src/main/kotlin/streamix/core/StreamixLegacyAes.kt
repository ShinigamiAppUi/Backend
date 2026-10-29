package streamix.core

import java.util.Base64

/**
 * Native equivalent of CloudStream's legacy AesHelper envelope.
 *
 * Expected JSON:
 *   {"ct":"<base64>","iv":"<hex>","s":"<hex>"}
 *
 * The key/IV are derived with the same MD5 chaining algorithm used by
 * CloudStream's AesHelper, then AES/CBC/NoPadding is applied.
 */
object StreamixLegacyAes {
    fun decryptBase64(
        encoded: String,
        password: ByteArray,
        crypto: StreamixCrypto,
        json: StreamixJson
    ): String? = runCatching { decrypt(Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8), password, crypto, json) }.getOrNull()

    fun decrypt(
        data: String,
        password: ByteArray,
        crypto: StreamixCrypto,
        json: StreamixJson
    ): String? {
        val envelope = runCatching { json.parseObject(data) }.getOrNull() ?: return null

        val ct = envelope["ct"] as? String ?: return null
        val ivHex = envelope["iv"] as? String ?: return null
        val saltHex = envelope["s"] as? String ?: return null

        val salt = saltHex.hexToBytesOrNull() ?: return null
        val envelopeIv = ivHex.hexToBytesOrNull() ?: return null

        val (key, derivedIv) = crypto.deriveMd5KeyAndIv(
            password = password,
            salt = salt,
            ivLength = envelopeIv.size
        )

        // CloudStream uses the envelope IV only for its length. The actual
        // IV comes from the MD5 password/salt derivation.

        val ciphertext = runCatching {
            Base64.getDecoder().decode(ct)
        }.getOrNull() ?: return null

        val plaintext = runCatching {
            crypto.aesCbcNoPaddingDecrypt(ciphertext, key, derivedIv)
        }.getOrNull() ?: return null

        return plaintext.toString(Charsets.UTF_8)
    }

    private fun String.hexToBytesOrNull(): ByteArray? {
        if (length % 2 != 0) return null
        return runCatching {
            chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }.getOrNull()
    }
}
