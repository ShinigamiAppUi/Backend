package streamix.readingcatalog

import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document

/**
 * New Manhwa chapter-page adapter. The catalog owns chapter URLs; this provider only
 * extracts reader images and does not perform title search or bypass access controls.
 */
class NewManhwaReadingRuntimeProvider(
    private val documentFetcher: ((String) -> Document?)? = null,
) : ReadingRuntimeProvider {
    override val id = "new-manhwa"

    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): Boolean =
        entry.kind == ReadingKind.MANHWA && chapter.sources.any {
            it.enabled && it.providerId.equals(id, true) && validChapterUrl(it.url)
        }

    override suspend fun chapter(
        entry: ReadingCatalogEntry,
        chapter: ReadingChapterEntry,
    ): ReadingContent? = withContext(Dispatchers.IO) {
        if (entry.kind != ReadingKind.MANHWA) return@withContext null
        val source = chapter.sources.firstOrNull {
            it.enabled && it.providerId.equals(id, true) && validChapterUrl(it.url)
        } ?: return@withContext null

        val document = try {
            documentFetcher?.invoke(source.url)
                ?: if (documentFetcher == null) fetch(source.url) else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return@withContext null

        val pages = document.select(
            "#readerarea img, .reading-content img, .chapter-content img, .reader-container img, .chapter-images img"
        ).mapNotNull { image ->
            sequenceOf("data-lazy-src", "data-src", "data-original", "data-url", "src")
                .map { image.attr(it).trim() }
                .firstNotNullOfOrNull { raw ->
                    raw.takeIf { it.isNotBlank() && !it.startsWith("data:", ignoreCase = true) }
                        ?.let { runCatching { URI(source.url).resolve(it).toString() }.getOrNull() }
                        ?.takeIf(::validImageUrl)
                }
        }.normalizedReadingPageUrls()

        pages.takeIf { it.isNotEmpty() }?.let {
            ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages")
        }
    }

    private fun validChapterUrl(value: String): Boolean =
        isSafeReadingChapterUrl(value, setOf("newmanhwa.com", "www.newmanhwa.com")) { path ->
            path.isNotBlank() && path != "/"
        }

    private fun validImageUrl(value: String): Boolean = isSafeReadingImageUrl(value)

    private fun fetch(value: String): Document? = fetchReadingHtmlDocument(
        value,
        mapOf(
            "Accept" to "text/html,application/xhtml+xml",
            "Accept-Language" to "en-US,en;q=0.9",
        ),
    )
}
