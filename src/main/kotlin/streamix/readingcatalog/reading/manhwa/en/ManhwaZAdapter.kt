package streamix.readingcatalog

import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document

/**
 * First-pass reader adapter for ManhwaZ's manually catalogued chapter URLs.
 * Host and reader selectors require fixtures/live verification before this adapter is considered tested.
 */
class ManhwaZReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "manhwaz"

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

            val pages = document.select(
                ".reading-content img, #readerarea img, .chapter-content img, .chapter-images img, .page-break img"
            ).mapNotNull { image ->
                sequenceOf("data-lazy-src", "data-src", "data-original", "data-url", "src")
                    .map { image.attr(it).trim() }
                    .firstNotNullOfOrNull { raw ->
                        if (raw.isBlank() || raw.startsWith("data:", ignoreCase = true)) null
                        else runCatching { URI(source.url).resolve(raw).toString() }.getOrNull()
                            ?.takeIf(::validImageUrl)
                    }
            }.normalizedReadingPageUrls()

            pages.takeIf { it.isNotEmpty() }?.let {
                ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages")
            }
        }

    private fun validChapterUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        isSafeReadingHttpUrl(value) &&
            uri.host.equals("manhwaz.com", ignoreCase = true) &&
            !uri.path.isNullOrBlank() &&
            uri.userInfo == null &&
            uri.port in setOf(-1, 443)
    }.getOrDefault(false)

    private fun validImageUrl(value: String): Boolean = isSafeReadingImageUrl(value)

    private fun fetch(value: String): Document? =
        fetchReadingHtmlDocument(value, mapOf("Accept" to "text/html,application/xhtml+xml"))
}
