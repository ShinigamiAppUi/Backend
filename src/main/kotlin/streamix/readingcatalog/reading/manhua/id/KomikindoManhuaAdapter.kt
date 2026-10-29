package streamix.readingcatalog

import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document

/**
 * Komikindo Indonesian manhua chapter-page adapter.
 *
 * This handles manually curated chapter URLs only. Its domain and reader selectors
 * still require a live fixture pass; it does not perform title discovery.
 */
class KomikindoManhuaReadingRuntimeProvider(
    private val documentFetcher: ((String) -> Document?)? = null,
) : ReadingRuntimeProvider {
    override val id = "komikindo-manhua"

    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): Boolean =
        entry.kind == ReadingKind.MANHUA && chapter.sources.any {
            it.enabled && it.providerId.equals(id, true) && isValidChapterUrl(it.url)
        }

    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? =
        withContext(Dispatchers.IO) {
            if (entry.kind != ReadingKind.MANHUA) return@withContext null
            val source = chapter.sources.firstOrNull {
                it.enabled && it.providerId.equals(id, true) && isValidChapterUrl(it.url)
            } ?: return@withContext null

            val document = try {
                documentFetcher?.invoke(source.url)
                    ?: if (documentFetcher == null) fetchReadingHtmlDocument(
                        source.url,
                        mapOf(
                            "Accept" to "text/html,application/xhtml+xml",
                            "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
                        ),
                    ) else null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: return@withContext null

            val pages = document.select(
                "#readerarea img, .reader-area img, .reading-content img, .chapter-content img",
            ).mapNotNull { image ->
                sequenceOf("data-lazy-src", "data-src", "data-original", "data-cfsrc", "src")
                    .map { image.attr(it).trim() }
                    .firstNotNullOfOrNull { raw ->
                        if (raw.isBlank() || raw.startsWith("data:", ignoreCase = true)) null
                        else runCatching { URI(source.url).resolve(raw).toString() }
                            .getOrNull()
                            ?.takeIf(::isSafeReadingImageUrl)
                    }
            }.normalizedReadingPageUrls()

            pages.takeIf { it.isNotEmpty() }?.let {
                ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages")
            }
        }

    private fun isValidChapterUrl(value: String): Boolean = runCatching {
        URI(value).let { uri ->
            isSafeReadingHttpUrl(value) &&
                uri.host.equals("komikindo4.link", true) &&
                uri.userInfo == null &&
                uri.port in setOf(-1, 443) &&
                !uri.path.isNullOrBlank() &&
                (uri.path.contains("chapter", true) || uri.path.contains("/read/", true))
        }
    }.getOrDefault(false)
}
