package streamix.readingcatalog

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Native Shinigami adapter. Chapters are opaque API identifiers; the adapter never
 * treats a catalog-provided chapter value as a network URL. Image paths are assembled
 * from the API's baseUrl + chapterPage.path + pages payload.
 */
class ShinigamiReadingRuntimeProvider(
    private val jsonFetcher: ((String) -> String?)? = null
) : ReadingRuntimeProvider {
    override val id = "shinigami"
    private val apiBase = "https://api.shngm.io"

    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): Boolean =
        entry.kind == ReadingKind.MANGA && chapter.sources.any {
            it.enabled && it.providerId.equals(id, true) && chapterId(it.url) != null
        }

    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? =
        withContext(Dispatchers.IO) {
            if (entry.kind != ReadingKind.MANGA) return@withContext null
            val source = chapter.sources.firstOrNull {
                it.enabled && it.providerId.equals(id, true) && chapterId(it.url) != null
            } ?: return@withContext null
            val id = chapterId(source.url) ?: return@withContext null
            val raw = fetch("$apiBase/v1/chapter/detail/$id") ?: return@withContext null
            val root = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
                ?: return@withContext null
            val pageList = root.getAsJsonObject("pageList")
                ?: root.getAsJsonObject("data")?.getAsJsonObject("pageList")
                ?: root.getAsJsonObject("data")
                ?: return@withContext null
            val baseUrl = pageList.string("baseUrl") ?: return@withContext null
            val chapterPage = pageList.getAsJsonObject("chapterPage")
                ?: pageList.getAsJsonObject("chapter_page")
                ?: return@withContext null
            val path = chapterPage.string("path").orEmpty()
            val names = chapterPage.getAsJsonArray("pages")?.mapNotNull { item ->
                item.takeIf { it.isJsonPrimitive }?.asString?.takeIf(String::isNotBlank)
            }.orEmpty()
            val pages = names.mapNotNull { name ->
                safeImageUrl(baseUrl.trimEnd('/') + "/" + path.trim('/') + "/" + name.trimStart('/'))
            }.distinct()
            pages.takeIf { it.isNotEmpty() }?.let {
                ReadingContent(entry.kind, "reading-provider:${this@ShinigamiReadingRuntimeProvider.id}", contentUrl = source.url, pageUrls = it, format = "image-pages")
            }
        }

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun chapterId(value: String): String? {
        val candidate = if (value.startsWith("https://", true)) {
            runCatching {
                val uri = java.net.URI(value)
                val host = uri.host ?: return null
                val trustedHost = host.equals("shinigami.asia", true) || host.endsWith(".shinigami.asia", true)
                val safeAuthority = uri.scheme.equals("https", true) &&
                    uri.userInfo == null && (uri.port == -1 || uri.port == 443)
                if (trustedHost && safeAuthority && isSafeReadingHttpUrl(value)) {
                    uri.path.trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() }
                } else null
            }.getOrNull()
        } else value
        return candidate?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,160}")) }
    }

    private fun safeImageUrl(value: String): String? = runCatching {
        val uri = java.net.URI(value)
        value.takeIf(::isSafeReadingImageUrl)
    }.getOrNull()

    private fun fetch(url: String): String? {
        jsonFetcher?.let { return it(url) }
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", true) || !uri.host.equals("api.shngm.io", true) ||
            uri.userInfo != null || (uri.port != -1 && uri.port != 443)
        ) return null
        return ReadingHttpTransport.getText(
            url,
            mapOf(
                "Accept" to "application/json",
                "User-Agent" to "ShinigamiReadingRuntime/1.0",
                "DNT" to "1",
            ),
            followRedirects = false,
        )
    }
}
