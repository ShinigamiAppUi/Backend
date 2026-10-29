package streamix.readingcatalog

import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document

/**
 * Inventory-first chapter adapter for ReadHive.
 * The Reading Catalog supplies chapter URLs; this adapter only extracts prose from that chapter.
 * Selectors and current upstream availability remain pending fixture/live verification.
 */
class ReadHiveReadingRuntimeProvider(
    private val documentFetcher: ((String) -> Document?)? = null,
) : ReadingRuntimeProvider {
    override val id: String = "readhive"

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
            val text = extractFirstReadingNovelText(document, ".chapter-content, #chapter-content, .reading-content, .entry-content")
            text.takeIf(String::isNotBlank)?.let {
                ReadingContent(entry.kind, "reading-provider:$id", source.url, text = it, format = "text")
            }
        }

    private fun validChapterUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", true) &&
            uri.host?.lowercase()?.let { it in setOf("readhive.org", "www.readhive.org") } == true &&
            uri.userInfo == null && uri.port in setOf(-1, 443) &&
            (uri.path.isNotBlank() && uri.path != "/")
    }.getOrDefault(false)
}
