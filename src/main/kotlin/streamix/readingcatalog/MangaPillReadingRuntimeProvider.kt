package streamix.readingcatalog

import java.net.URI
import org.jsoup.nodes.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** MangaPill reader parser using its own picture/img markup, not a shared site parser. */
class MangaPillReadingRuntimeProvider(
    private val documentFetcher: ((String) -> Document?)? = null
) : ReadingRuntimeProvider {
    override val id = "mangapill"

    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): Boolean =
        entry.kind != ReadingKind.NOVEL && chapter.sources.any {
            it.enabled && it.providerId.equals(id, true) && isChapterUrl(it.url)
        }

    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? =
        withContext(Dispatchers.IO) {
            if (entry.kind == ReadingKind.NOVEL) return@withContext null
            val source = chapter.sources.firstOrNull {
                it.enabled && it.providerId.equals(id, true) && isChapterUrl(it.url)
            } ?: return@withContext null
            val doc = runCatching {
                documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetchDocument(source.url) else null
            }.getOrNull() ?: return@withContext null
            val pages = doc.select("picture img").mapNotNull { image ->
                sequenceOf(image.attr("data-lazy-src"), image.attr("data-src"), image.attr("data-original"), image.attr("src"))
                    .map(String::trim).firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
                    ?.let { runCatching { URI(source.url).resolve(it).toString() }.getOrNull() }
                    ?.takeIf(::validPageUrl)
            }.distinct()
            pages.takeIf { it.isNotEmpty() }?.let {
                ReadingContent(entry.kind, "reading-provider:$id", contentUrl = source.url, pageUrls = it, format = "image-pages")
            }
        }

    private fun isChapterUrl(value: String): Boolean = isSafeReadingChapterUrl(
        value,
        setOf("mangapill.com", "www.mangapill.com")
    ) { path -> path.contains("/chapters/", ignoreCase = true) }

    private fun validPageUrl(value: String): Boolean = isSafeReadingImageUrl(value)

    private fun fetchDocument(value: String): Document? = fetchReadingHtmlDocument(
        value,
        mapOf(
            "User-Agent" to "ShinigamiReadingRuntime/1.0",
            "Accept" to "text/html,application/xhtml+xml",
        ),
    )
}
