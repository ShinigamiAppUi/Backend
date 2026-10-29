package streamix.readingcatalog

import java.net.URI
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AinzScansIdReadingRuntimeProvider(private val responseFetcher: ((String) -> String?)? = null) : ReadingRuntimeProvider {
    override val id = "ainz-scans-id"
    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) = entry.kind == ReadingKind.MANHWA && chapter.sources.any { it.enabled && it.providerId.equals(id, true) && parts(it.url, "v3.ainzscans01.com") != null }
    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && parts(it.url, "v3.ainzscans01.com") != null } ?: return@withContext null
        val (series, slug) = parts(source.url, "v3.ainzscans01.com") ?: return@withContext null
        loneSealPages(entry.kind, source.url, "https://api.ainzscans01.com/api/series/comic/$series/chapter/$slug", id, responseFetcher)
    }
}

private fun parts(value: String, expectedHost: String): Pair<String, String>? = runCatching {
    val u = URI(value)
    if (!isSafeReadingHttpUrl(value) || !u.host.equals(expectedHost, true)) return@runCatching null
    val path = u.path.split('/').filter(String::isNotBlank)
    val chapterIndex = path.indexOf("chapter")
    if (chapterIndex > 0 && chapterIndex + 1 < path.size) path[chapterIndex - 1] to path[chapterIndex + 1]
    else if (path.size >= 3) path[path.size - 2] to path.last() else null
}.getOrNull()

private suspend fun loneSealPages(kind: ReadingKind, sourceUrl: String, apiUrl: String, providerId: String, responseFetcher: ((String) -> String?)?): ReadingContent? {
    val body = runCatching { responseFetcher?.invoke(apiUrl) ?: if (responseFetcher == null) fetchLoneSealJson(apiUrl) else null }.getOrNull() ?: return null
    val pages = runCatching {
        val root = JSONObject(body)
        val chapter = root.optJSONObject("chapter") ?: root.optJSONObject("data")?.optJSONObject("chapter") ?: root.optJSONObject("data") ?: root
        val arr = chapter.optJSONArray("pages") ?: return@runCatching emptyList<String>()
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.optString("image_url")?.takeIf(String::isNotBlank)?.takeIf(::validLoneSealImage)
        }
    }.getOrNull().orEmpty().distinct()
    return pages.takeIf { it.isNotEmpty() }?.let { ReadingContent(kind, "reading-provider:$providerId", sourceUrl, it, format = "image-pages") }
}

private fun validLoneSealImage(value: String) = isSafeReadingImageUrl(value)
private fun fetchLoneSealJson(value: String): String? = ReadingHttpTransport.getText(
    value,
    mapOf(
        "User-Agent" to "ShinigamiReadingRuntime/1.0",
        "Accept" to "application/json",
    ),
)
