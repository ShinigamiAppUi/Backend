package streamix.readingcatalog

import java.net.URI
import org.jsoup.nodes.Document
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MangaNowReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "manganow"
    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) =
        entry.kind == ReadingKind.MANGA && chapter.sources.any { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) }
    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) } ?: return@withContext null
        val doc = try { documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetch(source.url) else null } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null } ?: return@withContext null
        val pages = doc.select(".container-reader-chapter > .iv-card:not([data-url$=manganow.jpg])").mapNotNull { page ->
            sequenceOf("data-url", "data-src", "data-original", "src").map(String::trim).firstNotNullOfOrNull { key -> page.attr(key).takeIf { it.isNotBlank() && !it.startsWith("data:", ignoreCase = true) } }
                ?.let { runCatching { URI(source.url).resolve(it).toString() }.getOrNull() }?.takeIf(::validImage)
        }.normalizedReadingPageUrls()
        pages.takeIf { it.isNotEmpty() }?.let { ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages") }
    }
    private fun validChapter(value: String) = runCatching { URI(value).let { isSafeReadingHttpUrl(value) && it.host.equals("manganow.to", true) && it.path.isNotBlank() } }.getOrDefault(false)
    private fun validImage(value: String) = isSafeReadingImageUrl(value)
    private fun fetch(value: String): Document? = fetchEnglishReaderDocument(value)
}

private fun fetchEnglishReaderDocument(value: String): Document? =
    fetchReadingHtmlDocument(value, mapOf("Accept" to "text/html,application/xhtml+xml"))
