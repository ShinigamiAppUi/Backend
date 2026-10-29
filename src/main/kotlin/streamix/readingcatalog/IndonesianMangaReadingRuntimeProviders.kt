package streamix.readingcatalog

import com.google.gson.JsonParser
import java.net.URI
import org.jsoup.nodes.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Komiku reader parses only its #Baca_Komik page container and removes promotional images. */
class KomikuReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "komiku"
    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) =
        entry.kind == ReadingKind.MANGA && chapter.sources.any { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) }
    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        if (entry.kind != ReadingKind.MANGA) return@withContext null
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) } ?: return@withContext null
        val doc = try { documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetch(source.url) else null } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { null } ?: return@withContext null
        val pages = doc.select("#Baca_Komik img").filterNot { img ->
            sequenceOf("data-lazy-src", "data-src", "data-original", "src").any { img.attr(it).contains("komiku-promosi", true) }
        }.mapNotNull { img ->
            sequenceOf("data-lazy-src", "data-src", "data-original", "src")
                .map { key -> img.attr(key).trim() }
                .firstNotNullOfOrNull { raw ->
                    raw.takeIf { it.isNotBlank() && !it.startsWith("data:", ignoreCase = true) }
                        ?.let { runCatching { URI(source.url).resolve(it).toString() }.getOrNull() }
                        ?.takeIf(::validImage)
                }
        }.normalizedReadingPageUrls()
        pages.takeIf { it.isNotEmpty() }?.let { ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages") }
    }
    private fun validChapter(value: String) =
        isSafeReadingChapterUrl(value, setOf("komiku.org", "komikid.org", "komiku.to")) { it.isNotBlank() }
    private fun validImage(value: String) = isSafeReadingImageUrl(value)
    private fun fetch(value: String): Document? = fetchIndonesianReadingDocument(value)
}

/**
 * Extracts a balanced JSON array assigned to imageUrls. A non-greedy regex stops at the
 * first closing bracket, which breaks when an image entry contains a nested array/object.
 * Scan brackets while respecting quoted strings and escapes instead.
 */
private fun extractBacamiImageUrlsArray(script: String): String? {
    val assignment = Regex("""\bimageUrls\s*[:=]\s*\[""").find(script) ?: return null
    val start = assignment.range.last
    var depth = 0
    var inString = false
    var escaped = false
    for (index in start until script.length) {
        val char = script[index]
        if (inString) {
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> inString = false
            }
            continue
        }
        when (char) {
            '"' -> inString = true
            '[' -> depth++
            ']' -> {
                depth--
                if (depth == 0) return script.substring(start, index + 1)
                if (depth < 0) return null
            }
        }
    }
    return null
}

/** Bacami exposes a JSON-like imageUrls array in its chapter script. */
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

/** KomikIndoID resolves lazy image attributes and the site's explicit onError fallback URL. */
class KomikIndoIdReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "komikindoid"
    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) =
        entry.kind == ReadingKind.MANGA && chapter.sources.any { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) }
    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        if (entry.kind != ReadingKind.MANGA) return@withContext null
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) } ?: return@withContext null
        val doc = try { documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetch(source.url) else null } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { null } ?: return@withContext null
        val pages = doc.select("div.img-landmine img").mapNotNull { img ->
            val onError = img.attr("onError")
            val fallback = Regex("""src=['"]([^'"]+)['"]""").find(onError)?.groupValues?.get(1)
            sequenceOf(fallback, img.attr("data-lazy-src"), img.attr("data-src"), img.attr("data-original"), img.attr("src"))
                .filterNotNull()
                .map(String::trim)
                .firstNotNullOfOrNull { raw ->
                    raw.takeIf { it.isNotBlank() && !it.startsWith("data:", ignoreCase = true) }
                        ?.let { runCatching { URI(source.url).resolve(it).toString() }.getOrNull() }
                        ?.takeIf(::validImage)
                }
        }.normalizedReadingPageUrls()
        pages.takeIf { it.isNotEmpty() }?.let { ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages") }
    }
    private fun validChapter(value: String) =
        isSafeReadingChapterUrl(value, setOf("komikindo.ch")) { it.isNotBlank() }
    private fun validImage(value: String) = isSafeReadingImageUrl(value)
    private fun fetch(value: String): Document? = fetchIndonesianReadingDocument(value)
}

/** KlikManga uses a Madara-based chapter reader; image extraction is scoped to its reader container. */
class KlikMangaReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "klikmanga"
    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) =
        entry.kind == ReadingKind.MANGA && chapter.sources.any { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) }
    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        if (entry.kind != ReadingKind.MANGA) return@withContext null
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) } ?: return@withContext null
        val doc = try { documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetch(source.url) else null } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { null } ?: return@withContext null
        val pages = doc.select(".reading-content img, .page-break img, .chapter-content img").mapNotNull { img ->
            sequenceOf("data-src", "data-lazy-src", "data-original", "src")
                .map { key -> img.attr(key).trim() }
                .mapNotNull { raw ->
                    raw.takeIf { it.isNotBlank() && !it.startsWith("data:", ignoreCase = true) }
                        ?.let { runCatching { URI(source.url).resolve(it).toString() }.getOrNull() }
                        ?.takeIf(::validImage)
                }
                .firstOrNull()
        }.normalizedReadingPageUrls()
        pages.takeIf { it.isNotEmpty() }?.let { ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages") }
    }
    private fun validChapter(value: String) =
        isSafeReadingChapterUrl(value, setOf("klikmanga.org")) { it.isNotBlank() }
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
