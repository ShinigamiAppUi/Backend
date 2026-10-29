package streamix.readingcatalog

import java.net.URI
import org.json.JSONObject
import org.jsoup.nodes.Document
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** MangaK (mangak.io) embeds the initial chapter image list in Next.js pageProps. */
class MangaKReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "mangak"
    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) =
        entry.kind == ReadingKind.MANGA && chapter.sources.any { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) }
    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) } ?: return@withContext null
        val doc = try { documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetch(source.url) else null } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null } ?: return@withContext null
        val script = doc.select("script#__NEXT_DATA__").firstOrNull()?.data()?.takeIf(String::isNotBlank)
            ?: doc.select("script").firstNotNullOfOrNull { it.data().takeIf { data -> data.contains("\"initialChapter\"") && data.contains("\"images\"") } }
            ?: return@withContext null
        val pages = runCatching {
            val root = JSONObject(script)
            val props = root.optJSONObject("props")?.optJSONObject("pageProps") ?: root.optJSONObject("pageProps") ?: return@runCatching emptyList<String>()
            val chapterObj = props.optJSONObject("initialChapter") ?: props.optJSONObject("chapter") ?: return@runCatching emptyList<String>()
            val arr = chapterObj.optJSONArray("images") ?: return@runCatching emptyList<String>()
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { u -> u.isNotBlank() }?.takeIf(::validImage) }
        }.getOrNull().orEmpty().distinct()
        pages.takeIf { it.isNotEmpty() }?.let { ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages") }
    }
    private fun validChapter(value: String) = runCatching { URI(value).let { isSafeReadingHttpUrl(value) && it.host.equals("mangak.io", true) && it.path.isNotBlank() } }.getOrDefault(false)
    private fun validImage(value: String) = isSafeReadingImageUrl(value)
    private fun fetch(value: String): Document? = fetchReadingHtmlDocument(
        value,
        mapOf(
            "User-Agent" to "ShinigamiReadingRuntime/1.0",
            "Accept" to "text/html,application/xhtml+xml",
        ),
    )
}
