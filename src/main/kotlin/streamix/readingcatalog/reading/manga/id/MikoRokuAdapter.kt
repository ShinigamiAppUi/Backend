package streamix.readingcatalog

import java.net.URI
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    private fun chapterParts(value: String): Pair<String, String>? = try {
        if (!isSafeReadingChapterUrl(value, setOf("mikoroku.com")) { path ->
                val segments = path.split('/').filter(String::isNotBlank)
                val index = segments.indexOf("manga")
                index >= 0 &&
                    segments.size == index + 4 &&
                    segments.getOrNull(index + 2) == "chapter"
            }) return null
        val p = URI(value).path.split('/').filter(String::isNotBlank)
        val i = p.indexOf("manga")
        if (i < 0 || p.size != i + 4 || p.getOrNull(i + 2) != "chapter") return null
        p.getOrNull(i + 1)
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,160}")) }
            ?.let { slug ->
                p.getOrNull(i + 3)
                    ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,160}")) }
                    ?.let { key -> slug to key }
            }
    } catch (_: Exception) {
        null
    }
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
