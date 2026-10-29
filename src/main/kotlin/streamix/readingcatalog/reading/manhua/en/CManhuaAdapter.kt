package streamix.readingcatalog

import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document

/**
 * CManhua's reader uses the ASP.NET ReadComic?id=... route and chapter-image markup.
 * The catalog supplies the chapter URL; this adapter does not crawl the site's listing/postback flow.
 */
class CManhuaReadingRuntimeProvider(
    private val documentFetcher: ((String) -> Document?)? = null,
) : ReadingRuntimeProvider {
    override val id = "cmanhua"

    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): Boolean =
        entry.kind == ReadingKind.MANHUA && chapter.sources.any {
            it.enabled && it.providerId.equals(id, true) && validChapterUrl(it.url)
        }

    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? =
        withContext(Dispatchers.IO) {
            if (entry.kind != ReadingKind.MANHUA) return@withContext null
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

            val pages = document.select("img.chapter-image").mapNotNull { image ->
                sequenceOf("data-src", "src")
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

    private fun validChapterUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", true) &&
            uri.host?.lowercase() in setOf("cmanhua.com", "www.cmanhua.com") &&
            uri.userInfo == null && uri.port in setOf(-1, 443) &&
            uri.path.equals("/ReadComic", true) &&
            !uri.rawQuery.isNullOrBlank() &&
            uri.rawQuery.split("&").any { it.substringBefore("=") == "id" && it.substringAfter("=", "").isNotBlank() }
    }.getOrDefault(false)

    private fun validImageUrl(value: String): Boolean = isSafeReadingImageUrl(value)

    private fun fetch(value: String): Document? = fetchReadingHtmlDocument(
        value,
        mapOf(
            "User-Agent" to "ShinigamiReadingRuntime/1.0",
            "Accept" to "text/html,application/xhtml+xml",
            "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.7",
        ),
    )
}
