package streamix.catalog

import kotlinx.coroutines.CancellationException
import streamix.api.StreamRef
import streamix.core.StreamixHttp
import java.net.URI

/**
 * Resolves Sendvid pages into the media URL exposed by the host's video source tag.
 *
 * Reference behavior follows Yūzōnō's SendvidExtractor: the stable page exposes
 * source#video_source. Shinigami keeps the result terminal at the catalog
 * boundary; it does not invoke another extractor or playlist resolver.
 */
class SendvidCatalogResolver(
    private val http: StreamixHttp = CatalogSafeStreamixHttp()
) : CatalogResolver {
    override val id: String = "sendvid-catalog"
    override val domains: Set<String> = setOf("sendvid.com")

    override suspend fun resolve(source: EpisodeSource): CatalogResolverResult {
        val page = try {
            http.get(
                url = source.sourceUrl,
                headers = source.headers,
                referer = source.referer
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return CatalogResolverResult(failureReason = error.message?.take(512))
        }

        if (page.code !in 200..299) {
            return CatalogResolverResult(
                httpStatus = page.code,
                finalUrl = page.url,
                failureReason = "HTTP " + page.code
            )
        }

        val rawUrl = VIDEO_SOURCE_REGEX.find(page.text)?.groupValues?.getOrNull(1)
            ?: return CatalogResolverResult(
                httpStatus = page.code,
                finalUrl = page.url,
                failureReason = "Sendvid video source not found"
            )

        val mediaUrl = try {
            URI(page.url).resolve(rawUrl).toString()
        } catch (error: Exception) {
            return CatalogResolverResult(
                httpStatus = page.code,
                finalUrl = page.url,
                failureReason = "Sendvid media URL malformed"
            )
        }

        val mediaUri = try {
            URI(mediaUrl)
        } catch (error: IllegalArgumentException) {
            return CatalogResolverResult(
                httpStatus = page.code,
                finalUrl = page.url,
                failureReason = "Sendvid media URL malformed"
            )
        }

        if (mediaUri.scheme !in setOf("http", "https") || mediaUri.host.isNullOrBlank()) {
            return CatalogResolverResult(
                httpStatus = page.code,
                finalUrl = page.url,
                failureReason = "Sendvid media URL is not HTTP(S)"
            )
        }

        val type = if (mediaUri.path?.contains(".m3u8", ignoreCase = true) == true) "hls" else "video"
        val playbackHeaders = source.headers + ("Referer" to page.url)

        return CatalogResolverResult(
            streams = listOf(
                StreamRef(
                    providerId = id,
                    url = mediaUrl,
                    quality = source.quality,
                    type = type,
                    language = source.language,
                    subtitleLanguage = source.subtitleLanguage,
                    headers = playbackHeaders,
                    referer = page.url
                )
            ),
            httpStatus = page.code,
            finalUrl = page.url
        )
    }

    private companion object {
        val VIDEO_SOURCE_REGEX = Regex(
            """<source\b[^>]*\bid\s*=\s*["']video_source["'][^>]*\bsrc\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
    }
}
