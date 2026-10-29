package streamix.readingcatalog

import java.net.URI
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WestMangaReadingRuntimeProvider(private val responseFetcher: ((String, Map<String, String>) -> String?)? = null) : ReadingRuntimeProvider {
    override val id = "west-manga"
    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) = entry.kind == ReadingKind.MANGA && chapter.sources.any { it.enabled && it.providerId.equals(id, true) && chapterSlug(it.url) != null }
    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        if (entry.kind != ReadingKind.MANGA) return@withContext null
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && chapterSlug(it.url) != null } ?: return@withContext null
        val slug = chapterSlug(source.url) ?: return@withContext null
        val apiUrl = "https://data.mantweh.online/api/v/$slug"
        val headers = signedHeaders(apiUrl)
        val body = try { responseFetcher?.invoke(apiUrl, headers) ?: if (responseFetcher == null) fetch(apiUrl, headers) else null } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { null } ?: return@withContext null
        val images = runCatching {
            val data = JSONObject(body).optJSONObject("data") ?: JSONObject(body)
            val arr = data.optJSONArray("images") ?: return@runCatching emptyList<String>()
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { u -> u.isNotBlank() }?.takeIf(::validImage) }
        }.getOrNull().orEmpty().normalizedReadingPageUrls()
        images.takeIf { it.isNotEmpty() }?.let { ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages") }
    }
    private fun chapterSlug(value: String): String? = runCatching {
        URI(value).let { u ->
            val parts = u.path.split('/').filter(String::isNotBlank)
            val slug = parts.firstOrNull()
            if (
                isSafeReadingHttpUrl(value) &&
                u.userInfo == null && (u.port == -1 || u.port == 443 || u.port == 80) &&
                u.host.equals("v1.westmanga.my", true) &&
                parts.size >= 2 &&
                parts.any { it.contains("chapter", true) } &&
                slug != null &&
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

class RiztranslationReadingRuntimeProvider(private val responseFetcher: ((String, Map<String, String>) -> String?)? = null) : ReadingRuntimeProvider {
    override val id = "riztranslation"
    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) = entry.kind == ReadingKind.MANGA && chapter.sources.any { it.enabled && it.providerId.equals(id, true) && chapterId(it.url) != null }
    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        if (entry.kind != ReadingKind.MANGA) return@withContext null
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && chapterId(it.url) != null } ?: return@withContext null
        val chapterId = chapterId(source.url) ?: return@withContext null
        val apiUrl = "https://uefnaojxivvxeamljskn.supabase.co/rest/v1/Chapter?select=id,bookId,isigambar&id=eq.$chapterId"
        val headers = mapOf("apikey" to SUPABASE_ANON_KEY, "Accept" to "application/json", "User-Agent" to "ShinigamiReadingRuntime/1.0")
        val body = try { responseFetcher?.invoke(apiUrl, headers) ?: if (responseFetcher == null) fetch(apiUrl, headers) else null } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { null } ?: return@withContext null
        val images = runCatching {
            val rows = JSONArray(body)
            if (rows.length() == 0) return@runCatching emptyList<String>()
            val raw = rows.getJSONObject(0).opt("isigambar")
            when (raw) {
                is JSONArray -> (0 until raw.length()).mapNotNull { raw.optString(it).takeIf { u -> u.isNotBlank() }?.takeIf(::validImage) }
                is String -> runCatching { JSONArray(raw).let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { u -> u.isNotBlank() }?.takeIf(::validImage) } } }.getOrDefault(emptyList())
                else -> emptyList()
            }
        }.getOrNull().orEmpty().normalizedReadingPageUrls()
        images.takeIf { it.isNotEmpty() }?.let { ReadingContent(entry.kind, "reading-provider:riztranslation", source.url, it, format = "image-pages") }
    }
    private fun chapterId(value: String): String? = runCatching { URI(value).let { if (isSafeReadingHttpUrl(value) && it.host.equals("riztranslation.pages.dev", true) && it.userInfo == null && (it.port == -1 || it.port == 443 || it.port == 80)) it.path.trimEnd('/').substringAfterLast('/').takeIf { s -> s.length in 1..160 && s.all(Char::isDigit) } else null } }.getOrNull()
    private fun validImage(value: String) = isSafeReadingImageUrl(value)
    private fun fetch(value: String, headers: Map<String, String>): String? =
        ReadingHttpTransport.getText(value, headers)
    companion object {
        private const val SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InVlZm5hb2p4aXZ2eGVhbWxqc2tuIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NDc3MTU5MjksImV4cCI6MjA2MzI5MTkyOX0._lEBN5puTvATwtYodg4zbcoTwg0ss3j2BebD8WoHt9A"
    }
}

