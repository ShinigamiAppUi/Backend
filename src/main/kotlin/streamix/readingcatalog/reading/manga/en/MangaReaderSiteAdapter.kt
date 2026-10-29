package streamix.readingcatalog

import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document

/**
 * Site-specific adapter for MangaReader.site.
 *
 * This is intentionally separate from MangaReader.to/.org adapters: similarly named
 * domains are different sources and must not share host assumptions.
 */
class MangaReaderSiteReadingRuntimeProvider(
    private val documentFetcher: ((String) -> Document?)? = null,
) : ReadingRuntimeProvider {
    override val id: String = "mangareader-site"

    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): Boolean =
        entry.kind == ReadingKind.MANGA && chapter.sources.any {
            it.enabled && it.providerId.equals(id, ignoreCase = true) && validChapterUrl(it.url)
        }

    override suspend fun chapter(
        entry: ReadingCatalogEntry,
        chapter: ReadingChapterEntry,
    ): ReadingContent? = withContext(Dispatchers.IO) {
        if (entry.kind != ReadingKind.MANGA) return@withContext null
        val source = chapter.sources.firstOrNull {
            it.enabled && it.providerId.equals(id, ignoreCase = true) && validChapterUrl(it.url)
        } ?: return@withContext null

        val document = try {
            documentFetcher?.invoke(source.url)
                ?: if (documentFetcher == null) fetch(source.url) else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return@withContext null

        // MangaReader.site currently serves chapter images from mghcdn.com hosts.
        // Keep extraction scoped to the site's known reader containers and image CDN.
        val pages = document.select(
            ".chapter-container img, .chapter-content img, .reader-content img, .reading-content img, img"
        ).mapNotNull { image ->
            sequenceOf("data-src", "data-lazy-src", "data-original", "src")
                .map { image.attr(it).trim() }
                .firstNotNullOfOrNull { raw ->
                    if (raw.isBlank() || raw.startsWith("data:", ignoreCase = true)) null
                    else runCatching { URI(source.url).resolve(raw).toString() }.getOrNull()
                        ?.takeIf(::isMangaReaderSiteImage)
                }
        }.normalizedReadingPageUrls()

        pages.takeIf { it.isNotEmpty() }?.let {
            ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages")
        }
    }

    private fun validChapterUrl(value: String): Boolean = isSafeReadingChapterUrl(
        value,
        setOf("mangareader.site", "www.mangareader.site"),
    ) { path ->
        path.startsWith("/chapter/") && path.count { it == '/' } >= 3
    }

    private fun isMangaReaderSiteImage(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host?.lowercase() in setOf("mghcdn.com", "www.mghcdn.com", "imgx.mghcdn.com") &&
            uri.userInfo == null && (uri.port == -1 || uri.port == 443)
    }.getOrDefault(false)

    private fun fetch(value: String): Document? = fetchReadingHtmlDocument(
        value,
        mapOf(
            "User-Agent" to "ShinigamiReadingRuntime/1.0",
            "Accept" to "text/html,application/xhtml+xml",
            "Accept-Language" to "en-US,en;q=0.9",
        ),
    )
}
