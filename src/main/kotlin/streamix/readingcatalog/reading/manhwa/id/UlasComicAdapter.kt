package streamix.readingcatalog

import java.net.URI
import java.net.URLEncoder
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UlasComicReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "ulas-comic"

    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) =
        entry.kind == ReadingKind.MANHWA && chapter.sources.any {
            it.enabled && it.providerId.equals(id, true) && validChapter(it.url)
        }

    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        if (entry.kind != ReadingKind.MANHWA) return@withContext null
        val source = chapter.sources.firstOrNull {
            it.enabled && it.providerId.equals(id, true) && validChapter(it.url)
        } ?: return@withContext null
        val doc = runCatching {
            if (documentFetcher != null) documentFetcher.invoke(source.url) else fetchDoc(source.url)
        }.getOrNull() ?: return@withContext null
        val direct = doc.select("div.check-box div.separator img, article div.separator img, .readerarea img")
            .mapNotNull { image ->
                sequenceOf("data-src", "data-lazy-src", "data-original", "src").map { image.attr(it).trim() }
                    .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
                    ?.let { resolveSafeImage(it, source.url) }
            }
        val scripts = doc.select("script").flatMap { script ->
            Regex("""https?://[^\\"'\\s]+""").findAll(script.data()).map { it.value.trimEnd(',', ')') }.toList()
        }.mapNotNull { resolveSafeImage(it, source.url) }
        (direct + scripts).distinct().takeIf { it.isNotEmpty() }?.let {
            ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages")
        }
    }

    private fun validChapter(value: String) = runCatching {
        URI(value).let { isSafeReadingHttpUrl(value) && it.host.equals("www.ulascomic01.xyz", true) && it.path.isNotBlank() }
    }.getOrDefault(false)

    private fun resolveSafeImage(value: String, base: String): String? = runCatching {
        val uri = URI(base).resolve(value)
        uri.toString().takeIf(::isSafeReadingImageUrl)
    }.getOrNull()
}

private fun fetchText(value: String, accept: String): String? {
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    if (!isSafeReadingHttpUrl(value)) return null
    return ReadingHttpTransport.getText(
        value,
        mapOf(
            "User-Agent" to "ShinigamiReadingRuntime/1.0",
            "Accept" to accept,
        ),
    )
}

private fun fetchDoc(value: String): Document? {
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    if (!isSafeReadingHttpUrl(value)) return null
    return fetchReadingHtmlDocument(
        value,
        mapOf(
            "User-Agent" to "ShinigamiReadingRuntime/1.0",
            "Accept" to "text/html,application/xhtml+xml",
        ),
    )
}