class MikoRokuReadingRuntimeProvider(private val responseFetcher: ((String) -> String?)? = null) : ReadingRuntimeProvider {
    override val id = "mikoroku"
    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) = entry.kind == ReadingKind.MANGA && chapter.sources.any { it.enabled && it.providerId.equals(id, true) && chapterParts(it.url) != null }
    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        if (entry.kind != ReadingKind.MANGA) return@withContext null
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && chapterParts(it.url) != null } ?: return@withContext null
        val (slug, key) = chapterParts(source.url) ?: return@withContext null
        val urls = listOf("https://firestore.googleapis.com/v1/projects/mikoroku/databases/(default)/documents/manga/$slug/chapters/$key", "https://firestore.googleapis.com/v1/projects/mikoroku/databases/(default)/documents/manga/$slug")
        val pages = urls.asSequence().mapNotNull { api ->
            try {
                responseFetcher?.invoke(api) ?: if (responseFetcher == null) fetch(api) else null
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }.mapNotNull { body ->
            runCatching { extractFirestoreImages(JSONObject(body), key) }.getOrNull()
        }.firstOrNull { it.isNotEmpty() }.orEmpty().normalizedReadingPageUrls()
        pages.takeIf { it.isNotEmpty() }?.let { ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages") }
    }
    private fun chapterParts(value: String): Pair<String, String>? = runCatching {
        val u = URI(value)
        if (!isSafeReadingHttpUrl(value) || !u.host.equals("mikoroku.com", true) || u.userInfo != null || (u.port != -1 && u.port != 443 && u.port != 80)) return@runCatching null
        val p = u.path.split('/').filter(String::isNotBlank)
        val i = p.indexOf("manga")
        if (i < 0 || p.getOrNull(i + 2) != "chapter") return@runCatching null
        p.getOrNull(i + 1)
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,160}")) }
            ?.let { slug ->
                p.getOrNull(i + 3)
                    ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,160}")) }
                    ?.let { key -> slug to key }
            }
    }.getOrNull()
    private fun extractFirestoreImages(root: JSONObject, key: String): List<String> {
        val fields = root.optJSONObject("fields") ?: return emptyList()
        fun list(value: JSONObject?): List<String> {
            val a = value?.optJSONObject("arrayValue")?.optJSONArray("values") ?: return emptyList()
            return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.optString("stringValue")?.takeIf(::validImage) }
        }
        val direct = list(fields.optJSONObject("images"))
        if (direct.isNotEmpty()) return direct
        val nested = fields.optJSONObject("chapters")?.optJSONObject("mapValue")?.optJSONObject("fields")?.optJSONObject(key)?.optJSONObject("mapValue")?.optJSONObject("fields")
        return list(nested?.optJSONObject("images"))
    }
    private fun validImage(value: String) = isSafeReadingImageUrl(value)
    private fun fetch(value: String): String? = ReadingHttpTransport.getText(
        value,
        mapOf(
            "User-Agent" to "ShinigamiReadingRuntime/1.0",
            "Accept" to "application/json",
        ),
    )
}
