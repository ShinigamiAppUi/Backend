package streamix.readingcatalog

import java.net.URI
import org.jsoup.nodes.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
