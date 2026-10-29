package streamix.readingcatalog

import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document

/**
 * Site-specific chapter text adapter for Chikari.
 * The Reading Catalog supplies chapter URLs; this adapter does not perform metadata discovery
 * or bypass account, paywall, anti-bot, or other upstream access controls.
 *
 * Initial selector pass only: verify against source fixtures before marking the provider tested.
 */
class ChikariReadingRuntimeProvider(
    private val documentFetcher: ((String) -> Document?)? = null,
) : ReadingRuntimeProvider {
    override val id: String = "chikari"

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

    private fun validChapterUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", true) && uri.host.equals("chikari.moe", true) &&
            uri.userInfo == null && uri.port in setOf(-1, 443) &&
            !uri.path.isNullOrBlank() && uri.path.contains("/novels/", true)
    }.getOrDefault(false)
}
