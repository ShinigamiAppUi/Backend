package streamix.catalog

import com.google.gson.JsonParser
import streamix.api.StreamRef
import streamix.core.StreamixHttp
import java.net.URI
import kotlinx.coroutines.CancellationException

class GofileCatalogResolver(
    private val http: StreamixHttp = CatalogSafeStreamixHttp()
) : CatalogResolver {
    override val id: String = "gofile-catalog"
    override val domains: Set<String> = setOf("gofile.io")
    private val websiteTokens = listOf("4fd6sg89d7s6", "1234567890abcdef")

    override suspend fun resolve(source: EpisodeSource): CatalogResolverResult {
        val contentId = Regex("""/d/([A-Za-z0-9]+)|/w/([A-Za-z0-9]+)""")
            .find(URI(source.sourceUrl).path.orEmpty())
            ?.let { it.groupValues[1].ifBlank { it.groupValues[2] } }
            ?: return CatalogResolverResult(failureReason = "invalid Gofile content id")

        var lastFailure: CatalogResolverResult? = null
        for (token in websiteTokens) {
            val response = try {
                http.get(
                    "https://api.gofile.io/contents/" + contentId + "?wt=" + token + "&cache=true",
                    headers = source.headers + mapOf("Accept" to "application/json"),
                    referer = source.referer ?: "https://gofile.io"
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (lastFailure == null) {
                    lastFailure = CatalogResolverResult(failureReason = error.message?.take(512))
                }
                continue
            }
            if (response.code !in 200..299) {
                lastFailure = CatalogResolverResult(httpStatus = response.code, finalUrl = response.url, failureReason = "HTTP " + response.code)
                continue
            }
            val root = runCatching { JsonParser.parseString(response.text).asJsonObject }.getOrNull()
                ?: run {
                    lastFailure = CatalogResolverResult(httpStatus = response.code, finalUrl = response.url, failureReason = "invalid Gofile JSON")
                    continue
                }
            if (!root.get("status")?.asString.equals("ok", ignoreCase = true)) {
                lastFailure = CatalogResolverResult(
                    streams = emptyList(),
                    httpStatus = response.code,
                    finalUrl = response.url,
                    failureReason = root.get("status")?.asString ?: "Gofile API returned non-ok status"
                )
                continue
            }
            val children = root.getAsJsonObject("data")?.getAsJsonObject("children")
            if (children == null) {
                lastFailure = CatalogResolverResult(httpStatus = response.code, finalUrl = response.url, failureReason = "Gofile children not found")
                continue
            }
            val streams = children.entrySet().mapNotNull { (_, value) ->
                val file = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                if (!file.get("type")?.asString.equals("file", ignoreCase = true)) return@mapNotNull null
                val url = file.get("link")?.asString
                    ?.takeIf { it.isNotBlank() }
                    ?: file.get("directLink")?.asString.orEmpty()
                if (url.isBlank()) return@mapNotNull null
                StreamRef(
                    providerId = id,
                    url = url,
                    quality = source.quality,
                    type = when {
                        url.substringBefore('?').endsWith(".m3u8", true) -> "m3u8"
                        url.substringBefore('?').endsWith(".mpd", true) -> "dash"
                        else -> "video"
                    },
                    language = source.language,
                    subtitleLanguage = source.subtitleLanguage,
                    headers = source.headers,
                    referer = source.referer ?: "https://gofile.io"
                )
            }
            if (streams.isNotEmpty()) {
                return CatalogResolverResult(
                    streams = streams.distinctBy { it.url },
                    httpStatus = response.code,
                    finalUrl = response.url
                )
            }
            lastFailure = CatalogResolverResult(httpStatus = response.code, finalUrl = response.url, failureReason = "Gofile returned no playable files")
        }
        return lastFailure ?: CatalogResolverResult(failureReason = "Gofile resolution failed")
    }
}
