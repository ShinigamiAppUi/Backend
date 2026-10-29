package streamix.catalog

import streamix.api.StreamRef
import streamix.core.StreamixHttp
import java.net.URI

/**
 * Resolves MediaFire file pages into media-ready download URLs.
 *
 * The catalog source remains the stable MediaFire page. The resolver only
 * performs the host-specific page -> media transformation; Media3 remains
 * the final playback authority.
 */
class MediafireCatalogResolver(
    private val http: StreamixHttp = CatalogSafeStreamixHttp()
) : CatalogResolver {
    override val id: String = "mediafire-catalog"
    override val domains: Set<String> = setOf("mediafire.com")

    override suspend fun resolve(source: EpisodeSource): CatalogResolverResult {
        val response = http.get(
            url = source.sourceUrl,
            headers = source.headers,
            referer = source.referer ?: source.sourceUrl
        )

        if (response.code !in 200..299) {
            return CatalogResolverResult(
                httpStatus = response.code,
                finalUrl = response.url,
                failureReason = "HTTP ${response.code}"
            )
        }

        val title = response.document.select("div.dl-btn-label").text()
        val rawUrl = response.document.selectFirst("a#downloadButton")
            ?.attr("href")
            ?.trim()
            .orEmpty()

        if (rawUrl.isBlank()) {
            return CatalogResolverResult(
                httpStatus = response.code,
                finalUrl = response.url,
                failureReason = "MediaFire download link not found"
            )
        }

        val mediaUrl = when {
            rawUrl.startsWith("//") -> "https:$rawUrl"
            rawUrl.startsWith("http://") || rawUrl.startsWith("https://") -> rawUrl
            else -> URI(response.url).resolve(rawUrl).toString()
        }

        val quality = Regex("""(\d{3,4})[pP]""")
            .find(title)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: source.quality

        return CatalogResolverResult(
            streams = listOf(
                StreamRef(
                    providerId = id,
                    url = mediaUrl,
                    quality = quality,
                    type = when {
                        mediaUrl.substringBefore('?').endsWith(".m3u8", true) -> "m3u8"
                        mediaUrl.substringBefore('?').endsWith(".mpd", true) -> "dash"
                        else -> "video"
                    },
                    language = source.language,
                    subtitleLanguage = source.subtitleLanguage,
                    headers = source.headers,
                    referer = source.referer ?: response.url
                )
            ),
            httpStatus = response.code,
            finalUrl = response.url
        )
    }
}
