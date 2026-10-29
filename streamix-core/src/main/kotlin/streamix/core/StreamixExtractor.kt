package streamix.core

/**
 * Dependency-free extractor boundary.
 *
 * Extractors receive an embed/source URL and return playable stream candidates
 * plus subtitles. Provider implementations do not need to know CloudStream
 * ExtractorLink or SubtitleFile types.
 */
interface StreamixExtractor {
    val id: String

    /**
     * Hostnames handled by this extractor.
     *
     * Empty means the extractor is not selected by hostname routing and must
     * be addressed explicitly by id.
     */
    val domains: Set<String>
        get() = emptySet()

    suspend fun extract(request: StreamixExtractorRequest): StreamixExtractorResult
}

data class StreamixExtractorRequest(
    val url: String,
    val providerId: String? = null,
    val referer: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val quality: Int? = null,
    /** Optional request-scoped transport; null preserves runtime-provider behavior. */
    val scopedHttp: StreamixHttp? = null
)

data class StreamixExtractorResult(
    val streams: List<ProviderStream> = emptyList(),
    val subtitles: List<ProviderSubtitle> = emptyList()
)

data class ProviderSubtitle(
    val url: String,
    val language: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val referer: String? = null
)
