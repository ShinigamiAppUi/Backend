package streamix.readingcatalog

import com.google.gson.JsonParser
import java.net.URI
import org.jsoup.nodes.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BacamiReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "bacami"
    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) =
        entry.kind == ReadingKind.MANGA && chapter.sources.any { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) }
    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        if (entry.kind != ReadingKind.MANGA) return@withContext null
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) } ?: return@withContext null
        val doc = try { documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetch(source.url) else null } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { null } ?: return@withContext null
        // Evaluate candidates after URL validation and normalization. A script can contain
        // a non-empty but stale/unsafe imageUrls array before the actual reader payload.
        // Choosing the first raw non-empty array would mask later usable page lists.
        val pages = doc.select("script")
            .asSequence()
            .mapNotNull { script ->
                val body = script.data().ifBlank { script.html() }
                extractBacamiImageUrlsArray(body)
            }
            .mapNotNull { raw -> runCatching { JsonParser.parseString(raw).asJsonArray }.getOrNull() }
            .mapNotNull { array ->
                array.mapNotNull image@ { value ->
                    val rawImage = value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                        ?.asString?.trim()?.takeIf(String::isNotBlank)
                        ?: return@image null
                    runCatching { URI(source.url).resolve(rawImage).toString() }.getOrNull()
                        ?.takeIf(::validImage)
                }.normalizedReadingPageUrls().takeIf { it.isNotEmpty() }
            }
            .firstOrNull()
            .orEmpty()
        pages.takeIf { it.isNotEmpty() }?.let { ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages") }
    }
    private fun validChapter(value: String) =
        isSafeReadingChapterUrl(value, setOf("v1.bacami.site")) { it.contains("chapter", true) }
    private fun validImage(value: String) = isSafeReadingImageUrl(value)
    private fun fetch(value: String): Document? = fetchIndonesianReadingDocument(value)
}

private fun fetchIndonesianReadingDocument(value: String): Document? =
    fetchReadingHtmlDocument(
        value,
        mapOf(
            "Accept" to "text/html,application/xhtml+xml",
            "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        ),
    )
