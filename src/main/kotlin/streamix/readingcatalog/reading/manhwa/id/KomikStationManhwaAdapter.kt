package streamix.readingcatalog

import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document

/**
 * Komik Station Manhwa chapter reader.
 * Chapter URLs are supplied by the manual Reading Catalog; this adapter only extracts pages.
 */
class KomikStationManhwaReadingRuntimeProvider(
    private val documentFetcher: ((String) -> Document?)? = null,
) : ReadingRuntimeProvider {
    override val id = "komik-station-manhwa"

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
                ?: if (documentFetcher == null) fetchIndonesianReaderDocument(source.url) else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return@withContext null

        val pages = document.select("#readerarea img, .reader-area img, .reading-content img").mapNotNull { image ->
            sequenceOf("data-lazy-src", "data-src", "data-original", "data-cfsrc", "src")
                .map { image.attr(it).trim() }
                .firstNotNullOfOrNull { raw ->
                    raw.takeIf { it.isNotBlank() && !it.startsWith("data:", ignoreCase = true) }
                        ?.let { runCatching { URI(source.url).resolve(it).toString() }.getOrNull() }
                        ?.takeIf(::isSafeReadingImageUrl)
                }
        }.normalizedReadingPageUrls()

        pages.takeIf { it.isNotEmpty() }?.let {
            ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages")
        }
    }

    private fun validChapterUrl(value: String): Boolean =
        isSafeReadingChapterUrl(value, setOf("komikstation.org")) { path ->
            path.isNotBlank() && path.contains("chapter", ignoreCase = true)
        }
}

private fun fetchIndonesianReaderDocument(value: String): Document? =
    fetchReadingHtmlDocument(
        value,
        mapOf(
            "Accept" to "text/html,application/xhtml+xml",
            "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        ),
    )
