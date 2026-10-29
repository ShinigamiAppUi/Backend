package streamix.readingcatalog

import java.net.URI
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        return try {
            URI(value).path.trimEnd('/').substringAfterLast('/')
                .takeIf { s -> s.length in 1..160 && s.all(Char::isDigit) }
        } catch (_: Exception) {
            null
        }
    }
    private fun validImage(value: String) = isSafeReadingImageUrl(value)
    private fun fetch(value: String, headers: Map<String, String>): String? =
        ReadingHttpTransport.getText(value, headers)
    companion object {
        private const val SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InVlZm5hb2p4aXZ2eGVhbWxqc2tuIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NDc3MTU5MjksImV4cCI6MjA2MzI5MTkyOX0._lEBN5puTvATwtYodg4zbcoTwg0ss3j2BebD8WoHt9A"
    }
}
