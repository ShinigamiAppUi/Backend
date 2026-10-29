package streamix.readingcatalog

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.net.URI
import org.jsoup.nodes.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Asura Scans reader adapter. It reads this site's embedded pages payload and does not
 * guess image selectors shared by unrelated sites.
 */
class AsuraScansReadingRuntimeProvider(
    private val documentFetcher: ((String) -> Document?)? = null
) : ReadingRuntimeProvider {
    override val id = "asura-scans"

    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): Boolean =
        entry.kind != ReadingKind.NOVEL && chapter.sources.any {
            it.enabled && it.providerId.equals(id, true) && isChapterUrl(it.url)
        }

    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? =
        withContext(Dispatchers.IO) {
            if (entry.kind == ReadingKind.NOVEL) return@withContext null
            val source = chapter.sources.firstOrNull {
                it.enabled && it.providerId.equals(id, true) && isChapterUrl(it.url)
            } ?: return@withContext null
            val doc = runCatching {
                documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetchDocument(source.url) else null
            }.getOrNull() ?: return@withContext null
            val payload = doc.select("script").asSequence()
                .mapNotNull { it.data().takeIf(String::isNotBlank) ?: it.html().takeIf(String::isNotBlank) }
                .filter { it.contains("pages") }
                .mapNotNull(::extractPagesPayload)
                .firstOrNull() ?: return@withContext null
            val pages = payload.mapNotNull { node ->
                if (!node.isJsonObject) return@mapNotNull null
                val obj = node.asJsonObject
                sequenceOf("url", "src", "image").mapNotNull { key ->
                    obj.get(key)?.takeIf { it.isJsonPrimitive }?.asString
                }.firstOrNull()?.takeIf(::validPageUrl)
            }.distinct()
            pages.takeIf { it.isNotEmpty() }?.let {
                ReadingContent(entry.kind, "reading-provider:$id", contentUrl = source.url, pageUrls = it, format = "image-pages")
            }
        }

    private fun extractPagesPayload(raw: String): List<JsonElement>? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until raw.length) {
            val ch = raw[index]
            if (inString) {
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == '"') inString = false
            } else {
                when (ch) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            val parsed = runCatching {
                                JsonParser.parseString(raw.substring(start, index + 1)).asJsonObject
                            }.getOrNull() ?: return null
                            val candidates = listOf(
                                parsed.get("pages"),
                                parsed.getAsJsonObject("props")?.get("pages"),
                                parsed.getAsJsonObject("pageProps")?.get("pages"),
                                parsed.getAsJsonObject("data")?.get("pages")
                            )
                            return candidates.firstNotNullOfOrNull { it?.takeIf { v -> v.isJsonArray }?.asJsonArray?.toList() }
                        }
                    }
                }
            }
        }
        return null
    }

    private fun isChapterUrl(value: String): Boolean = runCatching {
        val u = URI(value)
        u.scheme.equals("https", true) &&
            (u.host.equals("asuracomic.net", true) || u.host.equals("asurascans.com", true)) &&
            u.userInfo == null && (u.port == -1 || u.port == 443) &&
            u.path.contains("/chapter/", true)
    }.getOrDefault(false)

    private fun validPageUrl(value: String): Boolean = runCatching {
        val u = URI(value)
        u.scheme.equals("https", true) && !u.host.isNullOrBlank() && u.userInfo == null && (u.port == -1 || u.port == 443)
    }.getOrDefault(false)

    private fun fetchDocument(value: String): Document? =
        fetchReadingHtmlDocument(
            value,
            mapOf(
                "User-Agent" to "ShinigamiReadingRuntime/1.0",
                "Accept" to "text/html,application/xhtml+xml",
            ),
        )
}
