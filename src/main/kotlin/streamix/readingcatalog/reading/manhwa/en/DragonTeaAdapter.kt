package streamix.readingcatalog

import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document

/**
 * Dragon Tea serves both comics and prose novels on the same canonical host.
 * The adapter branches by catalog kind: image-page extraction for manhwa and
 * text extraction for novel chapter pages. Text selectors remain pending live fixtures.
 */
class DragonTeaReadingRuntimeProvider(
    private val documentFetcher: ((String) -> Document?)? = null
) : ReadingRuntimeProvider {
    override val id = "dragontea"

    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): Boolean =
        entry.kind in setOf(ReadingKind.MANHWA, ReadingKind.NOVEL) &&
            chapter.sources.any {
                it.enabled && it.providerId.equals(id, true) && validChapter(it.url)
            }

    override suspend fun chapter(
        entry: ReadingCatalogEntry,
        chapter: ReadingChapterEntry
    ): ReadingContent? = withContext(Dispatchers.IO) {
        if (entry.kind !in setOf(ReadingKind.MANHWA, ReadingKind.NOVEL)) return@withContext null
        val source = chapter.sources.firstOrNull {
            it.enabled && it.providerId.equals(id, true) && validChapter(it.url)
        } ?: return@withContext null

        val document = try {
            documentFetcher?.invoke(source.url)
                ?: if (documentFetcher == null) {
                    // Novel prose uses the bounded novel response path; comic pages use
                    // the regular HTML fetcher. Keep the same injectable fetcher for tests.
                    if (entry.kind == ReadingKind.NOVEL) fetchNovelDocument(source.url)
                    else fetchManhwaDocument(source.url)
                } else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return@withContext null

        if (entry.kind == ReadingKind.NOVEL) {
            val text = extractFirstReadingNovelText(
                document,
                ".text-left, .text-right, .reading-content, .entry-content, #chapter-content, .chapter-content"
            )
            return@withContext text.takeIf(String::isNotBlank)?.let {
                ReadingContent(entry.kind, "reading-provider:$id", source.url, text = it, format = "text")
            }
        }

        val pages = document.select(".reading-content img, .page-break img").mapNotNull { img ->
            sequenceOf("data-src", "data-lazy-src", "data-original", "src")
                .map(String::trim)
                .firstNotNullOfOrNull { key ->
                    img.attr(key).takeIf { it.isNotBlank() && !it.startsWith("data:") }
                }
                ?.let { runCatching { URI(source.url).resolve(it).toString() }.getOrNull() }
                ?.takeIf(::validImage)
        }.distinct()

        pages.takeIf { it.isNotEmpty() }?.let {
            ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages")
        }
    }

    private fun validChapter(value: String): Boolean = runCatching {
        URI(value).let {
            isSafeReadingHttpUrl(value) &&
                (it.host.equals("dragontea.ink", true) || it.host.equals("dragontea.com", true)) &&
                it.path.contains("/chapter", true)
        }
    }.getOrDefault(false)

    private fun validImage(value: String) = isSafeReadingImageUrl(value)
}

private fun fetchManhwaDocument(value: String): Document? =
    fetchReadingHtmlDocument(value, mapOf("Accept" to "text/html,application/xhtml+xml"))
