package streamix.catalog

enum class SourceOrigin { SCRAPER, ADMIN }

/**
 * Explicit catalog source contract.
 *
 * DIRECT is already media-addressable (or will be deterministically transformed
 * by the direct-link layer in a later P-002 stage).
 * RESOLVER requires host/page/session resolution before a media URL exists.
 * EXTRACTOR is an anime/provider page consumed by a Streamix extractor.
 */
enum class SourceType {
    DIRECT,
    RESOLVER,
    EXTRACTOR
}

enum class SourceHealth { UNKNOWN, STREAMING, EXPIRED, FAILED }

data class EpisodeSource(
    val id: String,
    val anilistId: Long,
    val episodeNumber: Int,
    val sourceUrl: String,
    val origin: SourceOrigin = SourceOrigin.ADMIN,
    val type: SourceType,
    val provider: String? = null,
    val role: String? = null,
    val resolution: String? = null,
    val label: String? = null,
    val sourcePage: String? = null,
    val source: String? = null,
    val quality: Int? = null,
    val language: String? = null,
    val subtitleLanguage: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val referer: String? = null,
    val priority: Int = 0,
    val enabled: Boolean = true,
    val health: SourceHealth = SourceHealth.UNKNOWN,
    val lastCheckedAt: Long? = null,
    val lastHttpStatus: Int? = null,
    val lastFailureReason: String? = null
)

data class CatalogEpisode(
    val anilistId: Long,
    val number: Int,
    val title: String? = null,
    val sources: List<EpisodeSource> = emptyList()
)


/**
 * Legacy persisted catalog values are accepted only at the storage boundary.
 * Runtime code sees only DIRECT / RESOLVER / EXTRACTOR.
 */
fun legacySourceType(raw: String?, sourceUrl: String): SourceType = when (raw?.uppercase()) {
    "HLS", "MP4" -> SourceType.DIRECT
    "EMBED" -> SourceType.EXTRACTOR
    "AUTO" -> if (
        sourceUrl.substringBefore('?').endsWith(".m3u8", true) ||
        sourceUrl.substringBefore('?').endsWith(".mp4", true)
    ) SourceType.DIRECT else SourceType.EXTRACTOR
    "DIRECT" -> SourceType.DIRECT
    "RESOLVER" -> SourceType.RESOLVER
    "EXTRACTOR" -> SourceType.EXTRACTOR
    null, "" -> legacySourceType("AUTO", sourceUrl)
    else -> throw IllegalArgumentException("unsupported persisted catalog source type: $raw")
}
