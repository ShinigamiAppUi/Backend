package streamix.readingcatalog

import java.net.URI

import org.jsoup.nodes.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** MangaFox uses its mobile roll_manga endpoint and #viewer data-original URLs. */
class MangaFoxReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "mangafox"
    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) =
        entry.kind == ReadingKind.MANGA && chapter.sources.any { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) }
    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) } ?: return@withContext null
        val mobileUrl = source.url.replace("://", "://m.")
        val readerUrl = mobileUrl.replace("/manga/", "/roll_manga/")
        val doc = runCatching { documentFetcher?.invoke(readerUrl) ?: if (documentFetcher == null) fetch(readerUrl) else null }.getOrNull() ?: return@withContext null
        val pages = doc.select("#viewer img").mapNotNull { img ->
            sequenceOf("data-original", "data-lazy-src", "data-src", "src").map(String::trim).firstNotNullOfOrNull { key -> img.attr(key).takeIf { it.isNotBlank() && !it.startsWith("data:") } }
                ?.let { runCatching { URI(readerUrl).resolve(it).toString() }.getOrNull() }?.takeIf(::validImage)
        }.distinct()
        pages.takeIf { it.isNotEmpty() }?.let { ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages") }
    }
    private fun validChapter(value: String) = runCatching { URI(value).let { isSafeReadingHttpUrl(value) && it.host.equals("fanfox.net", true) && it.path.contains("/manga/", true) } }.getOrDefault(false)
    private fun validImage(value: String) = isSafeReadingImageUrl(value)
    private fun fetch(value: String): Document? = fetchMangaFoxDocument(value)
}

/** MangaBolt uses its own js-pages-container and lazy js-page markup. */
class MangaBoltReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "mangabolt"
    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) =
        entry.kind == ReadingKind.MANGA && chapter.sources.any { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) }
    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) } ?: return@withContext null
        val doc = runCatching { documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetch(source.url) else null }.getOrNull() ?: return@withContext null
        val pages = doc.select(".js-pages-container img.js-page").filterNot { img -> img.parents().any { it.tagName().equals("noscript", true) } }.mapNotNull { img ->
            sequenceOf("data-lazy-src", "data-src", "data-original", "src").map(String::trim).firstNotNullOfOrNull { key -> img.attr(key).takeIf { it.isNotBlank() && !it.startsWith("data:") && !it.startsWith("data:image") } }
                ?.let { runCatching { URI(source.url).resolve(it).toString() }.getOrNull() }?.takeIf(::validImage)
        }.distinct()
        pages.takeIf { it.isNotEmpty() }?.let { ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages") }
    }
    private fun validChapter(value: String) = runCatching { URI(value).let { isSafeReadingHttpUrl(value) && it.host.equals("mangabolt.com", true) && it.path.isNotBlank() } }.getOrDefault(false)
    private fun validImage(value: String) = isSafeReadingImageUrl(value)
    private fun fetch(value: String): Document? = fetchMangaFoxDocument(value)
}

private fun fetchMangaFoxDocument(value: String): Document? =
    fetchReadingHtmlDocument(value, mapOf("Accept" to "text/html,application/xhtml+xml"))
