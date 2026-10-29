package streamix.readingcatalog

import java.net.URI
import org.jsoup.nodes.Document
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NovelCoolReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "novelcool"
    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) =
        entry.kind == ReadingKind.NOVEL && chapter.sources.any { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) }
    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        if (entry.kind != ReadingKind.NOVEL) return@withContext null
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) } ?: return@withContext null
        val doc = try {
            documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetch(source.url) else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return@withContext null
        val text = extractFirstReadingNovelText(doc, "#content, .chapter-content, .txt")
        text.takeIf(String::isNotBlank)?.let { ReadingContent(entry.kind, "reading-provider:$id", source.url, text = it, format = "text") }
    }
    private fun validChapter(value: String): Boolean = try {
        URI(value).let { uri ->
            uri.scheme.equals("https", true) &&
                (uri.host.equals("www.novelcool.com", true) || uri.host.equals("novelcool.com", true)) &&
                uri.userInfo == null &&
                uri.port in setOf(-1, 443) &&
                uri.path.isNotBlank()
        }
    } catch (_: Exception) {
        false
    }
    private fun fetch(value: String): Document? = fetchNovelDocument(value)
}
