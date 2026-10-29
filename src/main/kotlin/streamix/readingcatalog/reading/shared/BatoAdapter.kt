package streamix.readingcatalog

import com.google.gson.JsonParser
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document

class BatoReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "bato"
    private val acceptedProviderIds = setOf("bato", "bbato")

    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): Boolean =
        entry.kind != ReadingKind.NOVEL && chapter.sources.any {
            it.enabled && it.providerId.lowercase() in acceptedProviderIds && validChapter(it.url)
        }

    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? =
        withContext(Dispatchers.IO) {
            val source = chapter.sources.firstOrNull {
                it.enabled && it.providerId.lowercase() in acceptedProviderIds && validChapter(it.url)
            } ?: return@withContext null
            val document = runCatching {
                documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetchComicDocument(source.url) else null
            }.getOrNull() ?: return@withContext null
            val pages = document.select(".pages .page:not(.notice-page) img").mapNotNull { image ->
                sequenceOf("data-lazy-src", "data-src", "data-original", "src").map { image.attr(it).trim() }
                    .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
                    ?.let { runCatching { URI(source.url).resolve(it).toString() }.getOrNull() }
                    ?.takeIf(::validImage)
            }.distinct()
            pages.takeIf { it.isNotEmpty() }?.let {
                ReadingContent(entry.kind, "reading-provider:${id}", source.url, it, format = "image-pages")
            }
        }

    private fun validChapter(value: String): Boolean = runCatching {
        val uri = URI(value)
        isSafeReadingHttpUrl(value) && uri.host.lowercase() in setOf("bato.to", "bato.si", "bato.ing", "bato.ac", "bato1.com", "bbato.com") &&
            (uri.path.startsWith("/read/") || uri.path.contains("/chapter", ignoreCase = true))
    }.getOrDefault(false)

    private fun validImage(value: String) = isSafeReadingImageUrl(value)
}

private fun fetchComicDocument(value: String): Document? {
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    if (!isSafeReadingHttpUrl(value)) return null
    return fetchReadingHtmlDocument(value, mapOf("Accept" to "text/html,application/xhtml+xml"))
}
