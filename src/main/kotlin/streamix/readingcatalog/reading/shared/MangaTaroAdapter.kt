package streamix.readingcatalog

import java.net.URI
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** MangaTaro serves chapter image URLs from /auth/chapter-content?chapter_id=... rather than page HTML. */
class MangaTaroReadingRuntimeProvider(
    private val responseFetcher: ((String) -> String?)? = null
) : ReadingRuntimeProvider {
    override val id = "mangataro"
    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) =
        entry.kind == ReadingKind.MANGA && chapter.sources.any { it.enabled && it.providerId.equals(id, true) && chapterId(it.url) != null && validChapter(it.url) }

    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && chapterId(it.url) != null && validChapter(it.url) } ?: return@withContext null
        val chapterId = chapterId(source.url) ?: return@withContext null
        val apiUrl = "https://mangataro.org/auth/chapter-content?chapter_id=$chapterId"
        val body = runCatching { responseFetcher?.invoke(apiUrl) ?: if (responseFetcher == null) fetch(apiUrl) else null }.getOrNull() ?: return@withContext null
        val images = runCatching {
            val root = JSONObject(body)
            val array = root.optJSONArray("images") ?: root.optJSONObject("data")?.optJSONArray("images") ?: return@runCatching emptyList<String>()
            (0 until array.length()).mapNotNull { i -> array.optString(i).takeIf { it.isNotBlank() }?.let { raw -> runCatching { URI(source.url).resolve(raw).toString() }.getOrNull() }?.takeIf(::validImage) }
        }.getOrNull().orEmpty().distinct()
        images.takeIf { it.isNotEmpty() }?.let { ReadingContent(entry.kind, "reading-provider:${this@MangaTaroReadingRuntimeProvider.id}", source.url, it, format = "image-pages") }
    }

    private fun chapterId(value: String): String? = runCatching {
        URI(value).path.trimEnd('/').substringAfterLast('-').takeIf { it.isNotBlank() && it.all(Char::isDigit) }
    }.getOrNull()
    private fun validChapter(value: String) = runCatching { URI(value).let { isSafeReadingHttpUrl(value) && it.host.equals("mangataro.org", true) && it.path.contains("chapter", true) } }.getOrDefault(false)
    private fun validImage(value: String) = isSafeReadingImageUrl(value)
    private fun fetch(value: String): String? = ReadingHttpTransport.getText(
        value,
        mapOf(
            "User-Agent" to "ShinigamiReadingRuntime/1.0",
            "Accept" to "application/json",
        ),
    )
}
