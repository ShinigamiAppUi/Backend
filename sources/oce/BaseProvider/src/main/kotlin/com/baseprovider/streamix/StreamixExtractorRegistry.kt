package com.baseprovider.streamix

/**
 * Streamix-facing registry. Implementations are supplied by the runtime
 * adapter layer; provider code should depend on this Streamix contract.
 */
interface StreamixExtractorRegistry {
    fun getMatchingExtractors(url: String): List<StreamixExtractor>
    fun hasMatchingExtractor(url: String): Boolean
    fun all(): List<StreamixExtractor>

    /**
     * Resolve an URL through the native extractor registry.
     *
     * Implementations may try multiple matching extractors. [callChain] is
     * used to prevent recursive delegate loops while preserving fallback.
     */
    suspend fun resolve(
        url: String,
        referer: String? = null,
        subtitleCallback: (StreamixSubtitle) -> Unit = {},
        callback: (StreamixStream) -> Unit,
        callChain: Set<String> = emptySet()
    ): Boolean
}
