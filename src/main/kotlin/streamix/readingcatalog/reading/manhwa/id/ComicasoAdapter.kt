package streamix.readingcatalog

import java.net.URI
import java.net.URLEncoder
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ComicasoReadingRuntimeProvider(private val responseFetcher: ((String) -> String?)? = null) : ReadingRuntimeProvider {
    override val id = "comicaso"

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
        val apiUrl = "https://v3.comicaso.pro/api/chapter.php?source=${enc(parts[0])}" +
            "&manga=${enc(parts[1])}&chapter=${enc(parts[2])}&token=${enc(parts[3])}"
        val body = runCatching {
            if (responseFetcher != null) responseFetcher.invoke(apiUrl) else fetchText(apiUrl, "application/json")
        }.getOrNull() ?: return@withContext null
        val pages = runCatching {
            val root = JSONObject(body)
            val data = root.optJSONObject("data") ?: root
            val images = data.optJSONArray("images") ?: data.optJSONArray("pages")
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

    private fun chapterParts(value: String): List<String>? = runCatching {
        val uri = URI(value)
        if (!uri.scheme.equals("https", true) || !uri.host.equals("v3.comicaso.pro", true) || uri.userInfo != null || (uri.port != -1 && uri.port != 443)) return@runCatching null
        val path = uri.path.split('/').filter(String::isNotBlank)
        val token = uri.rawQuery?.split('&')
            ?.firstOrNull { it.substringBefore('=') == "token" }
            ?.substringAfter('=', "")
            ?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8) }
        if (path.size < 3 || token.isNullOrBlank()) null
        else listOf(path[0], path[1], path[2], token)
    }.getOrNull()

    private fun resolveSafeImage(value: String, base: String): String? = runCatching {
        val uri = URI(base).resolve(value)
        uri.toString().takeIf(::isSafeReadingImageUrl)
    }.getOrNull()

    private fun enc(value: String) = URLEncoder.encode(value, Charsets.UTF_8)
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
