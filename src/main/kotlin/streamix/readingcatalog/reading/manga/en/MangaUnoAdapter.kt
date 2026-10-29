package streamix.readingcatalog

import com.google.gson.JsonParser
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Manga.uno chapter API adapter. The chapter ID comes only from a validated /r/ catalog URL. */
class MangaUnoReadingRuntimeProvider(private val jsonFetcher: ((String) -> String?)? = null) : ReadingRuntimeProvider {
    override val id = "manga-uno"

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
            val body = try {
                jsonFetcher?.invoke("https://manga.uno/api/chapter/$id")
                    ?: if (jsonFetcher == null) fetch("https://manga.uno/api/chapter/$id") else null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: return@withContext null
            val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
                ?: return@withContext null
            val pages = root.getAsJsonArray("pages")?.mapNotNull { item ->
                if (!item.isJsonPrimitive || !item.asJsonPrimitive.isString) return@mapNotNull null
                val raw = item.asString.trim()
                if (raw.isBlank() || raw.startsWith("data:", true)) return@mapNotNull null
                val candidate = runCatching {
                    if (raw.startsWith("/")) URI("https://xz7.fstr-cdn.com").resolve(raw).toString()
                    else URI(source.url).resolve(raw).toString()
                }.getOrNull()
                candidate?.takeIf(::validImageUrl)
            }.orEmpty().normalizedReadingPageUrls()
            pages.takeIf { it.isNotEmpty() }?.let {
                ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages")
            }
        }

    private fun chapterId(value: String): String? = runCatching {
        val uri = URI(value)
        if (!isSafeReadingChapterUrl(value, setOf("manga.uno", "www.manga.uno")) { path ->
                val parts = path.trim('/').split('/')
                parts.size == 3 && parts[0] == "r" && parts[1].isNotBlank() && parts[2].matches(Regex("[0-9]{1,20}"))
            }) null
        else uri.path.trimEnd('/').substringAfterLast('/').takeIf { it.matches(Regex("[0-9]{1,20}")) }
    }.getOrNull()

    private fun validImageUrl(value: String) = isSafeReadingImageUrl(value)

    private fun fetch(url: String): String? = ReadingHttpTransport.getText(
        url,
        mapOf("Accept" to "application/json", "User-Agent" to "ShinigamiReadingRuntime/1.0"),
        followRedirects = false,
    )
}
