package streamix.catalog

import streamix.api.StreamRef
import streamix.core.StreamixHttp
import java.net.URI

/**
 * Resolves KrakenFiles container/view pages into media-ready URLs.
 *
 * This is intentionally a catalog RESOLVER, not an extractor: the catalog
 * source remains the stable KrakenFiles page and the resolver performs the
 * host-specific page -> media transformation.
 */
class KrakenFilesCatalogResolver(
    private val http: StreamixHttp = CatalogSafeStreamixHttp()
) : CatalogResolver {
    override val id: String = "krakenfiles-catalog"
    override val domains: Set<String> = setOf("krakenfiles.com")

    override suspend fun resolve(source: EpisodeSource): CatalogResolverResult {
        val id = Regex("/(?:view|embed-video)/([0-9A-Za-z]+)")
            .find(URI(source.sourceUrl).path.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?: return CatalogResolverResult(failureReason = "invalid KrakenFiles id")

        val response = http.get(
            url = "https://krakenfiles.com/embed-video/$id",
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

        val raw = response.document.selectFirst("video source[src], source[src]")
            ?.attr("src")
            ?.trim()
            .orEmpty()

        if (raw.isBlank()) {
            return CatalogResolverResult(
                httpStatus = response.code,
                finalUrl = response.url,
                failureReason = "media source not found"
            )
        }

        val mediaUrl = when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            else -> URI(response.url).resolve(raw).toString()
        }

        return CatalogResolverResult(
            streams = listOf(
                StreamRef(
                    providerId = id,
                    url = mediaUrl,
                    quality = source.quality,
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
