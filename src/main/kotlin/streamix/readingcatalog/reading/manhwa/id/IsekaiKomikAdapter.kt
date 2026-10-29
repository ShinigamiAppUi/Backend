package streamix.readingcatalog

import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document

/**
 * Indonesian Manhwa reader adapter for IsekaiKomik's current chapter host.
 * The source chapter route and image-reader structure were inspected on the public site;
 * fixtures and runtime verification are still pending.
 */
class IsekaiKomikReadingRuntimeProvider(
    private val documentFetcher: ((String) -> Document?)? = null,
) : ReadingRuntimeProvider {
    override val id: String = "isekaikomik"

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

            val pages = document.select("#readerarea img, .reader-area img, .reading-content img, .chapter-content img")
                .mapNotNull { image ->
                    sequenceOf("data-lazy-src", "data-src", "data-original", "data-cfsrc", "src")
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
        setOf("ch1.isekaikomik.site"),
    ) { path ->
        path.trim('/').split('/').filter(String::isNotBlank).let { segments ->
            segments.size == 1 && segments.single().contains("-chapter-", ignoreCase = true)
        }
    }

    private fun validImageUrl(value: String): Boolean = isSafeReadingImageUrl(value)

    private fun fetch(value: String): Document? = fetchReadingHtmlDocument(
        value,
        mapOf(
            "Accept" to "text/html,application/xhtml+xml",
            "Accept-Language" to "id-ID,id;q=0.9,en;q=0.7",
        ),
    )
}
