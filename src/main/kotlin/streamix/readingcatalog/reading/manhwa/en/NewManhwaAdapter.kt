package streamix.readingcatalog

import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document

/**
 * New Manhwa's source is hosted at saymanhwa.com and its reader markup uses div.reader-pages img.
 * This pass implements the source-specific chapter image reader; catalog metadata remains separate.
 */
class NewManhwaReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "new-manhwa"

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
                if (documentFetcher == null) fetchReadingHtmlDocument(source.url, mapOf(
                    "User-Agent" to "ShinigamiReadingRuntime/1.0",
                    "Accept" to "text/html,application/xhtml+xml",
                    "Accept-Language" to "en-US,en;q=0.9",
                )) else documentFetcher.invoke(source.url)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: return@withContext null

            val pages = document.select("div.reader-pages img").mapNotNull { image ->
                sequenceOf("data-lazy-src", "data-src", "data-original", "data-url", "src")
                    .map { image.attr(it).trim() }
                    .firstNotNullOfOrNull { raw ->
                        if (raw.isBlank() || raw.startsWith("data:", ignoreCase = true)) null
                        else runCatching { URI(source.url).resolve(raw).toString() }.getOrNull()
                            ?.takeIf(::isSafeReadingImageUrl)
                    }
            }.normalizedReadingPageUrls()

            pages.takeIf { it.isNotEmpty() }?.let {
                ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages")
            }
        }

    private fun validChapterUrl(value: String): Boolean = isSafeReadingChapterUrl(
        value, setOf("saymanhwa.com", "www.saymanhwa.com")
    ) { path -> path.contains("/chapter", true) || path.contains("/chapters/", true) }

    // Site-specific chapter/image parser; no metadata is copied into Reading Catalog.
}
