package streamix.readingcatalog

import java.net.URI
import org.jsoup.nodes.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Kuro Manga uses MangaThemesia readerarea images and supports lazy-load attributes. */
class KuroMangaReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "kuro-manga"
    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) =
        entry.kind == ReadingKind.MANGA && chapter.sources.any { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) }

    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        if (entry.kind != ReadingKind.MANGA) return@withContext null
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) } ?: return@withContext null
        val doc = try { documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetch(source.url) else null } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { null } ?: return@withContext null
        val pages = doc.select("#readerarea img").mapNotNull { img ->
            sequenceOf("data-lazy-src", "data-src", "data-original", "src")
                .mapNotNull { key ->
                    img.attr(key).trim()
                        .takeIf { it.isNotBlank() && !it.startsWith("data:", ignoreCase = true) }
                        ?.let { raw -> runCatching { URI(source.url).resolve(raw).toString() }.getOrNull() }
                        ?.takeIf(::validImage)
                }
                .firstOrNull()
        }.normalizedReadingPageUrls()
        pages.takeIf { it.isNotEmpty() }?.let { ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages") }
    }

    private fun validChapter(value: String) =
        isSafeReadingChapterUrl(value, setOf("kuromanga.id")) { it.path.isNotBlank() }

    private fun validImage(value: String) = isSafeReadingImageUrl(value)

    private fun fetch(value: String): Document? =
        fetchReadingHtmlDocument(
            value,
            mapOf(
                "Accept" to "text/html,application/xhtml+xml",
                "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8",
            ),
        )
}
