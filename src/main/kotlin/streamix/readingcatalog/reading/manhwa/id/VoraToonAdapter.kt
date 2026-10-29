package streamix.readingcatalog

import java.net.URI
import java.net.URLEncoder
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VoraToonReadingRuntimeProvider(private val responseFetcher: ((String) -> String?)? = null) : ReadingRuntimeProvider {
    override val id = "voratoon"

    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) =
        entry.kind == ReadingKind.MANHWA && chapter.sources.any {
            it.enabled && it.providerId.equals(id, true) && chapterParts(it.url) != null
        }

    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        if (entry.kind != ReadingKind.MANHWA) return@withContext null
        val source = chapter.sources.firstOrNull {
            it.enabled && it.providerId.equals(id, true) && chapterParts(it.url) != null
        } ?: return@withContext null
        val parts = chapterParts(source.url) ?: return@withContext null
        val apiUrl = "https://v9.voratoon.com/api/proxy/series/${parts.first}/chapters/${parts.second}"
        val body = try {
            if (responseFetcher != null) responseFetcher.invoke(apiUrl) else ReadingHttpTransport.getTextWithCookieBootstrap(apiUrl, "https://v9.voratoon.com/", mapOf("User-Agent" to "ShinigamiReadingRuntime/1.0", "Accept" to "application/json"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return@withContext null
        val pages = runCatching {
            val root = JSONObject(body)
            val data = root.optJSONObject("data") ?: root
            val nested = data.optJSONObject("data") ?: data
            val images = nested.optJSONArray("images") ?: nested.optJSONArray("pages")
                ?: return@runCatching emptyList<String>()
            (0 until images.length()).mapNotNull { index ->
                val item = images.opt(index)
                val candidates = when (item) {
                    is JSONObject -> sequenceOf("url", "src", "image").map { item.optString(it) }
                    else -> sequenceOf(item?.toString().orEmpty())
                }
                candidates.mapNotNull { value ->
                    value.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                        ?.let { resolveSafeImage(it, apiUrl) }
                }.firstOrNull()
            }
        }.getOrNull().orEmpty().distinct()
        pages.takeIf { it.isNotEmpty() }?.let {
            ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages")
        }
    }

    private fun chapterParts(value: String): Pair<String, String>? = runCatching {
        val uri = URI(value)
        if (!isSafeReadingHttpUrl(value) || !(uri.host.equals("v1.voratoon.com", true) || uri.host.equals("v9.voratoon.com", true))) return@runCatching null
        val segments = uri.path.split('/').filter(String::isNotBlank)
        val chapterIndex = segments.indexOf("chapter")
        val parts = when {
            chapterIndex > 0 && chapterIndex + 1 < segments.size ->
                segments[chapterIndex - 1] to segments[chapterIndex + 1]
            segments.size >= 4 && segments[0] == "series" -> segments[1] to segments[3]
            else -> null
        } ?: return@runCatching null
        fun safeSegment(segment: String) = segment.matches(Regex("[A-Za-z0-9_-]+")) && segment != "." && segment != ".."
        parts.takeIf { safeSegment(it.first) && safeSegment(it.second) }
    }.getOrNull()

    private fun resolveSafeImage(value: String, base: String): String? = runCatching {
        val uri = URI(base).resolve(value)
        uri.toString().takeIf(::isSafeReadingImageUrl)
    }.getOrNull()
}

private fun fetchText(value: String, accept: String): String? {
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    if (!isSafeReadingHttpUrl(value)) return null
    return ReadingHttpTransport.getText(
        value,
        mapOf(
            "User-Agent" to "ShinigamiReadingRuntime/1.0",
            "Accept" to accept,
        ),
    )
}

private fun fetchDoc(value: String): Document? {
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    if (!isSafeReadingHttpUrl(value)) return null
    return fetchReadingHtmlDocument(
        value,
        mapOf(
            "User-Agent" to "ShinigamiReadingRuntime/1.0",
            "Accept" to "text/html,application/xhtml+xml",
        ),
    )
}
