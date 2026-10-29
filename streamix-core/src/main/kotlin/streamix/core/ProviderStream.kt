package streamix.core

/**
 * Provider/extractor-neutral playable stream candidate.
 *
 * This is a data model only. It does not restore the retired provider runtime,
 * registry, routing, or provider-selection architecture.
 */
data class ProviderStream(
    val providerId: String,
    val url: String,
    val quality: Int? = null,
    val type: String? = null,
    val language: String? = null,
    val subtitleLanguage: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val referer: String? = null,
    val subtitles: List<ProviderSubtitle> = emptyList()
)
