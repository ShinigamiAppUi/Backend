package streamix.catalog

import streamix.api.StreamRef
import streamix.core.StreamixHttp
import java.net.URI
import kotlinx.coroutines.CancellationException

class BuzzHeavierCatalogResolver(
    private val http: StreamixHttp = CatalogSafeStreamixHttp()
) : CatalogResolver {
    override val id: String = "buzzheavier-catalog"
    override val domains: Set<String> = setOf("buzzheavier.com")

    override suspend fun resolve(source: EpisodeSource): CatalogResolverResult {
        val uri = runCatching { URI(source.sourceUrl) }.getOrNull()
            ?: return CatalogResolverResult(failureReason = "invalid BuzzHeavier URL")
        val id = Regex("""/([A-Za-z0-9_-]+)(?:/)?$""")
            .find(uri.path.orEmpty())
            ?.groupValues
            ?.getOrNull(1)

        val response = try {
            http.get(
                source.sourceUrl,
                headers = source.headers + mapOf("Accept" to "text/html"),
                referer = source.referer
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
                failureReason = "HTTP " + response.code
            )
        }

        // BuzzHeavier generates signed download URLs on the page. Do not
        // invent a download endpoint: only accept actual media URLs or
        // generated /d/{id}?… download candidates present in the response.
        val mediaUrls = Regex(
            """https?://[^\s"'<>]+?\.(?:mp4|m3u8|mkv|mpd|webm)(?:\?[^\s"'<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(response.text).map { it.value }

        val signedDownloads = Regex(
            """https?://[^\s"'<>]+/d/[A-Za-z0-9_-]+(?:\?[^\s"'<>]+)?""",
            RegexOption.IGNORE_CASE
        ).findAll(response.text).map { it.value }

        val streams = (mediaUrls + signedDownloads)
            .filter { it.isNotBlank() }
            .distinct()
            .map { url ->
                StreamRef(
                    providerId = id ?: this.id,
                    url = url,
                    quality = source.quality,
                    type = when {
                        url.substringBefore('?').endsWith(".m3u8", true) -> "m3u8"
                        url.substringBefore('?').endsWith(".mpd", true) -> "dash"
                        url.substringBefore('?').endsWith(".mp4", true) -> "mp4"
                        url.substringBefore('?').endsWith(".webm", true) -> "webm"
                        url.substringBefore('?').endsWith(".mkv", true) -> "mkv"
                        else -> "video"
                    },
                    language = source.language,
                    subtitleLanguage = source.subtitleLanguage,
                    headers = source.headers,
                    referer = source.referer ?: response.url
                )
            }
            .toList()

        return if (streams.isNotEmpty()) {
            CatalogResolverResult(
                streams = streams,
                httpStatus = response.code,
                finalUrl = response.url
            )
        } else {
            CatalogResolverResult(
                httpStatus = response.code,
                finalUrl = response.url,
                failureReason = "BuzzHeavier media/download link not found"
            )
        }
    }

}
