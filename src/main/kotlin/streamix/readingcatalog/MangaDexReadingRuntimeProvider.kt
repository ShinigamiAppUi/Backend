package streamix.readingcatalog

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MangaDex chapter adapter for the reading runtime.
 *
 * Supports both manually curated canonical chapter URLs and a constrained
 * official-API discovery path when a title/chapter has no Catalog source URL.
 * Discovery requires an exact normalized title match and exact chapter number;
 * it never guesses a chapter from a series UUID or a fuzzy title match.
 */
class MangaDexReadingRuntimeProvider(
    private val connectTimeoutMillis: Int = 8_000,
    private val readTimeoutMillis: Int = 12_000,
    private val jsonFetcher: ((String) -> JsonObject?)? = null
) : ReadingRuntimeProvider {
    override val id: String = "mangadex"

    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): Boolean {
        if (entry.kind == ReadingKind.NOVEL) return false
        val explicit = chapter.sources.any {
            it.enabled && it.providerId.equals(id, ignoreCase = true) && isMangaDexChapterUrl(it.url.trim())
        }
        // Runtime race removes failed explicit sources before discovery. While any
        // enabled Catalog source remains, keep it authoritative and don't race discovery.
        return explicit || (chapter.sources.none { it.enabled } &&
            entry.title.isNotBlank() && chapter.number.isNotBlank())
    }

    override suspend fun chapter(
        entry: ReadingCatalogEntry,
        chapter: ReadingChapterEntry
    ): ReadingContent? = withContext(Dispatchers.IO) {
        if (entry.kind == ReadingKind.NOVEL) return@withContext null
        val explicitChapterUrl = chapter.sources.asSequence()
            .filter { it.enabled && it.providerId.equals(id, ignoreCase = true) }
            .mapNotNull { it.url.trim().takeIf(::isMangaDexChapterUrl) }
            .firstOrNull()
        // A direct call must obey the same Catalog priority as supports(): only
        // attempt discovery after the runtime race has removed every enabled source.
        // Otherwise an unsupported/unsafe URL could accidentally trigger a separate lookup.
        if (explicitChapterUrl == null && chapter.sources.any { it.enabled }) {
            return@withContext null
        }
        val chapterId = if (explicitChapterUrl != null) {
            runCatching { URI(explicitChapterUrl).path.trimEnd('/').substringAfterLast('/') }
                .getOrNull()?.takeIf(UUID_PATTERN::matches)
        } else {
            discoverChapterId(entry, chapter)
        } ?: return@withContext null

        val json = fetchJson("https://api.mangadex.org/at-home/server/$chapterId")
            ?: return@withContext null
        val data = json.getAsJsonObject("chapter") ?: return@withContext null
        val baseUrl = json.get("baseUrl")?.takeIf { it.isJsonPrimitive }?.asString
            ?.takeIf(::isAllowedMangaDexCdnBaseUrl) ?: return@withContext null
        val hash = data.get("hash")?.takeIf { it.isJsonPrimitive }?.asString
            ?.takeIf { it.matches(MANGADEX_CHAPTER_HASH_PATTERN) } ?: return@withContext null
        val files = data.getAsJsonArray("data") ?: return@withContext null
        val pages = files.mapNotNull { element ->
            element.takeIf { it.isJsonPrimitive }?.asString
                ?.takeIf { it.matches(MANGADEX_PAGE_FILENAME_PATTERN) && it != "." && it != ".." && !it.endsWith(".") }
                ?.let { "$baseUrl/data/$hash/$it" }
        }
        pages.takeIf { it.isNotEmpty() }?.let {
            ReadingContent(entry.kind, "reading-provider:$id", pageUrls = it, format = "image-pages")
        }
    }

    private fun discoverChapterId(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): String? {
        val title = normalizeTitle(entry.title)
        if (title.isBlank() || chapter.number.isBlank()) return null
        val encodedTitle = URLEncoder.encode(entry.title.trim(), StandardCharsets.UTF_8.name())
        val mangaSearch = fetchJson("https://api.mangadex.org/manga?title=$encodedTitle&limit=10")
            ?: return null
        val exactMangaIds = mangaSearch.getAsJsonArray("data")?.mapNotNull { row ->
            val item = row.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val attributes = item.getAsJsonObject("attributes") ?: return@mapNotNull null
            val titles = attributes.getAsJsonObject("title") ?: return@mapNotNull null
            val exactTitle = titles.entrySet().any { (_, value) ->
                value.isJsonPrimitive && normalizeTitle(value.asString) == title
            }
            if (!exactTitle) return@mapNotNull null
            item.get("id")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf(UUID_PATTERN::matches)
        }.orEmpty().distinct()
        if (exactMangaIds.isEmpty()) return null

        val encodedNumber = URLEncoder.encode(chapter.number.trim(), StandardCharsets.UTF_8.name())
        // Exact-title results can include multiple editions. Try each exact match so a
        // title with no chapter in one edition does not hide a matching chapter in another.
        for (mangaId in exactMangaIds) {
            val encodedLanguage = chapter.language?.takeIf { it == "id" || it == "en" }
                ?.let { "&translatedLanguage%5B%5D=$it" }.orEmpty()
            val chapters = fetchJson("https://api.mangadex.org/chapter?manga%5B%5D=$mangaId&chapter=$encodedNumber$encodedLanguage&limit=100&order%5BreadableAt%5D=asc")
                ?: continue
            val chapterId = chapters.getAsJsonArray("data")?.firstNotNullOfOrNull { row ->
                val item = row.takeIf { it.isJsonObject }?.asJsonObject ?: return@firstNotNullOfOrNull null
                val attributes = item.getAsJsonObject("attributes") ?: return@firstNotNullOfOrNull null
                val foundNumber = attributes.get("chapter")?.takeIf { it.isJsonPrimitive }?.asString ?: return@firstNotNullOfOrNull null
                if (!sameChapterNumber(foundNumber, chapter.number)) return@firstNotNullOfOrNull null
                item.get("id")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf(UUID_PATTERN::matches)
            }
            if (chapterId != null) return chapterId
        }
        return null
    }

    private fun normalizeTitle(value: String): String =
        value.lowercase(java.util.Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")

    private fun sameChapterNumber(left: String, right: String): Boolean {
        val a = left.trim().toBigDecimalOrNull()
        val b = right.trim().toBigDecimalOrNull()
        return if (a != null && b != null) a.compareTo(b) == 0 else left.trim().equals(right.trim(), true)
    }

    private fun isAllowedMangaDexCdnBaseUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", true) &&
            uri.host.equals("uploads.mangadex.org", true) &&
            uri.userInfo == null && (uri.port == -1 || uri.port == 443) &&
            (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") &&
            uri.rawQuery == null && uri.rawFragment == null
    }.getOrDefault(false)

    private fun isMangaDexChapterUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", true) && (uri.host.equals("mangadex.org", true) ||
            uri.host.equals("www.mangadex.org", true)) &&
            uri.userInfo == null && (uri.port == -1 || uri.port == 443) &&
            uri.rawQuery == null && uri.rawFragment == null &&
            uri.path.trimEnd('/').matches(Regex("/chapter/[0-9a-fA-F-]{36}"))
    }.getOrDefault(false)

    private fun fetchJson(url: String): JsonObject? =
        if (jsonFetcher != null) jsonFetcher.invoke(url) else requestJson(url)

    private fun requestJson(url: String): JsonObject? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", true) || !uri.host.equals("api.mangadex.org", true) ||
            uri.userInfo != null || (uri.port != -1 && uri.port != 443)
        ) return null
        val body = ReadingHttpTransport.getText(
            url,
            mapOf(
                "User-Agent" to "ShinigamiReadingCatalog/1.0",
                "Accept" to "application/json",
            ),
            followRedirects = false,
            connectTimeoutMillis = connectTimeoutMillis,
            readTimeoutMillis = readTimeoutMillis,
        ) ?: return null
        return runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
    }

    private companion object {
        val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        val MANGADEX_CHAPTER_HASH_PATTERN = Regex("[0-9a-fA-F]{32}")
        val MANGADEX_PAGE_FILENAME_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,199}")
    }
}
