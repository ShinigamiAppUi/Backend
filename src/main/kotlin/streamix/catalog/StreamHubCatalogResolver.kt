package streamix.catalog

import kotlinx.coroutines.CancellationException
import streamix.api.StreamRef
import streamix.core.StreamixHttp

/**
 * Resolves StreamHub pages into the host-generated HLS master playlist.
 *
 * Reference behavior follows Yūzōnō's StreamHubExtractor: the page exposes
 * the media id and CDN subdomain, which are combined into the terminal HLS URL.
 */
class StreamHubCatalogResolver(
    private val http: StreamixHttp = CatalogSafeStreamixHttp()
) : CatalogResolver {
    override val id: String = "streamhub-catalog"
    override val domains: Set<String> = setOf("streamhub.ink")

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

        val mediaId = ID_REGEX.find(page.text)?.groupValues?.getOrNull(1)
            ?: return CatalogResolverResult(
                httpStatus = page.code,
                finalUrl = page.url,
                failureReason = "StreamHub media id not found"
            )
        val cdnSubdomain = SUBDOMAIN_REGEX.find(page.text)?.groupValues?.getOrNull(1)
            ?: return CatalogResolverResult(
                httpStatus = page.code,
                finalUrl = page.url,
                failureReason = "StreamHub CDN subdomain not found"
            )

        if (!SAFE_SUBDOMAIN.matches(cdnSubdomain) || mediaId.isBlank()) {
            return CatalogResolverResult(
                httpStatus = page.code,
                finalUrl = page.url,
                failureReason = "StreamHub media parameters malformed"
            )
        }

        val mediaUrl = "https://$cdnSubdomain.streamhub.ink/hls/,$mediaId,.urlset/master.m3u8"
        val playbackHeaders = source.headers + ("Referer" to page.url)

        return CatalogResolverResult(
            streams = listOf(
                StreamRef(
                    providerId = id,
                    url = mediaUrl,
                    quality = source.quality,
                    type = "hls",
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
        val ID_REGEX = Regex("""urlset\|([^|]+)\|""", RegexOption.IGNORE_CASE)
        val SUBDOMAIN_REGEX = Regex("""width\|([^|]+)\|""", RegexOption.IGNORE_CASE)
        val SAFE_SUBDOMAIN = Regex("""[A-Za-z0-9-]+""")
    }
}
