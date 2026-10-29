package streamix.readingcatalog

import com.google.gson.JsonParser
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document

/** Ryukomik stores its page list in the chapter document's images JSON field. */
class RyukomikReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "ryukomik"

    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): Boolean =
        entry.kind == ReadingKind.MANGA && chapter.sources.any {
            it.enabled && it.providerId.equals(id, true) && validChapterUrl(it.url)
        }

    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? =
        withContext(Dispatchers.IO) {
            if (entry.kind != ReadingKind.MANGA) return@withContext null
            val source = chapter.sources.firstOrNull {
                it.enabled && it.providerId.equals(id, true) && validChapterUrl(it.url)
            } ?: return@withContext null
            val document = try {
                documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetch(source.url) else null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: return@withContext null

            val html = document.toString()
            val images = extractImagesArray(html)?.let { raw ->
                runCatching { JsonParser.parseString(raw).asJsonArray }.getOrNull()?.mapNotNull { item ->
                    if (!item.isJsonPrimitive || !item.asJsonPrimitive.isString) return@mapNotNull null
                    val value = item.asString.trim()
                        .replace("\\/", "/")
                    if (value.isBlank() || value.startsWith("data:", true)) return@mapNotNull null
                    val resolved = runCatching { URI("https://ryukomik.my.id/").resolve(value).toString() }.getOrNull()
                    resolved?.takeIf(::validImageUrl)
                }.orEmpty()
            }.orEmpty().normalizedReadingPageUrls()

            images.takeIf { it.isNotEmpty() }?.let {
                ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages")
            }
        }

    private fun extractImagesArray(html: String): String? {
        val key = Regex("""\\?"images\\?"\s*:\s*\[""").find(html) ?: return null
        val start = html.indexOf('[', key.range.first)
        if (start < 0) return null
        var depth = 0
        var quoted = false
        var escaped = false
        for (index in start until html.length) {
            val ch = html[index]
            if (escaped) { escaped = false; continue }
            if (ch == '\\' && quoted) { escaped = true; continue }
            if (ch == '"') { quoted = !quoted; continue }
            if (!quoted) {
                if (ch == '[') depth++
                if (ch == ']') {
                    depth--
                    if (depth == 0) {
                        return html.substring(start, index + 1)
                            .replace("\\\"", "\"")
                            .replace("\\\\", "\\")
                    }
                }
            }
        }
        return null
    }

    private fun validChapterUrl(value: String) = isSafeReadingChapterUrl(
        value, setOf("ryukomik.my.id", "www.ryukomik.my.id")
    ) { path -> path.contains("/chapter/", true) }

    private fun validImageUrl(value: String) = isSafeReadingImageUrl(value)

    private fun fetch(value: String): Document? = fetchReadingHtmlDocument(
        value,
        mapOf("Accept" to "text/html,application/xhtml+xml", "Accept-Language" to "id-ID,id;q=0.9,en;q=0.7"),
    )
}
