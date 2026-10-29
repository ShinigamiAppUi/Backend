package streamix.readingcatalog

import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document

/** Manhwa18.today uses the Madara AJAX chapter reader; the adapter does not bypass access controls. */
class Manhwa18TodayReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "manhwa18-today"

    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): Boolean =
        entry.kind == ReadingKind.MANHWA && chapter.sources.any {
            it.enabled && it.providerId.equals(id, true) && validChapterUrl(it.url)
        }

    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? =
        withContext(Dispatchers.IO) {
            if (entry.kind != ReadingKind.MANHWA) return@withContext null
            val source = chapter.sources.firstOrNull {
                it.enabled && it.providerId.equals(id, true) && validChapterUrl(it.url)
            } ?: return@withContext null
            val document = try {
                documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetch(source.url) else null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: return@withContext null
            val pages = document.select(".reading-content img, .page-break img").mapNotNull { image ->
                sequenceOf("data-lazy-src", "data-src", "data-original", "data-url", "src")
                    .map { image.attr(it).trim() }
                    .firstNotNullOfOrNull { raw ->
                        if (raw.isBlank() || raw.startsWith("data:", true)) null
                        else runCatching { URI(source.url).resolve(raw).toString() }.getOrNull()
                            ?.takeIf(::validImageUrl)
                    }
            }.normalizedReadingPageUrls()
            pages.takeIf { it.isNotEmpty() }?.let {
                ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages")
            }
        }

    private fun validChapterUrl(value: String): Boolean = isSafeReadingChapterUrl(
        value, setOf("manhwa18.today", "www.manhwa18.today")
    ) { path -> path.contains("/chapter", true) || path.contains("/chapters/", true) }

    private fun validImageUrl(value: String) = isSafeReadingImageUrl(value)

    private fun fetch(value: String): Document? = fetchReadingHtmlDocument(
        value,
        mapOf("User-Agent" to "ShinigamiReadingRuntime/1.0", "Accept" to "text/html,application/xhtml+xml", "Accept-Language" to "en-US,en;q=0.9"),
    )
}
