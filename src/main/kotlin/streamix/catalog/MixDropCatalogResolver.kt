package streamix.catalog

import kotlinx.coroutines.CancellationException
import streamix.api.StreamRef
import streamix.api.SubtitleRef
import streamix.core.StreamixHttp
import java.net.URI
import java.net.URLDecoder

/**
 * Resolves MixDrop pages into media-ready URLs.
 *
 * Reference behavior: Yūzōnō's MixDropExtractor reads a packed script containing
 * MixDrop's Core.wurl and optional Core.remotesub values. Shinigami decodes only
 * the packed data and never executes page JavaScript.
 */
class MixDropCatalogResolver(
    private val http: StreamixHttp = CatalogSafeStreamixHttp()
) : CatalogResolver {
    override val id: String = "mixdrop-catalog"
    override val domains: Set<String> = setOf(
        "mixdrop.ag",
        "mixdrop.co",
        "mixdrop.to"
    )

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

        val packedScript = response.document
            .select("script:containsData(eval):containsData(MDCore)")
            .firstOrNull()
            ?.data()

        if (packedScript.isNullOrBlank()) {
            return CatalogResolverResult(
                httpStatus = response.code,
                finalUrl = response.url,
                failureReason = "MixDrop packed media script not found"
            )
        }

        val unpacked = PackedJsUnpacker.unpackAndCombine(packedScript)
            ?: return CatalogResolverResult(
                httpStatus = response.code,
                finalUrl = response.url,
                failureReason = "MixDrop packed media script could not be decoded"
            )

        val rawUrl = unpacked.substringAfter("Core.wurl=\"", "")
            .substringBefore("\"")
            .trim()

        if (rawUrl.isBlank()) {
            return CatalogResolverResult(
                httpStatus = response.code,
                finalUrl = response.url,
                failureReason = "MixDrop media URL not found"
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
                failureReason = "MixDrop media URL malformed"
            )
        }

        val mediaUri = try {
            URI(mediaUrl)
        } catch (error: IllegalArgumentException) {
            return CatalogResolverResult(
                httpStatus = response.code,
                finalUrl = response.url,
                failureReason = "MixDrop media URL malformed"
            )
        }

        if (mediaUri.scheme !in setOf("http", "https")) {
            return CatalogResolverResult(
                httpStatus = response.code,
                finalUrl = response.url,
                failureReason = "MixDrop media URL is not HTTP(S)"
            )
        }

        val subtitleUrl = unpacked.substringAfter("Core.remotesub=\"", "")
            .substringBefore("\"")
            .trim()
            .takeIf(String::isNotBlank)
            ?.let {
                runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
            }

        val subtitles = subtitleUrl
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?.let { listOf(it) }
            ?: emptyList()

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
                    referer = source.referer ?: REFERER,
                    subtitles = subtitles.map { SubtitleRef(url = it, language = source.subtitleLanguage) }
                )
            ),
            httpStatus = response.code,
            finalUrl = response.url
        )
    }

    private companion object {
        const val REFERER = "https://mixdrop.co/"
    }
}
