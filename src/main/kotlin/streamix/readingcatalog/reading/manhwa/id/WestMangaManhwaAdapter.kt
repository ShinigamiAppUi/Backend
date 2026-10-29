package streamix.readingcatalog

import java.net.URI
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WestMangaManhwaReadingRuntimeProvider(private val responseFetcher: ((String, Map<String, String>) -> String?)? = null) : ReadingRuntimeProvider {
    override val id = "west-manga-manhwa"
    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) = entry.kind == ReadingKind.MANHWA && chapter.sources.any { it.enabled && it.providerId.equals(id, true) && chapterSlug(it.url) != null }
    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        if (entry.kind != ReadingKind.MANHWA) return@withContext null
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && chapterSlug(it.url) != null } ?: return@withContext null
        val slug = chapterSlug(source.url) ?: return@withContext null
        val apiUrl = "https://data.mantweh.online/api/v/$slug"
        val headers = signedHeaders(apiUrl)
        val body = try { responseFetcher?.invoke(apiUrl, headers) ?: if (responseFetcher == null) fetch(apiUrl, headers) else null } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { null } ?: return@withContext null
        val images = runCatching {
            val root = JSONObject(body)
            val data = root.optJSONObject("data") ?: root
            // Some responses expose the reader list at the root while also including a
            // partial/empty data object. Inspect both levels so that an unusable nested
            // payload cannot hide valid root-level pages.
            sequenceOf(root.optJSONArray("images"), data.optJSONArray("images"))
                .filterNotNull()
                .flatMap { arr ->
                    (0 until arr.length()).mapNotNull { index ->
                        arr.opt(index).takeIf { it is String }?.let { (it as String).trim() }
                            ?.takeIf(String::isNotBlank)
                            ?.takeIf(::validImage)
                    }
                }
                .toList()
        }.getOrNull().orEmpty().normalizedReadingPageUrls()
        images.takeIf { it.isNotEmpty() }?.let { ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages") }
    }
    private fun chapterSlug(value: String): String? = runCatching {
        URI(value).let { u ->
            val parts = u.path.split('/').filter(String::isNotBlank)
            // The API endpoint is keyed by the chapter slug, not the first route segment
            // (which may literally be "chapter" or a parent manga route).
            val slug = parts.lastOrNull()
            if (
                isSafeReadingChapterUrl(value, setOf("v1.westmanga.my")) {
                    it.split('/').filter(String::isNotBlank).let { segments ->
                        segments.size >= 2 && segments.any { segment -> segment.contains("chapter", true) }
                    }
                } &&
                slug != null &&
                !slug.equals("chapter", ignoreCase = true) &&
                slug.matches(Regex("[A-Za-z0-9_-]{1,160}"))
            ) slug else null
        }
    }.getOrNull()
    private fun signedHeaders(value: String): Map<String, String> {
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val accessKey = "WM_WEB_FRONT_END"
        val key = timestamp + "GET" + URI(value).rawPath + accessKey + "xxxoidj"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signature = mac.doFinal("wm-api-request".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return mapOf("x-wm-request-time" to timestamp, "x-wm-accses-key" to accessKey, "x-wm-request-signature" to signature, "User-Agent" to "ShinigamiReadingRuntime/1.0", "Accept" to "application/json")
    }
    private fun validImage(value: String) = isSafeReadingImageUrl(value)
    private fun fetch(value: String, headers: Map<String, String>): String? =
        ReadingHttpTransport.getText(value, headers)
}
