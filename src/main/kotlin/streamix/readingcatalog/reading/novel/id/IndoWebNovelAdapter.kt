package streamix.readingcatalog

import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document

/**
 * Site-specific IndoWebNovel chapter-text adapter.
 * Cancellation is propagated; ordinary fetch failures are treated as unavailable content.
 */
class IndoWebNovelReadingRuntimeProvider(
    private val documentFetcher: ((String) -> Document?)? = null
) : ReadingRuntimeProvider {
    override val id = "indowebnovel"

    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): Boolean =
        entry.kind == ReadingKind.NOVEL && chapter.sources.any {
            it.enabled && it.providerId.equals(id, true) && validChapterUrl(it.url)
        }

    override suspend fun chapter(
        entry: ReadingCatalogEntry,
        chapter: ReadingChapterEntry
    ): ReadingContent? = withContext(Dispatchers.IO) {
        if (entry.kind != ReadingKind.NOVEL) return@withContext null
        val source = chapter.sources.firstOrNull {
            it.enabled && it.providerId.equals(id, true) && validChapterUrl(it.url)
        } ?: return@withContext null

        val document = try {
            documentFetcher?.invoke(source.url)
                ?: if (documentFetcher == null) fetchNovelHtml(source.url) else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return@withContext null

        val text = extractFirstReadingNovelText(
            document,
            ".text-left, .text-right, .entry-content, .chapter-content"
        )
        text.takeIf(String::isNotBlank)?.let {
            ReadingContent(entry.kind, "reading-provider:$id", source.url, text = it, format = "text")
        }
    }

    private fun validChapterUrl(value: String): Boolean = try {
        val uri = URI(value)
        novelHost(value, setOf("indowebnovel.com", "indowebnovel.id")) &&
            !uri.path.isNullOrBlank() && uri.userInfo == null && uri.port in setOf(-1, 443)
    } catch (_: Exception) {
        false
    }
}
