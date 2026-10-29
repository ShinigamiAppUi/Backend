package streamix.readingcatalog

import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document

/**
 * Dedicated chapter-text adapter for Galaxy Translations on its documented darkstartranslations.com domain.
 * Uses manually catalogued chapter URLs only. Site-specific selector behavior remains pending fixtures
 * and is intentionally not marked verified until that implementation evidence exists.
 */
class GalaxyTranslationsReadingRuntimeProvider(
    private val documentFetcher: ((String) -> Document?)? = null,
) : ReadingRuntimeProvider {
    override val id: String = "galaxytranslations"

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

            val text = extractFirstReadingNovelText(document, ".entry-content, .chapter-content, .reading-content, #chapter-content")
            text.takeIf(String::isNotBlank)?.let {
                ReadingContent(entry.kind, "reading-provider:$id", source.url, text = it, format = "text")
            }
        }

    private fun validChapterUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", true) &&
            uri.host.equals("darkstartranslations.com", true) &&
            uri.userInfo == null &&
            uri.port in setOf(-1, 443) &&
            (uri.path.isNotBlank() && uri.path.split('/').filter { it.isNotBlank() }.size >= 2)
    }.getOrDefault(false)
}
