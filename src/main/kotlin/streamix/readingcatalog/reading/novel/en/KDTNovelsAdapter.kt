package streamix.readingcatalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document

/** Dedicated KDTNovels chapter-text adapter. Metadata remains outside Reading Catalog. */
class KDTNovelsReadingRuntimeProvider(
    private val documentFetcher: ((String) -> Document?)? = null,
) : ReadingRuntimeProvider {
    override val id = "kdtnovels"

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
            val text = extractFirstReadingNovelText(document, ".chapter-content, #chapter-content, .entry-content")
            text.takeIf(String::isNotBlank)?.let {
                ReadingContent(entry.kind, "reading-provider:$id", source.url, text = it, format = "text")
            }
        }

    private fun validChapterUrl(value: String): Boolean = isSafeReadingChapterUrl(
        value,
        setOf("kdtnovels.net", "www.kdtnovels.net"),
    ) { path ->
        val segments = path.trim('/').split('/').filter(String::isNotBlank)
        segments.size >= 2 && segments.none { it == "." || it == ".." }
    }
}
