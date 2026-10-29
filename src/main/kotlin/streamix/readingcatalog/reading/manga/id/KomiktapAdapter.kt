package streamix.readingcatalog

import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document

/**
 * Komiktap uses MangaThemesia reader markup.
 * This adapter resolves a manually catalogued chapter URL; metadata and source discovery remain separate.
 */
class KomiktapReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "komiktap"

    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): Boolean =
        entry.kind == ReadingKind.MANGA && chapter.sources.any {
            it.enabled && it.providerId.equals(id, true) && validChapterUrl(it.url)
        }

    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? =
        withContext(Dispatchers.IO) {
            if (entry.kind != ReadingKind.MANGA) return@withContext null
            val source = chapter.sources.firstOrNull {
                it.enabled && it.providerId.equals(id, true) && validChapterUrl(it.url)
            } ?: return@withContext null
            val document = try {
                documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetch(source.url) else null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: return@withContext null

            val pages = document.select("div#readerarea img, .reading-content img").mapNotNull { image ->
                sequenceOf("data-lazy-src", "data-src", "data-original", "data-url", "src")
                    .map { image.attr(it).trim() }
                    .firstNotNullOfOrNull { raw ->
                        if (raw.isBlank() || raw.startsWith("data:", ignoreCase = true)) null
                        else runCatching { URI(source.url).resolve(raw).toString() }.getOrNull()
                            ?.takeIf(::validImageUrl)
                    }
            }.normalizedReadingPageUrls()

            pages.takeIf { it.isNotEmpty() }?.let {
                ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages")
            }
        }

    private fun validChapterUrl(value: String): Boolean = isSafeReadingChapterUrl(
        value,
        setOf("komiktap.info", "www.komiktap.info"),
    ) { path ->
        path.contains("/chapter", ignoreCase = true) || path.contains("/ch-", ignoreCase = true)
    }

    private fun validImageUrl(value: String): Boolean = isSafeReadingImageUrl(value)

    private fun fetch(value: String): Document? = fetchReadingHtmlDocument(
        value,
        mapOf(
            "User-Agent" to "ShinigamiReadingRuntime/1.0",
            "Accept" to "text/html,application/xhtml+xml",
            "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        ),
    )
}
