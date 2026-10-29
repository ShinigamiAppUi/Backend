package streamix.readingcatalog

import java.net.URI
import org.json.JSONObject
import org.jsoup.nodes.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Komiku.asia's chapter-details API uses the comic slug and chapter id from the source URL. */
class KomikuAsiaReadingRuntimeProvider(private val responseFetcher: ((String) -> String?)? = null) : ReadingRuntimeProvider {
    override val id = "komiku-asia"

    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) =
        entry.kind == ReadingKind.MANGA && chapter.sources.any {
            it.enabled && it.providerId.equals(id, true) && chapterPath(it.url) != null
        }

    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        if (entry.kind != ReadingKind.MANGA) return@withContext null
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && chapterPath(it.url) != null }
            ?: return@withContext null
        val (comicId, chapterId) = chapterPath(source.url) ?: return@withContext null
        val apiUrl = "https://01.komiku.asia/api/v2/comics/$comicId/chapters/id/$chapterId"
        val body = try {
            if (responseFetcher != null) responseFetcher.invoke(apiUrl) else fetchJson(apiUrl)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return@withContext null
        val pages = runCatching {
            val root = JSONObject(body)
            val data = root.optJSONObject("data") ?: root
            val chapterData = data.optJSONObject("chapter") ?: data
            sequenceOf(root.optJSONArray("pages"), data.optJSONArray("pages"), chapterData.optJSONArray("pages"))
                .filterNotNull()
                // Payloads can expose overlapping or partial page lists at multiple nesting levels.
                // Merge all candidates; normalizedReadingPageUrls() below removes duplicates and unsafe URLs.
                .flatMap { pageArray ->
                    (0 until pageArray.length()).mapNotNull { index ->
                        val item = pageArray.opt(index)
                        when (item) {
                            is JSONObject -> sequenceOf("url", "src", "image", "image_url")
                                .mapNotNull { key ->
                                    item.optString(key)
                                        .takeIf { it.isNotBlank() && it != "null" }
                                        ?.let { resolveSafeImage(it, apiUrl) }
                                }
                                .firstOrNull()
                            is String -> item.takeIf { it.isNotBlank() && it != "null" }?.let { resolveSafeImage(it, apiUrl) }
                            else -> null
                        }
                    }
                }
                .toList()
        }.getOrNull().orEmpty().filter(String::isNotBlank).normalizedReadingPageUrls()
        pages.takeIf { it.isNotEmpty() }?.let {
            ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages")
        }
    }

    private fun chapterPath(value: String): Pair<String, String>? {
        if (!isSafeReadingChapterUrl(value, setOf("01.komiku.asia")) { it.isNotBlank() }) return null
        return runCatching {
            val segments = URI(value).path.split('/').filter(String::isNotBlank)
            if (segments.size < 2) return null
            val comicId = segments[segments.size - 2]
            val chapterId = segments.last()
            if (!comicId.matches(Regex("""[A-Za-z0-9_-]{1,160}""")) || !chapterId.matches(Regex("""[A-Za-z0-9_-]{1,160}"""))) null
            else comicId to chapterId
        }.getOrNull()
    }

    private fun resolveSafeImage(value: String, base: String): String? {
        val candidate = value.trim()
        if (candidate.isBlank() || candidate.equals("null", ignoreCase = true)) return null
        return runCatching {
            val image = URI(base).resolve(candidate)
            image.toString().takeIf(::isSafeReadingImageUrl)
        }.getOrNull()
    }

    private fun fetchJson(value: String): String? {
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (!isSafeReadingHttpUrl(value) || !uri.host.equals("01.komiku.asia", true) || uri.userInfo != null || (uri.port != -1 && uri.port != 443 && uri.port != 80)) return null
        return ReadingHttpTransport.getText(
            value,
            mapOf(
                "User-Agent" to "ShinigamiReadingRuntime/1.0",
                "Accept" to "application/json",
                "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8",
            ),
        )
    }
}

/** Siikomik exposes chapter reader images under the site's MangaThemesia reader area. */
class SiikomikReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "siikomik"

    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) =
        entry.kind == ReadingKind.MANGA && chapter.sources.any {
            it.enabled && it.providerId.equals(id, true) && validChapter(it.url)
        }

    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        if (entry.kind != ReadingKind.MANGA) return@withContext null
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) }
            ?: return@withContext null
        val doc = try {
            if (documentFetcher != null) documentFetcher.invoke(source.url) else fetchDocument(source.url)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return@withContext null
        val pages = doc.select("#readerarea img, .reading-content img").mapNotNull { image ->
            sequenceOf("data-lazy-src", "data-src", "data-original", "src")
                .map { image.attr(it).trim() }
                .mapNotNull { raw ->
                    raw.takeIf { it.isNotBlank() && !it.startsWith("data:", ignoreCase = true) }
                        ?.let { resolveSafeImage(it, source.url) }
                }
                .firstOrNull()
        }.filterNotNull().normalizedReadingPageUrls()
        pages.takeIf { it.isNotEmpty() }?.let {
            ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages")
        }
    }

    private fun validChapter(value: String) = isSafeReadingChapterUrl(value, setOf("siikomik.id")) { it.isNotBlank() }

    private fun resolveSafeImage(value: String, base: String): String? = runCatching {
        val image = URI(base).resolve(value)
        image.toString().takeIf(::isSafeReadingImageUrl)
    }.getOrNull()

    private fun fetchDocument(value: String): Document? {
        if (!validChapter(value)) return null
        return fetchReadingHtmlDocument(
            value,
            mapOf(
                "User-Agent" to "ShinigamiReadingRuntime/1.0",
                "Accept" to "text/html,application/xhtml+xml",
                "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8",
            ),
        )
    }
}

/** NarasiNinja chapter HTML renders its chapter pages through ts-main-image elements. */
class NarasiNinjaReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "narasininja"

    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) =
        entry.kind == ReadingKind.MANGA && chapter.sources.any {
            it.enabled && it.providerId.equals(id, true) && validChapter(it.url)
        }

    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        if (entry.kind != ReadingKind.MANGA) return@withContext null
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && validChapter(it.url) }
            ?: return@withContext null
        val doc = try {
            if (documentFetcher != null) documentFetcher.invoke(source.url) else fetchDocument(source.url)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return@withContext null
        val pages = doc.select("#readerarea img.ts-main-image, #readerarea img, .reading-content img").mapNotNull { image ->
            sequenceOf("src", "data-src", "data-lazy-src", "data-original")
                .map { image.attr(it).trim() }
                .mapNotNull { raw ->
                    raw.takeIf { it.isNotBlank() && !it.startsWith("data:", ignoreCase = true) }
                        ?.let { resolveSafeImage(it, source.url) }
                }
                .firstOrNull()
        }.filterNotNull().normalizedReadingPageUrls()
        pages.takeIf { it.isNotEmpty() }?.let {
            ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages")
        }
    }

    private fun validChapter(value: String) = isSafeReadingChapterUrl(value, setOf("narasininja.net")) { it.contains("/komik", true) }

    private fun resolveSafeImage(value: String, base: String): String? = runCatching {
        val image = URI(base).resolve(value)
        image.toString().takeIf(::isSafeReadingImageUrl)
    }.getOrNull()

    private fun fetchDocument(value: String): Document? {
        if (!validChapter(value)) return null
        return fetchReadingHtmlDocument(
            value,
            mapOf(
                "User-Agent" to "ShinigamiReadingRuntime/1.0",
                "Accept" to "text/html,application/xhtml+xml",
                "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8",
            ),
        )
    }
}
