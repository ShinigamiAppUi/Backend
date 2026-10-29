package streamix.readingcatalog

import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document

/**
 * MANHUA-specific dispatch for the Indonesian Komiku source.
 * Kept separate from the existing MANGA adapter so the runtime respects content kind.
 */
class KomikuManhuaReadingRuntimeProvider(
    private val documentFetcher: ((String) -> Document?)? = null,
) : ReadingRuntimeProvider {
    override val id: String = "komiku-manhua"

    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): Boolean =
        entry.kind == ReadingKind.MANHUA && chapter.sources.any {
            it.enabled && it.providerId.equals(id, ignoreCase = true) && validChapterUrl(it.url)
        }

    override suspend fun chapter(
        entry: ReadingCatalogEntry,
        chapter: ReadingChapterEntry,
    ): ReadingContent? = withContext(Dispatchers.IO) {
        if (entry.kind != ReadingKind.MANHUA) return@withContext null
        val source = chapter.sources.firstOrNull {
            it.enabled && it.providerId.equals(id, ignoreCase = true) && validChapterUrl(it.url)
        } ?: return@withContext null

        val document = try {
            documentFetcher?.invoke(source.url)
                ?: if (documentFetcher == null) fetch(source.url) else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return@withContext null

        val pages = document.select("#Baca_Komik img, #readerarea img, .reading-content img")
            .filterNot { image ->
                sequenceOf("data-lazy-src", "data-src", "data-original", "src")
                    .any { image.attr(it).contains("komiku-promosi", ignoreCase = true) }
            }
            .mapNotNull { image ->
                sequenceOf("data-lazy-src", "data-src", "data-original", "data-srcset", "src")
                    .map { image.attr(it).trim().substringBefore(' ') }
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
        value,
        setOf("komiku.org", "komikid.org", "komiku.to"),
    ) { path -> path.isNotBlank() && path != "/" }

    private fun fetch(value: String): Document? = fetchReadingHtmlDocument(
        value,
        mapOf(
            "Accept" to "text/html,application/xhtml+xml",
            "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        ),
    )
}
