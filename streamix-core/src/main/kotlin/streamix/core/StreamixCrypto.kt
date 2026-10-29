package streamix.core

interface StreamixCrypto {
    fun sha256(input: ByteArray): ByteArray

    fun aesGcmDecrypt(
        ciphertext: ByteArray,
        key: ByteArray,
        iv: ByteArray,
        aad: ByteArray? = null
    ): ByteArray

    /**
     * Decrypt AES-CBC data without padding.
     *
     * This matches the fixed primitive used by legacy CloudStream providers
     * such as Kuronime, while keeping the contract provider/runtime neutral.
     */
    fun aesCbcNoPaddingDecrypt(
        ciphertext: ByteArray,
        key: ByteArray,
        iv: ByteArray
    ): ByteArray

    /**
     * Derive the AES key and IV used by the legacy OpenSSL-compatible
     * password/salt construction used by CloudStream's AesHelper.
     *
     * The primitive is exposed separately so providers can reproduce the
     * legacy envelope semantics without depending on CloudStream.
     */
    fun deriveMd5KeyAndIv(
        password: ByteArray,
        salt: ByteArray,
        keyLength: Int = 32,
        ivLength: Int
    ): Pair<ByteArray, ByteArray>
}
