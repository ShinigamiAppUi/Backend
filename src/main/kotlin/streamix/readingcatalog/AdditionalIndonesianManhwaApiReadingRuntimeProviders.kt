package streamix.readingcatalog

import java.net.URI
import java.net.URLEncoder
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * VoraToon source URLs contain both series slug and chapter ID. The reader API
 * response is intentionally parsed as JSON instead of treating the response as HTML.
 */
class VoraToonReadingRuntimeProvider(private val responseFetcher: ((String) -> String?)? = null) : ReadingRuntimeProvider {
    override val id = "voratoon"

    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) =
        entry.kind == ReadingKind.MANHWA && chapter.sources.any {
            it.enabled && it.providerId.equals(id, true) && chapterParts(it.url) != null
        }

    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        val source = chapter.sources.firstOrNull {
            it.enabled && it.providerId.equals(id, true) && chapterParts(it.url) != null
        } ?: return@withContext null
        val parts = chapterParts(source.url) ?: return@withContext null
        val apiUrl = "https://v9.voratoon.com/api/proxy/series/${parts.first}/chapters/${parts.second}"
        val body = runCatching {
            if (responseFetcher != null) responseFetcher.invoke(apiUrl) else ReadingHttpTransport.getTextWithCookieBootstrap(apiUrl, "https://v9.voratoon.com/", mapOf("User-Agent" to "ShinigamiReadingRuntime/1.0", "Accept" to "application/json"))
        }.getOrNull() ?: return@withContext null
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

/**
 * Comicaso chapter links carry a token query parameter. Preserve the token only
 * in the upstream API request and never use the supplied URI as an arbitrary host.
 */
class ComicasoReadingRuntimeProvider(private val responseFetcher: ((String) -> String?)? = null) : ReadingRuntimeProvider {
    override val id = "comicaso"

    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) =
        entry.kind == ReadingKind.MANHWA && chapter.sources.any {
            it.enabled && it.providerId.equals(id, true) && chapterParts(it.url) != null
        }

    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
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

class TheMangaReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "the-manga"

    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) =
        entry.kind == ReadingKind.MANHWA && chapter.sources.any {
            it.enabled && it.providerId.equals(id, true) && validChapter(it.url)
        }

    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        val source = chapter.sources.firstOrNull {
            it.enabled && it.providerId.equals(id, true) && validChapter(it.url)
        } ?: return@withContext null
        val doc = runCatching {
            if (documentFetcher != null) documentFetcher.invoke(source.url) else fetchDoc(source.url)
        }.getOrNull() ?: return@withContext null
        val pages = doc.select("img.page-img").mapNotNull { image ->
            sequenceOf("data-src", "data-lazy-src", "data-original", "src").map { image.attr(it).trim() }
                .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
                ?.let { resolveSafeImage(it, source.url) }
        }.filterNotNull().distinct()
        pages.takeIf { it.isNotEmpty() }?.let {
            ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages")
        }
    }

    private fun validChapter(value: String) = runCatching {
        URI(value).let { isSafeReadingHttpUrl(value) && it.host.equals("themanga.site", true) && it.path.contains("chapter", true) }
    }.getOrDefault(false)

    private fun resolveSafeImage(value: String, base: String): String? = runCatching {
        val uri = URI(base).resolve(value)
        uri.toString().takeIf(::isSafeReadingImageUrl)
    }.getOrNull()
}

class UlasComicReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "ulas-comic"

    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) =
        entry.kind != ReadingKind.NOVEL && chapter.sources.any {
            it.enabled && it.providerId.equals(id, true) && validChapter(it.url)
        }

    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        val source = chapter.sources.firstOrNull {
            it.enabled && it.providerId.equals(id, true) && validChapter(it.url)
        } ?: return@withContext null
        val doc = runCatching {
            if (documentFetcher != null) documentFetcher.invoke(source.url) else fetchDoc(source.url)
        }.getOrNull() ?: return@withContext null
        val direct = doc.select("div.check-box div.separator img, article div.separator img, .readerarea img")
            .mapNotNull { image ->
                sequenceOf("data-src", "data-lazy-src", "data-original", "src").map { image.attr(it).trim() }
                    .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
                    ?.let { resolveSafeImage(it, source.url) }
            }
        val scripts = doc.select("script").flatMap { script ->
            Regex("""https?://[^\\"'\\s]+""").findAll(script.data()).map { it.value.trimEnd(',', ')') }.toList()
        }.mapNotNull { resolveSafeImage(it, source.url) }
        (direct + scripts).distinct().takeIf { it.isNotEmpty() }?.let {
            ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages")
        }
    }

    private fun validChapter(value: String) = runCatching {
        URI(value).let { isSafeReadingHttpUrl(value) && it.host.equals("www.ulascomic01.xyz", true) && it.path.isNotBlank() }
    }.getOrDefault(false)

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
