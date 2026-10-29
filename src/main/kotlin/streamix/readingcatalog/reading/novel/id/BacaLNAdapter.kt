package streamix.readingcatalog

import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document

/**
 * Initial site-specific chapter-text adapter for BacaLN.
 * The selectors are intentionally isolated here so source changes do not affect other sites.
 * Access-control challenges and authenticated/paywalled chapters are not bypassed.
 */
class BacaLNReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "bacaln"

    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): Boolean =
        entry.kind == ReadingKind.NOVEL && chapter.sources.any {
            it.enabled && it.providerId.equals(id, true) && validChapterUrl(it.url)
        }

    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? =
        withContext(Dispatchers.IO) {
            if (entry.kind != ReadingKind.NOVEL) return@withContext null
            val source = chapter.sources.firstOrNull {
                it.enabled && it.providerId.equals(id, true) && validChapterUrl(it.url)
            } ?: return@withContext null
            val document = try {
                documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetchNovelHtml(source.url) else null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: return@withContext null

            val text = extractFirstReadingNovelText(document, ".reading-content, .entry-content, .chapter-content, article")
            text.takeIf(String::isNotBlank)?.let {
                ReadingContent(entry.kind, "reading-provider:$id", source.url, text = it, format = "text")
            }
        }

    private fun validChapterUrl(value: String): Boolean = try {
        novelHost(value, setOf("bacaln.com")) && URI(value).path.isNotBlank()
    } catch (_: Exception) {
        false
    }
}
