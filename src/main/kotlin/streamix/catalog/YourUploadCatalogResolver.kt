package streamix.catalog

import kotlinx.coroutines.CancellationException
import streamix.api.StreamRef
import streamix.core.StreamixHttp
import java.net.URI

/**
 * Resolves YourUpload pages into the JWPlayer media URL exposed by the page.
 *
 * Contract follows the current Yūzōnō YourUpload implementation: the page is
 * fetched with a YourUpload referer and the jwplayerOptions file field is the
 * source of truth. No guessed download endpoint is used.
 */
class YourUploadCatalogResolver(
    private val http: StreamixHttp = CatalogSafeStreamixHttp()
) : CatalogResolver {
    override val id: String = "yourupload-catalog"
    override val domains: Set<String> = setOf("yourupload.com")

    override suspend fun resolve(source: EpisodeSource): CatalogResolverResult {
        val response = try {
            http.get(
                url = source.sourceUrl,
                headers = source.headers,
                referer = source.referer ?: "https://www.yourupload.com/"
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return CatalogResolverResult(failureReason = error.message?.take(512))
        }

        if (response.code !in 200..299) {
            return CatalogResolverResult(
                httpStatus = response.code,
                finalUrl = response.url,
                failureReason = "HTTP ${response.code}"
            )
        }

        val script = response.document
            .selectFirst("script:containsData(jwplayerOptions)")
            ?.data()
            .orEmpty()

        if (script.isBlank()) {
            return CatalogResolverResult(
                httpStatus = response.code,
                finalUrl = response.url,
                failureReason = "YourUpload jwplayerOptions not found"
            )
        }

        val rawUrl = Regex("""file\s*:\s*['"]([^'"]+)['"]""")
            .find(script)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            .orEmpty()

        if (rawUrl.isBlank()) {
            return CatalogResolverResult(
                httpStatus = response.code,
                finalUrl = response.url,
                failureReason = "YourUpload media URL not found"
            )
        }

        val mediaUrl = try {
            when {
                rawUrl.startsWith("//") -> "https:$rawUrl"
                rawUrl.startsWith("http://") || rawUrl.startsWith("https://") -> rawUrl
                else -> URI(response.url).resolve(rawUrl).toString()
            }
        } catch (error: IllegalArgumentException) {
            return CatalogResolverResult(
                httpStatus = response.code,
                finalUrl = response.url,
                failureReason = "YourUpload media URL malformed"
            )
        }

        val mediaUri = try {
            URI(mediaUrl)
        } catch (error: IllegalArgumentException) {
            return CatalogResolverResult(
                httpStatus = response.code,
                finalUrl = response.url,
                failureReason = "YourUpload media URL malformed"
            )
        }

        if (mediaUri.scheme !in setOf("http", "https")) {
            return CatalogResolverResult(
                httpStatus = response.code,
                finalUrl = response.url,
                failureReason = "YourUpload media URL is not HTTP(S)"
            )
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
                        mediaUrl.substringBefore('?').endsWith(".mp4", true) -> "mp4"
                        mediaUrl.substringBefore('?').endsWith(".webm", true) -> "webm"
                        else -> "video"
                    },
                    language = source.language,
                    subtitleLanguage = source.subtitleLanguage,
                    headers = source.headers,
                    referer = source.referer ?: "https://www.yourupload.com/"
                )
            ),
            httpStatus = response.code,
            finalUrl = response.url
        )
    }
}
