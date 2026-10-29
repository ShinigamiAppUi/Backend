package streamix.readingcatalog

import java.net.URI
import org.jsoup.nodes.Document
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MangaKatanaReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "mangakatana"
    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) =
        entry.kind != ReadingKind.NOVEL && chapter.sources.any { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) }
    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) } ?: return@withContext null
        val doc = try { documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetch(source.url) else null } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null } ?: return@withContext null
        val pages = doc.select("#imgs img, .container-chapter-reader img").mapNotNull { img ->
            sequenceOf("data-lazy-src", "data-src", "data-original", "src").map(String::trim).firstNotNullOfOrNull { key -> img.attr(key).takeIf { it.isNotBlank() && !it.startsWith("data:") } }
                ?.let { runCatching { URI(source.url).resolve(it).toString() }.getOrNull() }?.takeIf(::validImage)
        }.distinct()
        pages.takeIf { it.isNotEmpty() }?.let { ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages") }
    }
    private fun validChapter(value: String) = isSafeReadingChapterUrl(value, setOf("mangakatana.com")) { it.contains("/manga/", true) }
    private fun validImage(value: String) = isSafeReadingImageUrl(value)
    private fun fetch(value: String): Document? = fetchReadingDocument(value)
}

private fun fetchReadingDocument(value: String): Document? =
    fetchReadingHtmlDocument(value, mapOf("Accept" to "text/html,application/xhtml+xml"))
