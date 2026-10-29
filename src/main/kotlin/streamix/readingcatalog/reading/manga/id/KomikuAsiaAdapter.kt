package streamix.readingcatalog

import java.net.URI
import org.json.JSONObject
import org.jsoup.nodes.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class KomikuAsiaReadingRuntimeProvider(private val responseFetcher: ((String) -> String?)? = null) : ReadingRuntimeProvider {
    override val id = "komiku-asia"

    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) =
        entry.kind == ReadingKind.MANGA && chapter.sources.any {
            it.enabled && it.providerId.equals(id, true) && chapterPath(it.url) != null
        }

    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        if (entry.kind != ReadingKind.MANGA) return@withContext null
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && chapterPath(it.url) != null }
            ?: return@withContext null
        val (comicId, chapterId) = chapterPath(source.url) ?: return@withContext null
        val apiUrl = "https://01.komiku.asia/api/v2/comics/$comicId/chapters/id/$chapterId"
        val body = try {
            if (responseFetcher != null) responseFetcher.invoke(apiUrl) else fetchJson(apiUrl)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return@withContext null
        val pages = runCatching {
            val root = JSONObject(body)
            val data = root.optJSONObject("data") ?: root
            // APIs can wrap chapter details either directly at the root or under data.
            // Keep both chapter objects in the candidate sequence so an unrelated/partial
            // data object cannot hide a usable root-level chapter.pages array.
            val rootChapter = root.optJSONObject("chapter")
            val dataChapter = data.optJSONObject("chapter")
            sequenceOf(
                root.optJSONArray("pages"),
                data.optJSONArray("pages"),
                rootChapter?.optJSONArray("pages"),
                dataChapter?.optJSONArray("pages"),
            )
                .filterNotNull()
                // Payloads can expose overlapping or partial page lists at multiple nesting levels.
                // Merge all candidates; normalizedReadingPageUrls() below removes duplicates and unsafe URLs.
                .flatMap { pageArray ->
                    (0 until pageArray.length()).mapNotNull { index ->
                        val item = pageArray.opt(index)
                        when (item) {
                            is JSONObject -> sequenceOf("url", "src", "image", "image_url")
                                .mapNotNull { key ->
                                    (item.opt(key) as? String)
                                        ?.let { resolveSafeImage(it, apiUrl) }
                                }
                                .firstOrNull()
                            is String -> item.takeIf { it.isNotBlank() && it != "null" }?.let { resolveSafeImage(it, apiUrl) }
                            else -> null
                        }
                    }
                }
                .toList()
        }.getOrNull().orEmpty().filter(String::isNotBlank).normalizedReadingPageUrls()
        pages.takeIf { it.isNotEmpty() }?.let {
            ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages")
        }
    }

    private fun chapterPath(value: String): Pair<String, String>? {
        if (!isSafeReadingChapterUrl(value, setOf("01.komiku.asia")) { it.isNotBlank() }) return null
        return try {
            val segments = URI(value).path.split('/').filter(String::isNotBlank)
            if (segments.size < 2) return null
            val comicId = segments[segments.size - 2]
            val chapterId = segments.last()
            if (!comicId.matches(Regex("""[A-Za-z0-9_-]{1,160}""")) || !chapterId.matches(Regex("""[A-Za-z0-9_-]{1,160}"""))) null
            else comicId to chapterId
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveSafeImage(value: String, base: String): String? {
        val candidate = value.trim()
        if (candidate.isBlank() || candidate.equals("null", ignoreCase = true)) return null
        return try {
            val image = URI(base).resolve(candidate)
            image.toString().takeIf(::isSafeReadingImageUrl)
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchJson(value: String): String? {
        val uri = try {
            URI(value)
        } catch (_: Exception) {
            return null
        }
        if (!isSafeReadingHttpUrl(value) || !uri.host.equals("01.komiku.asia", true) || uri.userInfo != null || (uri.port != -1 && uri.port != 443 && uri.port != 80)) return null
        return ReadingHttpTransport.getText(
            value,
            mapOf(
                "User-Agent" to "ShinigamiReadingRuntime/1.0",
                "Accept" to "application/json",
                "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8",
            ),
        )
    }
}


