package streamix.catalog

import kotlinx.coroutines.CancellationException
import streamix.api.StreamRef
import streamix.core.StreamixHttp
import java.net.URI
import kotlin.random.Random

/**
 * Resolves Dood/DoodStream pages into short-lived media-ready URLs.
 *
 * Reference behavior follows Yūzōnō's DoodExtractor: the page exposes a
 * /pass_md5/ token endpoint, whose response is combined with a random path
 * component and token/expiry query parameters.
 */
class DoodCatalogResolver(
    private val http: StreamixHttp = CatalogSafeStreamixHttp()
) : CatalogResolver {
    override val id: String = "dood-catalog"
    override val domains: Set<String> = setOf(
        "dood.to",
        "doodstream.com"
    )

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

        val passMd5Path = PASS_MD5_REGEX.find(page.text)?.value
            ?: return CatalogResolverResult(
                httpStatus = page.code,
                finalUrl = page.url,
                failureReason = "Dood pass_md5 endpoint not found"
            )

        val doodHost = try {
            val uri = URI(page.url)
            require(uri.scheme == "http" || uri.scheme == "https")
            uri.scheme + "://" + uri.host
        } catch (error: Exception) {
            return CatalogResolverResult(
                httpStatus = page.code,
                finalUrl = page.url,
                failureReason = "Dood final URL malformed"
            )
        }

        val md5Url = doodHost + passMd5Path
        val token = md5Url.substringAfterLast('/').takeIf(String::isNotBlank)
            ?: return CatalogResolverResult(
                httpStatus = page.code,
                finalUrl = page.url,
                failureReason = "Dood token missing"
            )

        val mediaBase = try {
            http.get(
                url = md5Url,
                headers = source.headers,
                referer = page.url
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return CatalogResolverResult(
                httpStatus = page.code,
                finalUrl = page.url,
                failureReason = error.message?.take(512)
            )
        }

        if (mediaBase.code !in 200..299) {
            return CatalogResolverResult(
                httpStatus = mediaBase.code,
                finalUrl = mediaBase.url,
                failureReason = "Dood pass_md5 HTTP " + mediaBase.code
            )
        }

        val base = mediaBase.text.trim().takeIf(String::isNotBlank)
            ?: return CatalogResolverResult(
                httpStatus = mediaBase.code,
                finalUrl = mediaBase.url,
                failureReason = "Dood media base URL missing"
            )

        val expiry = System.currentTimeMillis()
        val mediaUrl = base + randomPath() + "?token=" + token + "&expiry=" + expiry

        val mediaUri = try {
            URI(mediaUrl)
        } catch (error: IllegalArgumentException) {
            return CatalogResolverResult(
                httpStatus = mediaBase.code,
                finalUrl = mediaBase.url,
                failureReason = "Dood media URL malformed"
            )
        }

        if (mediaUri.scheme !in setOf("http", "https") || mediaUri.host.isNullOrBlank()) {
            return CatalogResolverResult(
                httpStatus = mediaBase.code,
                finalUrl = mediaBase.url,
                failureReason = "Dood media URL is not HTTP(S)"
            )
        }

        val playbackHeaders = source.headers + ("Referer" to page.url)

        return CatalogResolverResult(
            streams = listOf(
                StreamRef(
                    providerId = id,
                    url = mediaUrl,
                    quality = source.quality ?: QUALITY_REGEX.find(page.text)?.groupValues?.getOrNull(1)?.toIntOrNull(),
                    type = "video",
                    language = source.language,
                    subtitleLanguage = source.subtitleLanguage,
                    headers = playbackHeaders,
                    referer = page.url
                )
            ),
            httpStatus = mediaBase.code,
            finalUrl = mediaBase.url
        )
    }

    private fun randomPath(length: Int = 10): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return buildString(length) {
            repeat(length) {
                append(alphabet[Random.nextInt(alphabet.length)])
            }
        }
    }

    private companion object {
        val PASS_MD5_REGEX = Regex("""/pass_md5/[^']*""")
        val QUALITY_REGEX = Regex("""\b(\d{3,4})p\b""")
    }
}