package streamix.readingcatalog

import java.net.URI
import org.jsoup.nodes.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ManhwaIndoReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "manhwa-indo"
    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) = entry.kind == ReadingKind.MANHWA && chapter.sources.any { it.enabled && it.providerId.equals(id, true) && valid(it.url) }
    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && valid(it.url) } ?: return@withContext null
        val doc = runCatching { documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetch(source.url) else null }.getOrNull() ?: return@withContext null
        val pages = doc.select("#readerarea img").mapNotNull { img -> image(source.url, img.attr("data-lazy-src"), img.attr("data-src"), img.attr("data-original"), img.attr("src")) }.distinct()
        pages.takeIf { it.isNotEmpty() }?.let { ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages") }
    }
    private fun valid(v: String) = host(v, "www.manhwaindo.my")
    private fun image(base: String, vararg values: String) = values.firstNotNullOfOrNull { it.takeIf { v -> v.isNotBlank() && !v.startsWith("data:") } }?.let { runCatching { URI(base).resolve(it).toString() }.getOrNull() }?.takeIf(::validImage)
    private fun validImage(v: String) = isSafeReadingImageUrl(v)
    private fun fetch(v: String) = fetchManhwaIdDoc(v)
}

class HolotoonReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "holotoon"
    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) = entry.kind == ReadingKind.MANHWA && chapter.sources.any { it.enabled && it.providerId.equals(id, true) && valid(it.url) }
    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && valid(it.url) } ?: return@withContext null
        val doc = runCatching { documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetch(source.url) else null }.getOrNull() ?: return@withContext null
        val candidates = doc.select("#reader-pages img").ifEmpty { doc.select("main img[src*='/image/comic/'], main img[data-src*='/image/comic/']") }
        val pages = candidates.mapNotNull { img ->
            sequenceOf("data-lazy-src", "data-src", "data-original", "src")
                .map { key -> img.attr(key).trim() }
                .firstNotNullOfOrNull { raw ->
                    raw.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                        ?.let { runCatching { URI(source.url).resolve(it).toString() }.getOrNull() }
                }?.takeIf { !it.contains("/chapter-header/") && validImage(it) }
        }.distinct()
        pages.takeIf { it.isNotEmpty() }?.let { ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages") }
    }
    private fun valid(v: String) = host(v, "holodek.run")
    private fun validImage(v: String) = isSafeReadingImageUrl(v)
    private fun fetch(v: String) = fetchManhwaIdDoc(v)
}

class HwagoReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "hwago"
    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) = entry.kind == ReadingKind.MANHWA && chapter.sources.any { it.enabled && it.providerId.equals(id, true) && valid(it.url) }
    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && valid(it.url) } ?: return@withContext null
        val doc = runCatching { documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetch(source.url) else null }.getOrNull() ?: return@withContext null
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

class LumosKomikReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "lumoskomik"
    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) = entry.kind == ReadingKind.MANHWA && chapter.sources.any { it.enabled && it.providerId.equals(id, true) && valid(it.url) }
    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && valid(it.url) } ?: return@withContext null
        val doc = runCatching { documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetch(source.url) else null }.getOrNull() ?: return@withContext null
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
    private fun valid(v: String) = host(v, "03.lumosgg.com")
    private fun validImage(v: String) = isSafeReadingImageUrl(v)
    private fun fetch(v: String) = fetchManhwaIdDoc(v)
}

class NatsuReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "natsu"
    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) = entry.kind == ReadingKind.MANHWA && chapter.sources.any { it.enabled && it.providerId.equals(id, true) && valid(it.url) }
    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && valid(it.url) } ?: return@withContext null
        val doc = runCatching { documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetch(source.url) else null }.getOrNull() ?: return@withContext null
        val pages = doc.select("main .relative section > img").mapNotNull { img ->
            listOf(img.attr("data-src"), img.attr("data-lazy-src"), img.attr("data-original"), img.attr("src")).firstNotNullOfOrNull { it.takeIf { value -> value.isNotBlank() && !value.startsWith("data:") } }?.let { runCatching { URI(source.url).resolve(it).toString() }.getOrNull() }?.takeIf(::validImage)
        }.distinct()
        pages.takeIf { it.isNotEmpty() }?.let { ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages") }
    }
    private fun valid(v: String) = host(v, "natsu.one")
    private fun validImage(v: String) = isSafeReadingImageUrl(v)
    private fun fetch(v: String) = fetchManhwaIdDoc(v)
}

class IzanamiScansReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "izanami-scans"
    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) = entry.kind == ReadingKind.MANHWA && chapter.sources.any { it.enabled && it.providerId.equals(id, true) && valid(it.url) }
    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && valid(it.url) } ?: return@withContext null
        val doc = runCatching { documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetch(source.url) else null }.getOrNull() ?: return@withContext null
        val pages = doc.select("#readerarea img").mapNotNull { img -> listOf(img.attr("data-lazy-src"), img.attr("data-src"), img.attr("data-original"), img.attr("src")).firstNotNullOfOrNull { it.takeIf { value -> value.isNotBlank() && !value.startsWith("data:") } }?.let { runCatching { URI(source.url).resolve(it).toString() }.getOrNull() }?.takeIf(::validImage) }.distinct()
        pages.takeIf { it.isNotEmpty() }?.let { ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages") }
    }
    private fun valid(v: String) = host(v, "izanamiscans.my.id")
    private fun validImage(v: String) = isSafeReadingImageUrl(v)
    private fun fetch(v: String) = fetchManhwaIdDoc(v)
}

class RosyscansReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "rosyscans"
    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) = entry.kind == ReadingKind.MANHWA && chapter.sources.any { it.enabled && it.providerId.equals(id, true) && valid(it.url) }
    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && valid(it.url) } ?: return@withContext null
        val doc = runCatching { documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetch(source.url) else null }.getOrNull() ?: return@withContext null
        val pages = doc.select("#readerarea img").mapNotNull { img -> listOf(img.attr("data-lazy-src"), img.attr("data-src"), img.attr("data-original"), img.attr("src")).firstNotNullOfOrNull { it.takeIf { value -> value.isNotBlank() && !value.startsWith("data:") } }?.let { runCatching { URI(source.url).resolve(it).toString() }.getOrNull() }?.takeIf(::validImage) }.distinct()
        pages.takeIf { it.isNotEmpty() }?.let { ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages") }
    }
    private fun valid(v: String) = host(v, "rosyscans.com")
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
