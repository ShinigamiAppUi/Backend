package streamix.readingcatalog

import java.net.URI
import org.json.JSONObject
import org.jsoup.nodes.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SiikomikReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "siikomik"

    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) =
        entry.kind == ReadingKind.MANGA && chapter.sources.any {
            it.enabled && it.providerId.equals(id, true) && validChapter(it.url)
        }

    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        if (entry.kind != ReadingKind.MANGA) return@withContext null
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) }
            ?: return@withContext null
        val doc = try {
            if (documentFetcher != null) documentFetcher.invoke(source.url) else fetchDocument(source.url)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return@withContext null
        val pages = doc.select("#readerarea img, .reading-content img").mapNotNull { image ->
            sequenceOf("data-lazy-src", "data-src", "data-original", "src")
                .map { image.attr(it).trim() }
                .mapNotNull { raw ->
                    raw.takeIf { it.isNotBlank() && !it.startsWith("data:", ignoreCase = true) }
                        ?.let { resolveSafeImage(it, source.url) }
                }
                .firstOrNull()
        }.filterNotNull().normalizedReadingPageUrls()
        pages.takeIf { it.isNotEmpty() }?.let {
            ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages")
        }
    }

    private fun validChapter(value: String) = isSafeReadingChapterUrl(value, setOf("siikomik.id")) { it.isNotBlank() }

    private fun resolveSafeImage(value: String, base: String): String? = runCatching {
        val image = URI(base).resolve(value)
        image.toString().takeIf(::isSafeReadingImageUrl)
    }.getOrNull()

    private fun fetchDocument(value: String): Document? {
        if (!validChapter(value)) return null
        return fetchReadingHtmlDocument(
            value,
            mapOf(
                "User-Agent" to "ShinigamiReadingRuntime/1.0",
                "Accept" to "text/html,application/xhtml+xml",
                "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8",
            ),
        )
    }
}


