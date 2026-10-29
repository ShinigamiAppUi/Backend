package streamix.readingcatalog

import java.net.URI
import org.jsoup.nodes.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Toonily chapter pages use reading-content/page-break images. */
class ToonilyReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "toonily"
    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) =
        entry.kind == ReadingKind.MANHWA && chapter.sources.any { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) }
    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) } ?: return@withContext null
        val doc = runCatching { documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetch(source.url) else null }.getOrNull() ?: return@withContext null
        val pages = doc.select(".reading-content img, .page-break img").mapNotNull { img ->
            sequenceOf("data-src", "data-lazy-src", "data-original", "src").map(String::trim).firstNotNullOfOrNull { key -> img.attr(key).takeIf { it.isNotBlank() && !it.startsWith("data:") } }
                ?.let { runCatching { URI(source.url).resolve(it).toString() }.getOrNull() }?.takeIf(::validImage)
        }.distinct()
        pages.takeIf { it.isNotEmpty() }?.let { ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages") }
    }
    private fun validChapter(value: String) = runCatching { URI(value).let { isSafeReadingHttpUrl(value) && it.host.equals("toonily.com", true) && it.path.contains("/chapter", true) } }.getOrDefault(false)
    private fun validImage(value: String) = isSafeReadingImageUrl(value)
    private fun fetch(value: String): Document? = fetchManhwaDocument(value)
}

/** Weeb Central reader exposes a chapter-body image list. */
class WeebCentralReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "weebcentral"
    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) =
        entry.kind != ReadingKind.NOVEL && chapter.sources.any { it.enabled && (it.providerId.equals(id, true) || it.providerId.equals("weebcentral-manhwa", true)) && validChapter(it.url) }
    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        val source = chapter.sources.firstOrNull { it.enabled && (it.providerId.equals(id, true) || it.providerId.equals("weebcentral-manhwa", true)) && validChapter(it.url) } ?: return@withContext null
        val doc = runCatching { documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetch(source.url) else null }.getOrNull() ?: return@withContext null
        val pages = doc.select(".chapter-body img, .reader-images img").mapNotNull { img ->
            sequenceOf("data-src", "data-lazy-src", "data-original", "src").map(String::trim).firstNotNullOfOrNull { key -> img.attr(key).takeIf { it.isNotBlank() && !it.startsWith("data:") } }
                ?.let { runCatching { URI(source.url).resolve(it).toString() }.getOrNull() }?.takeIf(::validImage)
        }.distinct()
        pages.takeIf { it.isNotEmpty() }?.let { ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages") }
    }
    private fun validChapter(value: String) = runCatching { URI(value).let { isSafeReadingHttpUrl(value) && it.host.equals("weebcentral.com", true) && it.path.contains("/chapters/", true) } }.getOrDefault(false)
    private fun validImage(value: String) = isSafeReadingImageUrl(value)
    private fun fetch(value: String): Document? = fetchManhwaDocument(value)
}

/** Flame Comics uses chapter-content/reading-content image containers. */
class FlameComicsReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "flame-comics"
    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) =
        entry.kind == ReadingKind.MANHWA && chapter.sources.any { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) }
    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) } ?: return@withContext null
        val doc = runCatching { documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetch(source.url) else null }.getOrNull() ?: return@withContext null
        val pages = doc.select(".chapter-content img, .reading-content img").mapNotNull { img ->
            sequenceOf("data-src", "data-lazy-src", "data-original", "src").map(String::trim).firstNotNullOfOrNull { key -> img.attr(key).takeIf { it.isNotBlank() && !it.startsWith("data:") } }
                ?.let { runCatching { URI(source.url).resolve(it).toString() }.getOrNull() }?.takeIf(::validImage)
        }.distinct()
        pages.takeIf { it.isNotEmpty() }?.let { ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages") }
    }
    private fun validChapter(value: String) = runCatching { URI(value).let { isSafeReadingHttpUrl(value) && it.host.equals("flamecomics.com", true) && it.path.contains("/chapter", true) } }.getOrDefault(false)
    private fun validImage(value: String) = isSafeReadingImageUrl(value)
    private fun fetch(value: String): Document? = fetchManhwaDocument(value)
}

/** ManhwaBuddy chapter pages expose the chapter content as reader images. */
class ManhwaBuddyReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "manhwabuddy"
    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) =
        entry.kind == ReadingKind.MANHWA && chapter.sources.any { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) }
    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) } ?: return@withContext null
        val doc = runCatching { documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetch(source.url) else null }.getOrNull() ?: return@withContext null
        val pages = doc.select(".chapter-content img, .reading-content img").mapNotNull { img ->
            sequenceOf("data-src", "data-lazy-src", "data-original", "src").map(String::trim).firstNotNullOfOrNull { key -> img.attr(key).takeIf { it.isNotBlank() && !it.startsWith("data:") } }
                ?.let { runCatching { URI(source.url).resolve(it).toString() }.getOrNull() }?.takeIf(::validImage)
        }.distinct()
        pages.takeIf { it.isNotEmpty() }?.let { ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages") }
    }
    private fun validChapter(value: String) = runCatching { URI(value).let { isSafeReadingHttpUrl(value) && it.host.equals("manhwabuddy.com", true) && it.path.contains("/chapter", true) } }.getOrDefault(false)
    private fun validImage(value: String) = isSafeReadingImageUrl(value)
    private fun fetch(value: String): Document? = fetchManhwaDocument(value)
}

/** DragonTea's reader parses its reading-content and image-page nodes. */
class DragonTeaReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "dragontea"
    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) =
        entry.kind == ReadingKind.MANHWA && chapter.sources.any { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) }
    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) } ?: return@withContext null
        val doc = runCatching { documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetch(source.url) else null }.getOrNull() ?: return@withContext null
        val pages = doc.select(".reading-content img, .page-break img").mapNotNull { img ->
            sequenceOf("data-src", "data-lazy-src", "data-original", "src").map(String::trim).firstNotNullOfOrNull { key -> img.attr(key).takeIf { it.isNotBlank() && !it.startsWith("data:") } }
                ?.let { runCatching { URI(source.url).resolve(it).toString() }.getOrNull() }?.takeIf(::validImage)
        }.distinct()
        pages.takeIf { it.isNotEmpty() }?.let { ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages") }
    }
    private fun validChapter(value: String) = runCatching { URI(value).let { isSafeReadingHttpUrl(value) && (it.host.equals("dragontea.ink", true) || it.host.equals("dragontea.com", true)) && it.path.contains("/chapter", true) } }.getOrDefault(false)
    private fun validImage(value: String) = isSafeReadingImageUrl(value)
    private fun fetch(value: String): Document? = fetchManhwaDocument(value)
}

private fun fetchManhwaDocument(value: String): Document? =
    fetchReadingHtmlDocument(value, mapOf("Accept" to "text/html,application/xhtml+xml"))
