package streamix.readingcatalog

import java.net.URI
import org.jsoup.nodes.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** MGKomik is a MadaraNoAjax source with chapter pages in its readerarea. */
class MGKomikReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "mg-komik"
    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) =
        entry.kind == ReadingKind.MANGA && chapter.sources.any { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) }

    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        if (entry.kind != ReadingKind.MANGA) return@withContext null
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) } ?: return@withContext null
        val doc = try { documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetch(source.url) else null } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { null } ?: return@withContext null
        val pages = doc.select("div#readerarea img, .reading-content img, .reader-area img, .chapter-content img").mapNotNull { img ->
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
        isSafeReadingChapterUrl(value, setOf("id.mgkomik.cc")) { it.contains("/chapter", true) }

    private fun validImage(value: String) = isSafeReadingImageUrl(value)

    private fun fetch(value: String): Document? =
        fetchReadingHtmlDocument(
            value,
            mapOf(
                "User-Agent" to "ShinigamiReadingRuntime/1.0",
                "Accept" to "text/html,application/xhtml+xml",
            ),
        )
}
