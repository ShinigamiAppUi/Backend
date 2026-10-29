package streamix.readingcatalog

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * MangaFire's reader is backed by its chapter-pages API, matching the
 * site-specific flow documented by the Keiyoushi MangaFire extension.
 */
class MangaFireReadingRuntimeProvider(
    private val jsonFetcher: ((String) -> String?)? = null
) : ReadingRuntimeProvider {
    override val id = "mangafire"

    // Keiyoushi limits MangaFire to two requests per second. Pace this adapter's
    // requests so concurrent chapter fallbacks do not burst the upstream API.
    private val requestRateLock = Any()
    private var nextRequestAtNanos: Long = 0L

    private suspend fun awaitRequestSlot() {
        val waitNanos = synchronized(requestRateLock) {
            val now = System.nanoTime()
            val slot = maxOf(now, nextRequestAtNanos)
            nextRequestAtNanos = slot + 500_000_000L
            (slot - now).coerceAtLeast(0L)
        }
        if (waitNanos > 0L) delay((waitNanos + 999_999L) / 1_000_000L)
    }

    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): Boolean =
        entry.kind != ReadingKind.NOVEL && chapter.sources.any {
            it.enabled && it.providerId.equals(id, true) && chapterId(it.url) != null
        }

    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? =
        withContext(Dispatchers.IO) {
            if (entry.kind == ReadingKind.NOVEL) return@withContext null
            val source = chapter.sources.firstOrNull {
                it.enabled && it.providerId.equals(id, true) && chapterId(it.url) != null
            } ?: return@withContext null
            val chapterId = chapterId(source.url) ?: return@withContext null
            val apiPath = "/api/chapters/$chapterId"
            val signedUrl = "https://mangafire.to$apiPath?vrf=${MangaFireVrfSigner.sign(apiPath.removePrefix("/api"))}"
            awaitRequestSlot()
            val json = fetch(signedUrl) ?: return@withContext null
            val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()
                ?: return@withContext null
            val data = root.getAsJsonObject("data") ?: root
            val pages = data.getAsJsonArray("pages")?.mapNotNull {
                it.takeIf { v -> v.isJsonObject }?.asJsonObject?.get("url")
                    ?.takeIf { v -> v.isJsonPrimitive }?.asString
            }?.filter(::validPageUrl).orEmpty()
            pages.takeIf { it.isNotEmpty() }?.let {
                ReadingContent(entry.kind, "reading-provider:$id", contentUrl = source.url, pageUrls = it, format = "image-pages")
            }
        }

    private fun chapterId(value: String): String? {
        if (!isSafeReadingChapterUrl(value, setOf("mangafire.to", "www.mangafire.to")) { path ->
                Regex("/\\d+-chapter(?:-|/|$)").containsMatchIn(path)
            }) return null
        return Regex("/(\\d+)-chapter(?:-|/|$)")
            .find(java.net.URI(value).path)?.groupValues?.get(1)
    }

    private fun validPageUrl(value: String): Boolean = isSafeReadingImageUrl(value)

    private fun fetch(url: String): String? = jsonFetcher?.invoke(url) ?: if (jsonFetcher == null) {
        ReadingHttpTransport.getText(
            url,
            mapOf(
                "Accept" to "application/json",
                "User-Agent" to "ShinigamiReadingRuntime/1.0",
            ),
        )
    } else null
}
