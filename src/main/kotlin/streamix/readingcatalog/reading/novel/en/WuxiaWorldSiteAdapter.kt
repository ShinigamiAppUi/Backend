package streamix.readingcatalog

import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document

/**
 * WuxiaWorld.Site prose novel adapter. This is distinct from wuxiaworld.com:
 * the site uses /novel/{slug}/chapter-{number} chapter routes.
 */
class WuxiaWorldSiteReadingRuntimeProvider(
    private val documentFetcher: ((String) -> Document?)? = null
) : ReadingRuntimeProvider {
    override val id = "wuxiaworld.site"

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
            ".reading-content, .text-left, .entry-content, #chapter-content, .chapter-content, article"
        )
        text.takeIf(String::isNotBlank)?.let {
            ReadingContent(entry.kind, "reading-provider:$id", source.url, text = it, format = "text")
        }
    }

    private fun validChapterUrl(value: String): Boolean = try {
        val uri = URI(value)
        novelHost(value, setOf("wuxiaworld.site")) &&
            uri.userInfo == null && uri.port in setOf(-1, 443) &&
            uri.path.matches(Regex("/novel/[^/]+/chapter-[^/]+/?"))
    } catch (_: Exception) {
        false
    }
}
