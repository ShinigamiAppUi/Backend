package streamix.readingcatalog

import java.net.URI
import org.jsoup.nodes.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MangaKatana reader: chapter image nodes are exposed by its #imgs reader container.
 * The host and chapter URL shape are checked before any request is made.
 */
class MangaKatanaReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "mangakatana"
    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) =
        entry.kind != ReadingKind.NOVEL && chapter.sources.any { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) }
    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) } ?: return@withContext null
        val doc = runCatching { documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetch(source.url) else null }.getOrNull() ?: return@withContext null
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

/** Mangakakalot's chapter reader uses its dedicated container-chapter-reader element. */
class MangakakalotReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "mangakakalot"
    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) =
        entry.kind != ReadingKind.NOVEL && chapter.sources.any { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) }
    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) } ?: return@withContext null
        val doc = runCatching { documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetch(source.url) else null }.getOrNull() ?: return@withContext null
        val pages = doc.select(".container-chapter-reader img").mapNotNull { img ->
            sequenceOf("data-lazy-src", "data-src", "data-original", "src").map(String::trim).firstNotNullOfOrNull { key -> img.attr(key).takeIf { it.isNotBlank() && !it.startsWith("data:") } }
                ?.let { runCatching { URI(source.url).resolve(it).toString() }.getOrNull() }?.takeIf(::validImage)
        }.distinct()
        pages.takeIf { it.isNotEmpty() }?.let { ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages") }
    }
    private fun validChapter(value: String) = isSafeReadingChapterUrl(value, setOf("mangakakalot.com", "mangakakalot.tv")) { it.contains("chapter", true) }
    private fun validImage(value: String) = isSafeReadingImageUrl(value)
    private fun fetch(value: String): Document? = fetchReadingDocument(value)
}

/** Manganato chapter images are rendered inside .container-chapter-reader. */
class ManganatoReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "manganato"
    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) =
        entry.kind != ReadingKind.NOVEL && chapter.sources.any { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) }
    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) } ?: return@withContext null
        val doc = runCatching { documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetch(source.url) else null }.getOrNull() ?: return@withContext null
        val pages = doc.select(".container-chapter-reader img, .page-chapter img").mapNotNull { img ->
            sequenceOf("data-lazy-src", "data-src", "data-original", "src").map(String::trim).firstNotNullOfOrNull { key -> img.attr(key).takeIf { it.isNotBlank() && !it.startsWith("data:") } }
                ?.let { runCatching { URI(source.url).resolve(it).toString() }.getOrNull() }?.takeIf(::validImage)
        }.distinct()
        pages.takeIf { it.isNotEmpty() }?.let { ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages") }
    }
    private fun validChapter(value: String) = isSafeReadingChapterUrl(value, setOf("manganato.com", "www.manganato.com", "manganato.gg", "www.manganato.gg", "chapmanganato.to")) { it.contains("chapter", true) }
    private fun validImage(value: String) = isSafeReadingImageUrl(value)
    private fun fetch(value: String): Document? = fetchReadingDocument(value)
}

/** MangaRead uses the reading-content / chapter-reader image containers. */
class MangaReadReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "mangaread"
    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) =
        entry.kind != ReadingKind.NOVEL && chapter.sources.any { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) }
    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) } ?: return@withContext null
        val doc = runCatching { documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetch(source.url) else null }.getOrNull() ?: return@withContext null
        val pages = doc.select(".reading-content img, #chapter-reader img").mapNotNull { img ->
            sequenceOf("data-lazy-src", "data-src", "data-original", "src").map(String::trim).firstNotNullOfOrNull { key -> img.attr(key).takeIf { it.isNotBlank() && !it.startsWith("data:") } }
                ?.let { runCatching { URI(source.url).resolve(it).toString() }.getOrNull() }?.takeIf(::validImage)
        }.distinct()
        pages.takeIf { it.isNotEmpty() }?.let { ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages") }
    }
    private fun validChapter(value: String) = isSafeReadingChapterUrl(value, setOf("mangaread.org", "mangaread.to")) { it.contains("/chapter", true) }
    private fun validImage(value: String) = isSafeReadingImageUrl(value)
    private fun fetch(value: String): Document? = fetchReadingDocument(value)
}

/** MangaBat chapter image reader, using its .container-chapter-reader markup. */
class MangaBatReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "mangabat"
    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) =
        entry.kind != ReadingKind.NOVEL && chapter.sources.any { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) }
    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) } ?: return@withContext null
        val doc = runCatching { documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetch(source.url) else null }.getOrNull() ?: return@withContext null
        val pages = doc.select(".container-chapter-reader img, .chapter-img img").mapNotNull { img ->
            sequenceOf("data-lazy-src", "data-src", "data-original", "src").map(String::trim).firstNotNullOfOrNull { key -> img.attr(key).takeIf { it.isNotBlank() && !it.startsWith("data:") } }
                ?.let { runCatching { URI(source.url).resolve(it).toString() }.getOrNull() }?.takeIf(::validImage)
        }.distinct()
        pages.takeIf { it.isNotEmpty() }?.let { ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages") }
    }
    private fun validChapter(value: String) = isSafeReadingChapterUrl(value, setOf("mangabat.com", "h.mangabat.com")) { it.contains("/chapter-", true) }
    private fun validImage(value: String) = isSafeReadingImageUrl(value)
    private fun fetch(value: String): Document? = fetchReadingDocument(value)
}

/** MangaHere reader extracts page images from the #viewer and reader-main containers. */
class MangaHereReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "mangahere"
    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) =
        entry.kind != ReadingKind.NOVEL && chapter.sources.any { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) }
    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) } ?: return@withContext null
        val doc = runCatching { documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetch(source.url) else null }.getOrNull() ?: return@withContext null
        val pages = doc.select("#viewer img, .reader-main img").mapNotNull { img ->
            sequenceOf("data-lazy-src", "data-src", "data-original", "src").map(String::trim).firstNotNullOfOrNull { key -> img.attr(key).takeIf { it.isNotBlank() && !it.startsWith("data:") } }
                ?.let { runCatching { URI(source.url).resolve(it).toString() }.getOrNull() }?.takeIf(::validImage)
        }.distinct()
        pages.takeIf { it.isNotEmpty() }?.let { ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages") }
    }
    private fun validChapter(value: String) = isSafeReadingChapterUrl(value, setOf("mangahere.cc", "mangahere.co")) { it.contains("/manga/", true) }
    private fun validImage(value: String) = isSafeReadingImageUrl(value)
    private fun fetch(value: String): Document? = fetchReadingDocument(value)
}

private fun fetchReadingDocument(value: String): Document? =
    fetchReadingHtmlDocument(value, mapOf("Accept" to "text/html,application/xhtml+xml"))
