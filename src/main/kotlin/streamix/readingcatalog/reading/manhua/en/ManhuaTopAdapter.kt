package streamix.readingcatalog

import com.google.gson.JsonParser
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * ManhuaTop uses a site-specific reader JSON endpoint and paginates chapter images.
 * This adapter only resolves the manually catalogued /manhua/{slug}/{chapter} route;
 * it does not attempt discovery or bypass upstream access controls.
 */
class ManhuaTopReadingRuntimeProvider(
    private val jsonFetcher: ((String) -> String?)? = null,
) : ReadingRuntimeProvider {
    override val id = "manhuatop"
    private val apiBase = "https://manhuatop.org"
    private val rateLock = Any()
    private var nextRequestNanos = 0L

    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): Boolean =
        entry.kind == ReadingKind.MANHUA && chapter.sources.any {
            it.enabled && it.providerId.equals(id, true) && validChapterUrl(it.url)
        }

    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? =
        withContext(Dispatchers.IO) {
            if (entry.kind != ReadingKind.MANHUA) return@withContext null
            val source = chapter.sources.firstOrNull {
                it.enabled && it.providerId.equals(id, true) && validChapterUrl(it.url)
            } ?: return@withContext null
            val uri = runCatching { URI(source.url) }.getOrNull() ?: return@withContext null
            val pages = mutableListOf<String>()
            for (pageNumber in 1..50) {
                awaitRequestSlot()
                val contentPath = uri.rawPath.trimEnd('/') + "/" +
                    if (pageNumber == 1) "" else "?content_page=$pageNumber"
                val apiUrl = "$apiBase/api/reader?resource=content&path=" +
                    URLEncoder.encode(contentPath, StandardCharsets.UTF_8.name())
                val body = try {
                    jsonFetcher?.invoke(apiUrl) ?: if (jsonFetcher == null) fetch(apiUrl) else null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                } ?: break
                val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: break
                val images = root.getAsJsonArray("images")?.mapNotNull { item ->
                    val obj = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                    val raw = obj.get("url")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                        ?.asString?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                    runCatching { URI(source.url).resolve(raw).toString() }.getOrNull()
                        ?.takeIf(::validImageUrl)
                }.orEmpty()
                pages.addAll(images)
                val hasMore = root.get("hasMore")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
                if (!hasMore || images.isEmpty()) break
            }
            pages.normalizedReadingPageUrls().takeIf { it.isNotEmpty() }?.let {
                ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages")
            }
        }

    private suspend fun awaitRequestSlot() {
        val waitNanos = synchronized(rateLock) {
            val now = System.nanoTime()
            val slot = maxOf(now, nextRequestNanos)
            nextRequestNanos = slot + 500_000_000L
            (slot - now).coerceAtLeast(0L)
        }
        if (waitNanos > 0L) delay((waitNanos + 999_999L) / 1_000_000L)
    }

    private fun validChapterUrl(value: String): Boolean = isSafeReadingChapterUrl(
        value,
        setOf("manhuatop.org", "www.manhuatop.org"),
    ) { path ->
        val parts = path.trim('/').split('/')
        parts.size >= 3 && parts[0].equals("manhua", true) &&
            parts.drop(1).all { it.isNotBlank() && it != "." && it != ".." }
    }

    private fun validImageUrl(value: String): Boolean = isSafeReadingImageUrl(value)

    private fun fetch(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", true) || !uri.host.equals("manhuatop.org", true) ||
            uri.userInfo != null || uri.port !in setOf(-1, 443) ||
            !uri.path.equals("/api/reader", true)
        ) return null
        return ReadingHttpTransport.getText(
            url,
            mapOf(
                "Accept" to "application/json",
                "User-Agent" to "ShinigamiReadingRuntime/1.0",
            ),
            followRedirects = false,
        )
    }
}
