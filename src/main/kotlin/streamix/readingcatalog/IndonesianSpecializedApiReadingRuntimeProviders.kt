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
            // PostgREST normally returns one row for an ID filter. Still skip malformed or
            // unusable rows instead of letting the first bad row mask a later usable result.
            (0 until rows.length()).asSequence()
                .mapNotNull { index -> rows.optJSONObject(index)?.opt("isigambar") }
                .mapNotNull { raw ->
                    when (raw) {
                        is JSONArray -> (0 until raw.length()).mapNotNull { index ->
                            raw.opt(index).takeIf { it is String }?.let { (it as String).trim() }
                                ?.takeIf(String::isNotBlank)
                                ?.takeIf(::validImage)
                        }
                        is String -> {
                            // The API has returned both a JSON-encoded array and a single image URL.
                            // Preserve the single-URL form instead of treating every non-array string
                            // as an empty chapter.
                            val value = raw.trim()
                            val parsedArray = runCatching { JSONArray(value) }.getOrNull()
                            if (parsedArray != null) {
                                (0 until parsedArray.length()).mapNotNull { index ->
                                    parsedArray.opt(index).takeIf { it is String }?.let { (it as String).trim() }
                                        ?.takeIf(String::isNotBlank)
                                        ?.takeIf(::validImage)
                                }
                            } else {
                                value.takeIf(::validImage)?.let(::listOf).orEmpty()
                            }
                        }
                        else -> emptyList()
                    }.normalizedReadingPageUrls().takeIf { it.isNotEmpty() }
                }
                .firstOrNull()
                .orEmpty()
        }.getOrNull().orEmpty().normalizedReadingPageUrls()
        images.takeIf { it.isNotEmpty() }?.let { ReadingContent(entry.kind, "reading-provider:riztranslation", source.url, it, format = "image-pages") }
    }
    private fun chapterId(value: String): String? {
        if (!isSafeReadingChapterUrl(value, setOf("riztranslation.pages.dev")) { it.isNotBlank() }) return null
        return runCatching {
            URI(value).path.trimEnd('/').substringAfterLast('/')
                .takeIf { s -> s.length in 1..160 && s.all(Char::isDigit) }
        }.getOrNull()
    }
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
            // Normalize each endpoint's candidate list before selecting a fallback. Otherwise a
            // non-empty list containing only unsafe/invalid URLs can block the usable fallback.
            runCatching { extractFirestoreImages(JSONObject(body), key).normalizedReadingPageUrls() }.getOrNull()
        }.firstOrNull { it.isNotEmpty() }.orEmpty()
        pages.takeIf { it.isNotEmpty() }?.let { ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages") }
    }
    private fun chapterParts(value: String): Pair<String, String>? = runCatching {
        if (!isSafeReadingChapterUrl(value, setOf("mikoroku.com")) { path ->
                val segments = path.split('/').filter(String::isNotBlank)
                val index = segments.indexOf("manga")
                index >= 0 && segments.getOrNull(index + 2) == "chapter"
            }) return@runCatching null
        val p = URI(value).path.split('/').filter(String::isNotBlank)
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
            return (0 until a.length()).mapNotNull { i ->
                (a.optJSONObject(i)?.opt("stringValue") as? String)?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?.takeIf(::validImage)
            }
        }
        // Prefer direct images only when they survive shared normalization. A non-empty
        // array may still contain only duplicate/unsafe entries; in that case inspect the
        // chapter-specific nested map instead of letting the unusable direct field mask it.
        val direct = list(fields.optJSONObject("images")).normalizedReadingPageUrls()
        if (direct.isNotEmpty()) return direct
        val nested = fields.optJSONObject("chapters")?.optJSONObject("mapValue")?.optJSONObject("fields")?.optJSONObject(key)?.optJSONObject("mapValue")?.optJSONObject("fields")
        return list(nested?.optJSONObject("images")).normalizedReadingPageUrls()
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
