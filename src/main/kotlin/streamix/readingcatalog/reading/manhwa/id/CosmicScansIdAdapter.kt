package streamix.readingcatalog

import java.net.URI
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CosmicScansIdReadingRuntimeProvider(private val responseFetcher: ((String) -> String?)? = null) : ReadingRuntimeProvider {
    override val id = "cosmicscans"

    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) =
        entry.kind == ReadingKind.MANHWA && chapter.sources.any {
            it.enabled && it.providerId.equals(id, true) && chapterIdentity(it.url) != null
        }

    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        if (entry.kind != ReadingKind.MANHWA) return@withContext null
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && chapterIdentity(it.url) != null }
            ?: return@withContext null
        val chapterId = chapterIdentity(source.url) ?: return@withContext null
        val apiUrl = "https://cdncid.csmcscns.id/v1/manga/readingPage/$chapterId"
        val body = try { if (responseFetcher != null) responseFetcher.invoke(apiUrl) else fetchJson(apiUrl) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null } ?: return@withContext null
        val pages = runCatching {
            val root = JSONObject(body)
            val data = root.optJSONObject("data") ?: root
            if (data.optString("redirectLink").isNotBlank() || data.optString("redirect_link").isNotBlank()) return@runCatching emptyList<String>()
            val chapters = data.optJSONArray("chapters") ?: data.optJSONArray("pages")
                ?: return@runCatching emptyList<String>()
            (0 until chapters.length()).flatMap { index ->
                val item = chapters.opt(index)
                when (item) {
                    is JSONObject -> sequenceOf("url", "src", "html", "content").map { item.optString(it) }
                        .filter(String::isNotBlank).firstNotNullOfOrNull { value -> extractImages(value, apiUrl).takeIf { images -> images.isNotEmpty() } }.orEmpty()
                    else -> item?.toString()?.let { extractImages(it, apiUrl) }.orEmpty()
                }
            }
        }.getOrNull().orEmpty().distinct()
        pages.takeIf { it.isNotEmpty() }?.let {
            ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages")
        }
    }

    private fun chapterIdentity(value: String): String? = runCatching {
        val uri = URI(value)
        if (!isSafeReadingHttpUrl(value) || !uri.host.equals("02.cosmicscans.to", true)) return@runCatching null
        val parts = uri.path.trimEnd('/').split('/').filter(String::isNotBlank)
        val chapterIndex = parts.indexOfFirst { it.equals("chapter", true) || it.equals("chapters", true) }
        when {
            chapterIndex >= 0 && chapterIndex + 1 < parts.size -> parts[chapterIndex + 1]
            parts.lastOrNull()?.matches(Regex("""[A-Za-z0-9_-]+""")) == true -> parts.last()
            else -> null
        }
    }.getOrNull()

    private fun extractImages(value: String, base: String): List<String> {
        if (value.trimStart().startsWith("<")) {
            return Jsoup.parse(value, base).select("img").mapNotNull { image ->
                sequenceOf("data-src", "data-lazy-src", "data-original", "src").map { image.attr(it).trim() }
                    .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
                    ?.let { raw -> runCatching { URI(base).resolve(raw).toString() }.getOrNull() }
                    ?.takeIf(::isSafeReadingImageUrl)
            }
        }
        val raw = runCatching {
            val json = runCatching { JSONObject(value) }.getOrNull()
            json?.let { sequenceOf("url", "src", "image").map { key -> it.optString(key) }.firstOrNull(String::isNotBlank) }
                ?: value.takeIf { it.startsWith("https://") }
        }.getOrNull() ?: return emptyList()
        return runCatching {
            URI(base).resolve(raw).toString().takeIf(::isSafeReadingImageUrl)?.let(::listOf).orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun fetchJson(value: String): String? {
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (!isSafeReadingHttpUrl(value) || !uri.host.equals("cdncid.csmcscns.id", true)) return null
        return ReadingHttpTransport.getText(
            value,
            mapOf(
                "User-Agent" to "ShinigamiReadingRuntime/1.0",
                "Accept" to "application/json",
            ),
            followRedirects = false,
        )
    }
}
