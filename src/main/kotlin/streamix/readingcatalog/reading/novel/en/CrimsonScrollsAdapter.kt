package streamix.readingcatalog

import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document

/** Crimson Scrolls uses its cs-reader/chapter-display body and reader-specific chrome is removed. */
class CrimsonScrollsReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "crimson-scrolls"

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
                documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetchNovelDocument(source.url) else null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: return@withContext null

            
            
            document.select("header, nav, form, section, aside, figure, script, style, svg, ins, .cs-chapter-ad, .cs-home-ad, .cs-kofi-widget, .cs-copy-watermark, .cs-reader-bar, .cs-reader-tools, .cs-reader-nav-button, .cs-reader-toc").remove()
            val text = extractFirstReadingNovelText(document, "article.cs-reader, #chapter-display")
            text.takeIf(String::isNotBlank)?.let {
                ReadingContent(entry.kind, "reading-provider:$id", source.url, text = it, format = "text")
            }
        }

    private fun validChapterUrl(value: String): Boolean = isSafeReadingChapterUrl(
        value,
        setOf("crimsonscrolls.net", "www.crimsonscrolls.net"),
    ) { path -> path.contains("/novel/", true) && path.contains("/chapter", true) }
}
