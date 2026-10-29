package streamix.readingcatalog

import java.net.URI
import org.jsoup.nodes.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** WebNovel chapter adapter: extracts the novel's chapter_content text, not a generic page body. */
class WebNovelReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "webnovel"
    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) =
        entry.kind == ReadingKind.NOVEL && chapter.sources.any { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) }
    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        if (entry.kind != ReadingKind.NOVEL) return@withContext null
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) } ?: return@withContext null
        val doc = runCatching { documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetch(source.url) else null }.getOrNull() ?: return@withContext null
        val text = extractFirstReadingNovelText(doc, ".chapter_content, .cha-content")
        text.takeIf(String::isNotBlank)?.let { ReadingContent(entry.kind, "reading-provider:$id", source.url, text = it, format = "text") }
    }
    private fun validChapter(value: String) = runCatching { URI(value).let { it.scheme == "https" && it.host.equals("www.webnovel.com", true) && it.userInfo == null && it.port in setOf(-1, 443) && it.path.contains("/chapter-", true) } }.getOrDefault(false)
    private fun fetch(value: String): Document? = fetchNovelDocument(value)
}

/** WuxiaWorld chapter adapter reads the site's chapter-content article. */
class WuxiaWorldReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "wuxiaworld"
    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) =
        entry.kind == ReadingKind.NOVEL && chapter.sources.any { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) }
    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        if (entry.kind != ReadingKind.NOVEL) return@withContext null
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) } ?: return@withContext null
        val doc = runCatching { documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetch(source.url) else null }.getOrNull() ?: return@withContext null
        val text = extractFirstReadingNovelText(doc, "#chapter-content, .chapter-content")
        text.takeIf(String::isNotBlank)?.let { ReadingContent(entry.kind, "reading-provider:$id", source.url, text = it, format = "text") }
    }
    private fun validChapter(value: String) = runCatching { URI(value).let { it.scheme == "https" && it.host.equals("www.wuxiaworld.com", true) && it.userInfo == null && it.port in setOf(-1, 443) && it.path.contains("/chapter", true) } }.getOrDefault(false)
    private fun fetch(value: String): Document? = fetchNovelDocument(value)
}

/** Baca Light Novel extracts the article body used by its chapter pages. */
class BacaLightNovelReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "baca-light-novel"
    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) =
        entry.kind == ReadingKind.NOVEL && chapter.sources.any { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) }
    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        if (entry.kind != ReadingKind.NOVEL) return@withContext null
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) } ?: return@withContext null
        val doc = runCatching { documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetch(source.url) else null }.getOrNull() ?: return@withContext null
        val text = extractFirstReadingNovelText(doc, ".epcontent, .entry-content, .post-content")
        text.takeIf(String::isNotBlank)?.let { ReadingContent(entry.kind, "reading-provider:$id", source.url, text = it, format = "text") }
    }
    private fun validChapter(value: String) = runCatching { URI(value).let { it.scheme == "https" && (it.host.equals("bacalightnovel.co", true) || it.host.equals("bacalightnovel.com", true)) && it.userInfo == null && it.port in setOf(-1, 443) && it.path.isNotBlank() } }.getOrDefault(false)
    private fun fetch(value: String): Document? = fetchNovelDocument(value)
}

/** NovelCool chapter adapter uses its dedicated text container. */
class NovelCoolReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "novelcool"
    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) =
        entry.kind == ReadingKind.NOVEL && chapter.sources.any { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) }
    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        if (entry.kind != ReadingKind.NOVEL) return@withContext null
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) } ?: return@withContext null
        val doc = runCatching { documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetch(source.url) else null }.getOrNull() ?: return@withContext null
        val text = extractFirstReadingNovelText(doc, "#content, .chapter-content, .txt")
        text.takeIf(String::isNotBlank)?.let { ReadingContent(entry.kind, "reading-provider:$id", source.url, text = it, format = "text") }
    }
    private fun validChapter(value: String) = runCatching { URI(value).let { it.scheme == "https" && (it.host.equals("www.novelcool.com", true) || it.host.equals("novelcool.com", true)) && it.userInfo == null && it.port in setOf(-1, 443) && it.path.isNotBlank() } }.getOrDefault(false)
    private fun fetch(value: String): Document? = fetchNovelDocument(value)
}

private fun fetchNovelDocument(value: String): Document? =
    fetchReadingHtmlDocument(
        value,
        mapOf("Accept" to "text/html,application/xhtml+xml"),
        maxBytes = MAX_NOVEL_RESPONSE_BYTES,
    )

/** Read an upstream chapter body with a hard cap to avoid unbounded response buffering. */
internal fun readBoundedNovelResponse(input: java.io.InputStream, maxBytes: Int): ByteArray? {
    require(maxBytes > 0) { "maxBytes must be positive" }
    val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) {
            // Some unusual streams may report zero for a non-empty read request.
            // Fall back to a single-byte read so the bounded loop cannot spin forever.
            val single = input.read()
            if (single < 0) break
            if (total == maxBytes) return null
            output.write(single)
            total++
            continue
        }
        if (count > maxBytes - total) return null
        output.write(buffer, 0, count)
        total += count
    }
    return output.toByteArray()
}

private const val MAX_NOVEL_RESPONSE_BYTES = 2 * 1024 * 1024
