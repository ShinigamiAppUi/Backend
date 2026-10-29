package streamix.readingcatalog

import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document

/**
 * Hiperdex site-specific chapter image adapter.
 * This adapter only reads an explicitly catalogued chapter URL and does not bypass
 * upstream authentication, bot checks, or other access controls.
 */
class HiperdexReadingRuntimeProvider(
    private val documentFetcher: ((String) -> Document?)? = null,
) : ReadingRuntimeProvider {
    override val id: String = "hiperdex"

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
                documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetchNovelOrComicHtml(source.url) else null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: return@withContext null

            val pages = document.select(".reading-content img, .page-break img, .reader-area img, .chapter-content img")
                .mapNotNull { image ->
                    sequenceOf("data-lazy-src", "data-src", "data-original", "src")
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

    private fun validChapterUrl(value: String): Boolean = isSafeReadingChapterUrl(
        value,
        setOf("hiperdex.tv", "www.hiperdex.tv"),
    ) { path ->
        val lower = path.lowercase()
        lower.contains("/chapter") || lower.contains("/read/") || lower.contains("/series/")
    }

    private fun validImageUrl(value: String): Boolean = isSafeReadingImageUrl(value)

    private fun fetchNovelOrComicHtml(value: String): Document? = fetchReadingHtmlDocument(
        value,
        mapOf(
            "User-Agent" to "ShinigamiReadingRuntime/1.0",
            "Accept" to "text/html,application/xhtml+xml",
            "Accept-Language" to "en-US,en;q=0.9",
        ),
    )
}
