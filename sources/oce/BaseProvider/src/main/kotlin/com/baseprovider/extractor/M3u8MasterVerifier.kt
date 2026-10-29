package com.baseprovider.extractor

import com.baseprovider.streamix.app

/** Compatibility facade; pure HLS parsing/classification lives in streamix-core. */
object M3u8MasterVerifier {
    typealias MasterVariant = streamix.core.StreamixM3u8Verifier.MasterVariant
    typealias Verdict = streamix.core.StreamixM3u8Verifier.Verdict

    internal fun parseVariants(masterText: String) =
        streamix.core.StreamixM3u8Verifier.parseVariants(masterText)

    internal fun classify(masterUrl: String, parsed: List<MasterVariant>): Verdict =
        streamix.core.StreamixM3u8Verifier.classify(masterUrl, parsed)

    suspend fun verify(
        masterUrl: String,
        referer: String?,
        headers: Map<String, String>
    ): Verdict {
        return try {
            val text = app.get(
                masterUrl,
                referer = referer,
                headers = headers,
                timeout = 8000L
            ).text
            classify(masterUrl, parseVariants(text))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            streamix.core.StreamixM3u8Verifier.Verdict.Clean
        }
    }
}