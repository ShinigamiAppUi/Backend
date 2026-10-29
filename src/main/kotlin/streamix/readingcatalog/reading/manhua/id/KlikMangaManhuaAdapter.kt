package streamix.readingcatalog

import com.google.gson.JsonParser
import java.net.URI
import org.jsoup.nodes.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class KlikMangaManhuaReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "klikmanga-manhua"
    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) =
        entry.kind == ReadingKind.MANHUA && chapter.sources.any { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) }
    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        if (entry.kind != ReadingKind.MANHUA) return@withContext null
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) } ?: return@withContext null
        val doc = try { documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetch(source.url) else null } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { null } ?: return@withContext null
        val pages = doc.select(".reading-content img, .page-break img, .chapter-content img").mapNotNull { img ->
            sequenceOf("data-src", "data-lazy-src", "data-original", "src")
                .map { key -> img.attr(key).trim() }
                .mapNotNull { raw ->
                    raw.takeIf { it.isNotBlank() && !it.startsWith("data:", ignoreCase = true) }
                        ?.let { runCatching { URI(source.url).resolve(it).toString() }.getOrNull() }
                        ?.takeIf(::validImage)
                }
                .firstOrNull()
        }.normalizedReadingPageUrls()
        pages.takeIf { it.isNotEmpty() }?.let { ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages") }
    }
    private fun validChapter(value: String) =
        isSafeReadingChapterUrl(value, setOf("klikmanga.org")) { it.isNotBlank() }
    private fun validImage(value: String) = isSafeReadingImageUrl(value)
    private fun fetch(value: String): Document? = fetchIndonesianReadingDocument(value)
}

private fun fetchIndonesianReadingDocument(value: String): Document? =
    fetchReadingHtmlDocument(
        value,
        mapOf(
            "Accept" to "text/html,application/xhtml+xml",
            "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        ),
    )
