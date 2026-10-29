package streamix.readingcatalog

import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document

/** MangaPark.tv reader adapter for its Madara-based chapter page markup. */
class MangaParkTvReadingRuntimeProvider(
    private val documentFetcher: ((String) -> Document?)? = null
) : ReadingRuntimeProvider {
    override val id = "mangapark-tv"

    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): Boolean =
        entry.kind != ReadingKind.NOVEL &&
            chapter.sources.any { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) }

    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? =
        withContext(Dispatchers.IO) {
            val source = chapter.sources.firstOrNull {
                it.enabled && it.providerId.equals(id, true) && validChapter(it.url)
            } ?: return@withContext null
            val document = runCatching {
                documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetchMangaParkDocument(source.url) else null
            }.getOrNull() ?: return@withContext null
            val pages = document.select(".reading-content img, .page-break img, .wp-manga-chapter-img")
                .mapNotNull { image ->
                    sequenceOf("data-src", "data-lazy-src", "data-original", "src").map { image.attr(it).trim() }
                        .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
                        ?.let { runCatching { URI(source.url).resolve(it).toString() }.getOrNull() }
                        ?.takeIf(::validImage)
                }.distinct()
            pages.takeIf { it.isNotEmpty() }?.let {
                ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages")
            }
        }

    private fun validChapter(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", true) && uri.host.equals("mangapark.tv", true) && uri.userInfo == null && (uri.port == -1 || uri.port == 443) &&
            uri.path.contains("/chapter", ignoreCase = true)
    }.getOrDefault(false)

    private fun validImage(value: String): Boolean = isSafeReadingImageUrl(value)
}

private fun fetchMangaParkDocument(value: String): Document? =
    fetchReadingHtmlDocument(value, mapOf("Accept" to "text/html,application/xhtml+xml"))
