package streamix.readingcatalog

import java.net.URI
import org.jsoup.nodes.Document
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HwagoReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "hwago"
    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) = entry.kind == ReadingKind.MANHWA && chapter.sources.any { it.enabled && it.providerId.equals(id, true) && valid(it.url) }
    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        if (entry.kind != ReadingKind.MANHWA) return@withContext null
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && valid(it.url) } ?: return@withContext null
        val doc = try { documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetch(source.url) else null } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null } ?: return@withContext null
        val pages = doc.select("img[alt^='Page ']").mapNotNull { img ->
            sequenceOf("data-lazy-src", "data-src", "data-original", "src")
                .map { key -> img.attr(key).trim() }
                .firstNotNullOfOrNull { raw ->
                    raw.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                        ?.let { runCatching { URI(source.url).resolve(it).toString() }.getOrNull() }
                }?.takeIf { !it.contains("/api/image/p/") && validImage(it) }
        }.distinct()
        pages.takeIf { it.isNotEmpty() }?.let { ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages") }
    }
    private fun valid(v: String) = host(v, "02.hwago.xyz")
    private fun validImage(v: String) = isSafeReadingImageUrl(v)
    private fun fetch(v: String) = fetchManhwaIdDoc(v)
}

private fun host(value: String, expected: String) =
    isSafeReadingChapterUrl(value, setOf(expected)) { it.isNotBlank() }
private fun fetchManhwaIdDoc(value: String): Document? =
    fetchReadingHtmlDocument(
        value,
        mapOf(
            "Accept" to "text/html,application/xhtml+xml",
            "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8",
        ),
    )
