package streamix.catalog

import kotlinx.coroutines.CancellationException
import streamix.api.StreamRef
import streamix.core.StreamixHttp
import java.net.URI

/**
 * Resolves MP4Upload pages into media-ready URLs.
 *
 * The resolver follows the current Yūzōnō behavior: MP4Upload exposes the
 * media source through either packed P.A.C.K.E.R. JavaScript or a player.src
 * fallback. No guessed download endpoint is used.
 */
class Mp4uploadCatalogResolver(
    private val http: StreamixHttp = CatalogSafeStreamixHttp()
) : CatalogResolver {
    override val id: String = "mp4upload-catalog"
    override val domains: Set<String> = setOf("mp4upload.com")

    override suspend fun resolve(source: EpisodeSource): CatalogResolverResult {
        val response = try {
            http.get(
                url = source.sourceUrl,
                headers = source.headers,
                referer = source.referer ?: REFERER
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

        val scripts = response.document.select("script")
            .mapNotNull { it.data().takeIf(String::isNotBlank) }

        val unpacked = scripts.asSequence()
            .mapNotNull(PackedJsUnpacker::unpackAndCombine)
            .firstOrNull()

        val script = unpacked ?: scripts.firstOrNull {
            it.contains("player.src", ignoreCase = false) ||
                Regex("""\bsrc\s*:""").containsMatchIn(it)
        }

        if (script.isNullOrBlank()) {
            return CatalogResolverResult(
                httpStatus = response.code,
                finalUrl = response.url,
                failureReason = "MP4Upload media script not found"
            )
        }

        val rawUrl = MEDIA_URL_REGEX.find(script)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            .orEmpty()

        if (rawUrl.isBlank()) {
            return CatalogResolverResult(
                httpStatus = response.code,
                finalUrl = response.url,
                failureReason = "MP4Upload media URL not found"
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
                failureReason = "MP4Upload media URL malformed"
            )
        }

        val mediaUri = try {
            URI(mediaUrl)
        } catch (error: IllegalArgumentException) {
            return CatalogResolverResult(
                httpStatus = response.code,
                finalUrl = response.url,
                failureReason = "MP4Upload media URL malformed"
            )
        }

        if (mediaUri.scheme !in setOf("http", "https")) {
            return CatalogResolverResult(
                httpStatus = response.code,
                finalUrl = response.url,
                failureReason = "MP4Upload media URL is not HTTP(S)"
            )
        }

        val quality = QUALITY_REGEX.find(script)
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
                        mediaUrl.substringBefore('?').endsWith(".mp4", true) -> "mp4"
                        mediaUrl.substringBefore('?').endsWith(".webm", true) -> "webm"
                        else -> "video"
                    },
                    language = source.language,
                    subtitleLanguage = source.subtitleLanguage,
                    headers = source.headers,
                    referer = source.referer ?: REFERER
                )
            ),
            httpStatus = response.code,
            finalUrl = response.url
        )
    }

    private companion object {
        const val REFERER = "https://mp4upload.com/"
        val MEDIA_URL_REGEX = Regex(
            """(?:\.src\(\s*|\bsrc\s*:\s*)["']([^"']+)["']"""
        )
        val QUALITY_REGEX = Regex("""\WHEIGHT=(\d+)""")
    }
}
