package com.baseprovider.streamix.jvm

import com.baseprovider.streamix.StreamixCrypto
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class JvmStreamixCrypto : StreamixCrypto {
    override fun sha256(input: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input)

    override fun aesGcmDecrypt(
        ciphertext: ByteArray,
        key: ByteArray,
        iv: ByteArray,
        aad: ByteArray?
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        aad?.let(cipher::updateAAD)
        return cipher.doFinal(ciphertext)
    }

    override fun aesCbcNoPaddingDecrypt(
        ciphertext: ByteArray,
        key: ByteArray,
        iv: ByteArray
    ): ByteArray {
        require(iv.size == 16) { "AES-CBC requires a 16-byte IV" }
        require(ciphertext.size % 16 == 0) {
            "AES-CBC/NoPadding ciphertext length must be a multiple of 16 bytes"
        }

        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(iv)
        )
        return cipher.doFinal(ciphertext)
    }

    override fun deriveMd5KeyAndIv(
        password: ByteArray,
        salt: ByteArray,
        keyLength: Int,
        ivLength: Int
    ): Pair<ByteArray, ByteArray> {
        require(keyLength > 0) { "AES key length must be positive" }
        require(ivLength > 0) { "AES IV length must be positive" }

        val digestLength = 16
        val targetLength = keyLength + ivLength
        val generated = ByteArray(
            ((targetLength + digestLength - 1) / digestLength) * digestLength
        )

        var generatedLength = 0
        while (generatedLength < targetLength) {
            val md5 = MessageDigest.getInstance("MD5")
            if (generatedLength > 0) {
                md5.update(
                    generated,
                    generatedLength - digestLength,
                    digestLength
                )
            }
            md5.update(password)
            md5.update(salt)

            val digest = md5.digest()
            digest.copyInto(generated, generatedLength)
            generatedLength += digestLength
        }

        return generated.copyOfRange(0, keyLength) to
            generated.copyOfRange(keyLength, targetLength)
    }
}
