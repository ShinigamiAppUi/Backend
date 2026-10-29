package streamix.readingcatalog

import com.google.gson.JsonParser
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/**
 * We Tried TLs serves novel chapter text from its dedicated API.
 * The Catalog supplies the public /series/{series}/{chapter} URL; the adapter
 * maps that slug pair to the documented chapter API and returns plain text.
 */
class WeTriedTlsReadingRuntimeProvider(
    private val textFetcher: ((String, Map<String, String>) -> String?)? = null,
) : ReadingRuntimeProvider {
    override val id = "wetriedtls"
    private val publicHosts = setOf("wetriedtls.com", "www.wetriedtls.com")
    private val apiHost = "api.wetriedtls.com"

    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): Boolean =
        entry.kind == ReadingKind.NOVEL && chapter.sources.any {
            it.enabled && it.providerId.equals(id, true) && chapterSlugs(it.url) != null
        }

    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? =
        withContext(Dispatchers.IO) {
            if (entry.kind != ReadingKind.NOVEL) return@withContext null
            val source = chapter.sources.firstOrNull {
                it.enabled && it.providerId.equals(id, true) && chapterSlugs(it.url) != null
            } ?: return@withContext null
            val slugs = chapterSlugs(source.url) ?: return@withContext null
            val apiUrl = "https://$apiHost/chapter/${slugs.first}/${slugs.second}"
            val headers = mapOf(
                "Accept" to "application/json",
                "Referer" to "https://wetriedtls.com/",
                "Origin" to "https://wetriedtls.com",
                "User-Agent" to "ShinigamiReadingRuntime/1.0",
            )
            val body = try {
                textFetcher?.invoke(apiUrl, headers) ?: if (textFetcher == null) fetch(apiUrl, headers) else null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: return@withContext null
            val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
                ?: return@withContext null
            val chapterObject = root.getAsJsonObject("chapter") ?: return@withContext null
            val raw = chapterObject.get("chapter_content")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString?.trim().orEmpty()
            val text = Jsoup.parseBodyFragment(raw).text().trim()
            text.takeIf(String::isNotBlank)?.let {
                ReadingContent(entry.kind, "reading-provider:$id", source.url, text = it, format = "text")
            }
        }

    private fun chapterSlugs(value: String): Pair<String, String>? = runCatching {
        val uri = URI(value)
        if (!uri.scheme.equals("https", true) ||
            uri.host?.lowercase() !in publicHosts ||
            uri.userInfo != null || uri.port !in setOf(-1, 443)
        ) return@runCatching null
        val parts = uri.path.trim('/').split('/')
        if (parts.size != 3 || !parts[0].equals("series", true)) return@runCatching null
        val series = parts[1].takeIf(::isSafeSlug) ?: return@runCatching null
        val chapter = parts[2].takeIf(::isSafeSlug) ?: return@runCatching null
        series to chapter
    }.getOrNull()

    private fun isSafeSlug(value: String): Boolean =
        value.isNotBlank() && value.matches(Regex("[A-Za-z0-9_-]{1,180}"))

    private fun fetch(url: String, headers: Map<String, String>): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", true) || !uri.host.equals(apiHost, true) ||
            uri.userInfo != null || uri.port !in setOf(-1, 443) ||
            !uri.path.matches(Regex("/chapter/[A-Za-z0-9_-]{1,180}/[A-Za-z0-9_-]{1,180}"))
        ) return null
        return ReadingHttpTransport.getText(url, headers, followRedirects = false)
    }
}
