package streamix.catalog

import kotlinx.coroutines.CancellationException
import streamix.api.StreamRef
import streamix.core.StreamixHttp

/**
 * Resolves BurstCloud pages into the CDN URL returned by the host's play-request API.
 *
 * Reference behavior follows Yūzōnō's BurstCloudExtractor. The page supplies a
 * player file id, then the host API returns purchase.cdnUrl. That CDN URL is
 * terminal for the catalog resolver contract.
 */
class BurstCloudCatalogResolver(
    private val http: StreamixHttp = CatalogSafeStreamixHttp()
) : CatalogResolver {
    override val id: String = "burstcloud-catalog"
    override val domains: Set<String> = setOf("burstcloud.co")

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

        val fileId = FILE_ID_REGEX.find(page.text)?.groupValues?.getOrNull(1)
            ?: return CatalogResolverResult(
                httpStatus = page.code,
                finalUrl = page.url,
                failureReason = "BurstCloud player file id not found"
            )

        val apiHeaders = source.headers + ("Referer" to page.url)
        val api = try {
            http.post(
                url = PLAY_REQUEST_URL,
                body = """{"fileId":"$fileId"}""",
                headers = apiHeaders,
                referer = page.url
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return CatalogResolverResult(failureReason = error.message?.take(512))
        }

        if (api.code !in 200..299) {
            return CatalogResolverResult(
                httpStatus = api.code,
                finalUrl = api.url,
                failureReason = "BurstCloud play-request HTTP " + api.code
            )
        }

        val mediaUrl = CDN_URL_REGEX.find(api.text)?.groupValues?.getOrNull(1)
            ?: return CatalogResolverResult(
                httpStatus = api.code,
                finalUrl = api.url,
                failureReason = "BurstCloud CDN URL not found"
            )

        if (!mediaUrl.startsWith("http://") && !mediaUrl.startsWith("https://")) {
            return CatalogResolverResult(
                httpStatus = api.code,
                finalUrl = api.url,
                failureReason = "BurstCloud CDN URL is not HTTP(S)"
            )
        }

        return CatalogResolverResult(
            streams = listOf(
                StreamRef(
                    providerId = id,
                    url = mediaUrl,
                    quality = source.quality,
                    type = "video",
                    language = source.language,
                    subtitleLanguage = source.subtitleLanguage,
                    headers = apiHeaders,
                    referer = page.url
                )
            ),
            httpStatus = api.code,
            finalUrl = api.url
        )
    }

    private companion object {
        const val PLAY_REQUEST_URL = "https://www.burstcloud.co/file/play-request/"
        val FILE_ID_REGEX = Regex(
            """data-file-id\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        val CDN_URL_REGEX = Regex(
            """"cdnUrl"\s*:\s*"([^"]+)"""",
            RegexOption.IGNORE_CASE
        )
    }
}
