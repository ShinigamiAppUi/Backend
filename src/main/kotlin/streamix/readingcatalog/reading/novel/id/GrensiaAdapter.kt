package streamix.readingcatalog

import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document

/**
 * Grensia Indonesian novel chapter adapter.
 *
 * Grensia publishes chapter posts on Blogger. The reader content is contained in
 * the post body; navigation, embedded widgets, and scripts are excluded before
 * text extraction. This adapter only reads public pages and does not bypass
 * access controls.
 */
class GrensiaReadingRuntimeProvider(
    private val documentFetcher: ((String) -> Document?)? = null
) : ReadingRuntimeProvider {
    override val id = "grensia"

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

        document.select(
            ".post-body script, .post-body style, .post-body ins, " +
                ".post-body iframe, .post-body a[name=more]"
        ).remove()

        val text = extractFirstReadingNovelText(
            document,
            ".post-body.entry-content, .post-body, article .post-body"
        )
        text.takeIf(String::isNotBlank)?.let {
            ReadingContent(entry.kind, "reading-provider:$id", source.url, text = it, format = "text")
        }
    }

    private fun validChapterUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        novelHost(value, setOf("grensia.blogspot.com")) &&
            uri.userInfo == null && uri.port in setOf(-1, 443) &&
            uri.path.matches(Regex("/\\d{4}/\\d{2}/[^/]+\\.html"))
    }.getOrDefault(false)
}
