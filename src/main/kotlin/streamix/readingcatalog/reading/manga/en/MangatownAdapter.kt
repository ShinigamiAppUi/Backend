package streamix.readingcatalog

import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document

/** Mangatown uses per-page viewer URLs; this adapter resolves those URLs to ordered images. */
class MangatownReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "mangatown"

    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): Boolean =
        entry.kind == ReadingKind.MANGA && chapter.sources.any {
            it.enabled && it.providerId.equals(id, true) && validChapterUrl(it.url)
        }

    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? =
        withContext(Dispatchers.IO) {
            if (entry.kind != ReadingKind.MANGA) return@withContext null
            val source = chapter.sources.firstOrNull {
                it.enabled && it.providerId.equals(id, true) && validChapterUrl(it.url)
            } ?: return@withContext null
            val first = fetchDocument(source.url) ?: return@withContext null
            val pageUrls = first.select("select#top_chapter_list ~ div.page_select option:not(:contains(featured))")
                .mapNotNull { option ->
                    val raw = option.attr("value").trim()
                    if (raw.isBlank() || raw.startsWith("javascript:", true)) null
                    else runCatching { URI(source.url).resolve(raw).toString() }.getOrNull()
                        ?.takeIf(::validChapterUrl)
                }.distinct().take(100)

            val imageUrls = mutableListOf<String>()
            if (pageUrls.isNotEmpty()) {
                for ((index, pageUrl) in pageUrls.withIndex()) {
                    if (index > 0) delay(500)
                    val page = fetchDocument(pageUrl) ?: continue
                    page.select("div#viewer img").mapNotNullTo(imageUrls) { image ->
                        sequenceOf("data-src", "data-original", "src").map { image.attr(it).trim() }
                            .firstNotNullOfOrNull { raw ->
                                if (raw.isBlank() || raw.startsWith("data:", true)) null
                                else runCatching { URI(pageUrl).resolve(raw).toString() }.getOrNull()
                                    ?.takeIf(::validImageUrl)
                            }
                    }
                }
            } else {
                first.select("div#viewer img").mapNotNullTo(imageUrls) { image ->
                    sequenceOf("data-src", "data-original", "src").map { image.attr(it).trim() }
                        .firstNotNullOfOrNull { raw ->
                            if (raw.isBlank() || raw.startsWith("data:", true)) null
                            else runCatching { URI(source.url).resolve(raw).toString() }.getOrNull()
                                ?.takeIf(::validImageUrl)
                        }
                }
            }
            imageUrls.normalizedReadingPageUrls().takeIf { it.isNotEmpty() }?.let {
                ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages")
            }
        }

    private fun validChapterUrl(value: String): Boolean = isSafeReadingChapterUrl(
        value, setOf("mangatown.com", "www.mangatown.com")
    ) { path ->
        val parts = path.trim('/').split('/')
        parts.size >= 3 && parts.firstOrNull()?.equals("manga", true) == true &&
            !path.contains("/directory/", true) && !path.contains("/latest/", true) &&
            !path.contains("/search", true)
    }

    private fun validImageUrl(value: String) = isSafeReadingImageUrl(value)

    private suspend fun fetchDocument(url: String): Document? = try {
        documentFetcher?.invoke(url) ?: if (documentFetcher == null) fetchReadingHtmlDocument(
            url, mapOf("Accept" to "text/html,application/xhtml+xml", "User-Agent" to "ShinigamiReadingRuntime/1.0")
        ) else null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
