package streamix.readingcatalog

import com.google.gson.JsonParser
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document

class ComickReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "comick"
    private val acceptedHosts = setOf("comick.dev", "comick.live", "comick.art", "comick.so")

    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): Boolean =
        entry.kind != ReadingKind.NOVEL && chapter.sources.any {
            it.enabled && (it.providerId.equals(id, true) || it.providerId.equals("comick-unoriginal", true)) && validChapter(it.url)
        }

    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? =
        withContext(Dispatchers.IO) {
            val source = chapter.sources.firstOrNull {
                it.enabled && (it.providerId.equals(id, true) || it.providerId.equals("comick-unoriginal", true)) && validChapter(it.url)
            } ?: return@withContext null
            val document = runCatching {
                documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetchComicDocument(source.url) else null
            }.getOrNull() ?: return@withContext null
            // The DOM reader is a real fallback: some chapter pages expose images without
            // the embedded #sv-data payload, and malformed/changed JSON must not hide them.
            val data = document.selectFirst("#sv-data")?.let { node ->
                runCatching {
                    val raw = node.data().ifBlank { node.html() }
                    JsonParser.parseString(raw).takeIf { it.isJsonObject }?.asJsonObject
                }.getOrNull()
            }
            val embeddedImages = data?.getAsJsonObject("chapter")?.getAsJsonArray("images")
                ?.mapNotNull { image ->
                    image.asJsonObject.get("url")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf(::validImage)
                }.orEmpty()
            val domImages = document.select("#chapter-container img, .chapter-container img, .chapter-images img")
                .mapNotNull { image ->
                    sequenceOf("data-lazy-src", "data-src", "data-original", "src").map { image.attr(it).trim() }
                        .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
                        ?.let { raw -> runCatching { URI(source.url).resolve(raw).toString() }.getOrNull() }
                        ?.takeIf(::validImage)
                }
            val pages = (embeddedImages + domImages).distinct()
            pages.takeIf { it.isNotEmpty() }?.let {
                ReadingContent(entry.kind, "reading-provider:${id}", source.url, it, format = "image-pages")
            }
        }

    private fun validChapter(value: String): Boolean = runCatching {
        val uri = URI(value)
        isSafeReadingHttpUrl(value) && uri.host.lowercase() in acceptedHosts &&
            uri.path.startsWith("/comic/") && uri.path.contains("-chapter-")
    }.getOrDefault(false)

    private fun validImage(value: String) = isSafeReadingImageUrl(value)
}

private fun fetchComicDocument(value: String): Document? {
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    if (!isSafeReadingHttpUrl(value)) return null
    return fetchReadingHtmlDocument(value, mapOf("Accept" to "text/html,application/xhtml+xml"))
}
