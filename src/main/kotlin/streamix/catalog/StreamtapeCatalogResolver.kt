package streamix.catalog

import kotlinx.coroutines.CancellationException
import streamix.api.StreamRef
import streamix.core.StreamixHttp
import java.net.URI

/**
 * Resolves Streamtape view pages into short-lived tapecontent.net media URLs.
 *
 * Streamtape exposes the signed get_video gate through obfuscated inline
 * assignments on the view page. This resolver decodes only the string
 * assignments needed to reconstruct that gate; it never executes page JS.
 *
 * The gate redirects to the terminal tapecontent.net CDN URL. The CDN URL
 * is returned to Media3; no proxy or resolver chaining is introduced.
 */
class StreamtapeCatalogResolver(
    private val http: StreamixHttp = CatalogSafeStreamixHttp()
) : CatalogResolver {
    override val id: String = "streamtape-catalog"

    override val domains: Set<String> = setOf(
        "streamtape.com",
        "streamtape.to"
    )

    override suspend fun resolve(source: EpisodeSource): CatalogResolverResult {
        val page = try {
            http.get(
                url = source.sourceUrl,
                headers = source.headers,
                referer = source.referer ?: defaultReferer(source.sourceUrl)
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

        val gatePath = parseGatePath(page.text)
            ?: return CatalogResolverResult(
                httpStatus = page.code,
                finalUrl = page.url,
                failureReason = "Streamtape get_video gate not found"
            )

        val gateUrl = try {
            val pageUri = URI(page.url)
            require(pageUri.scheme == "http" || pageUri.scheme == "https")
            pageUri.resolve(ensureStreamParameter(gatePath)).toString()
        } catch (error: Exception) {
            return CatalogResolverResult(
                httpStatus = page.code,
                finalUrl = page.url,
                failureReason = "Streamtape get_video URL malformed"
            )
        }

        val pageReferer = source.referer ?: page.url
        val gate = try {
            http.probe(
                url = gateUrl,
                headers = source.headers,
                referer = pageReferer,
                maxBytes = 16
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

        if (gate.code !in 200..299) {
            return CatalogResolverResult(
                httpStatus = gate.code,
                finalUrl = gate.url,
                failureReason = "Streamtape get_video HTTP " + gate.code
            )
        }

        val mediaUri = try {
            URI(gate.url)
        } catch (error: IllegalArgumentException) {
            return CatalogResolverResult(
                httpStatus = gate.code,
                finalUrl = gate.url,
                failureReason = "Streamtape media URL malformed"
            )
        }

        val host = mediaUri.host?.lowercase()?.removePrefix("www.")
        if (mediaUri.scheme !in setOf("http", "https") ||
            host == null ||
            !(host == "tapecontent.net" || host.endsWith(".tapecontent.net"))
        ) {
            return CatalogResolverResult(
                httpStatus = gate.code,
                finalUrl = gate.url,
                failureReason = "Streamtape final URL is not tapecontent.net"
            )
        }

        return CatalogResolverResult(
            streams = listOf(
                StreamRef(
                    providerId = id,
                    url = gate.url,
                    quality = source.quality,
                    type = "video",
                    language = source.language,
                    subtitleLanguage = source.subtitleLanguage,
                    headers = source.headers,
                    referer = pageReferer
                )
            ),
            httpStatus = gate.code,
            finalUrl = gate.url
        )
    }

    private fun parseGatePath(html: String): String? {
        val direct = GET_VIDEO_REGEX.find(html)?.groupValues?.getOrNull(1)
            ?.takeIf(String::isNotBlank)
        if (direct != null) return normalizeGatePath(direct)

        val assignments = html
            .replace("</script>", "\n", ignoreCase = true)
            .lineSequence()
            .filter { line ->
                line.contains("getElementById") && line.contains("innerHTML")
            }

        val values = assignments.mapNotNull { line ->
            val id = ELEMENT_ID_REGEX.find(line)?.groupValues?.getOrNull(1) ?: return@mapNotNull null
            val expression = line.substringAfter('=', "").substringBeforeLast(';', line).trim()
            val value = decodeStringLiterals(expression).trim()
            if (value.isBlank()) null else id to value
        }.toList()

        return values
            .firstOrNull { it.second.contains("get_video", ignoreCase = true) }
            ?.second
            ?.let(::normalizeGatePath)
    }

    private fun decodeStringLiterals(expression: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < expression.length) {
            val quote = expression[i]
            if (quote != '\'' && quote != '"') {
                i++
                continue
            }

            val end = findStringEnd(expression, i + 1, quote)
            if (end < 0) break

            val literal = expression.substring(i + 1, end)
            out.append(decodeJsLiteral(literal))
            i = end + 1
        }
        return out.toString()
    }

    private fun findStringEnd(value: String, start: Int, quote: Char): Int {
        var escaped = false
        for (i in start until value.length) {
            val ch = value[i]
            if (escaped) {
                escaped = false
            } else if (ch == '\\') {
                escaped = true
            } else if (ch == quote) {
                return i
            }
        }
        return -1
    }

    private fun decodeJsLiteral(value: String): String {
        return value
            .replace(Regex("""\\x([0-9a-fA-F]{2})""")) {
                it.groupValues[1].toInt(16).toChar().toString()
            }
            .replace(Regex("""\\u([0-9a-fA-F]{4})""")) {
                it.groupValues[1].toInt(16).toChar().toString()
            }
            .replace("\\'", "'")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\/", "/")
    }

    private fun normalizeGatePath(raw: String): String {
        val trimmed = raw.trim()
        return when {
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("/") -> trimmed
            else -> "/$trimmed"
        }
    }

    private fun ensureStreamParameter(gatePath: String): String =
        if (Regex("""[?&]stream=""").containsMatchIn(gatePath)) gatePath
        else gatePath + if ('?' in gatePath) "&stream=1" else "?stream=1"

    private fun defaultReferer(url: String): String = runCatching {
        val uri = URI(url)
        "${uri.scheme}://${uri.authority}/"
    }.getOrDefault("https://streamtape.com/")

    private companion object {
        val ELEMENT_ID_REGEX = Regex(
            """document\.getElementById\(['"]([^'"]+)['"]\)\.innerHTML\s*="""
        )
        val GET_VIDEO_REGEX = Regex(
            """(?i)(/get_video\?[^"'\s<>]+|https?://[^"'\s<>]+/get_video\?[^"'\s<>]+|//[^"'\s<>]+/get_video\?[^"'\s<>]+)"""
        )
    }
}
