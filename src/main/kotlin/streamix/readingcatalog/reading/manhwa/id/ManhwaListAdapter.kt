package streamix.readingcatalog

import java.net.URI
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ManhwaListReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "manhwa-list"

    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) =
        entry.kind == ReadingKind.MANHWA && chapter.sources.any {
            it.enabled && it.providerId.equals(id, true) && validChapter(it.url)
        }

    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        if (entry.kind != ReadingKind.MANHWA) return@withContext null
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) }
            ?: return@withContext null
        val doc = try {
            if (documentFetcher != null) documentFetcher.invoke(source.url) else fetchDocument(source.url)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return@withContext null
        val pages = doc.select("#readerarea img, .reading-content img, .chapter-content img").mapNotNull { image ->
            sequenceOf("data-lazy-src", "data-src", "data-original", "src").map { image.attr(it).trim() }
                .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
                ?.let { resolveSafeImage(it, source.url) }
        }.filterNotNull().distinct()
        pages.takeIf { it.isNotEmpty() }?.let {
            ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages")
        }
    }

    private fun validChapter(value: String) = runCatching {
        URI(value).let { isSafeReadingHttpUrl(value) && it.host.equals("m1.manhwalist.my.id", true) && it.path.isNotBlank() }
    }.getOrDefault(false)

    private fun resolveSafeImage(value: String, base: String): String? = runCatching {
        val uri = URI(base).resolve(value)
        uri.toString().takeIf(::isSafeReadingImageUrl)
    }.getOrNull()

    private fun fetchDocument(value: String): Document? {
        runCatching { URI(value) }.getOrNull() ?: return null
        if (!validChapter(value)) return null
        return fetchReadingHtmlDocument(
            value,
            mapOf(
                "User-Agent" to "ShinigamiReadingRuntime/1.0",
                "Accept" to "text/html,application/xhtml+xml",
            ),
        )
    }
}
