package streamix.readingcatalog

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MangaDexReadingRuntimeProviderTest {
    @Test
    fun chapterAdapterBuildsPageUrlsFromAtHomeResponse() = runBlocking {
        val requested = mutableListOf<String>()
        val provider = MangaDexReadingRuntimeProvider(jsonFetcher = { url ->
            requested += url
            JsonParser.parseString(
                """{"baseUrl":"https://uploads.mangadex.org","chapter":{"hash":"0123456789abcdef0123456789abcdef","data":["page-1.jpg","page-2.png"]}}"""
            ).asJsonObject
        })
        val chapterId = "12345678-1234-1234-1234-123456789abc"
        val entry = ReadingCatalogEntry("m", ReadingKind.MANGA, "Manga", "https://mangadex.org/title/series", ReadingEntryOrigin.MANUAL)
        val chapter = ReadingChapterEntry(
            "c", "1", sources = listOf(
                ReadingChapterSource("s", "mangadex", "https://mangadex.org/chapter/$chapterId")
            )
        )

        val result = provider.chapter(entry, chapter)

        assertEquals(listOf("https://api.mangadex.org/at-home/server/$chapterId"), requested)
        assertEquals(
            listOf(
                "https://uploads.mangadex.org/data/0123456789abcdef0123456789abcdef/page-1.jpg",
                "https://uploads.mangadex.org/data/0123456789abcdef0123456789abcdef/page-2.png"
            ),
            result?.pageUrls
        )
        assertEquals("image-pages", result?.format)
    }

    @Test
    fun novelAndNonCanonicalChapterUrlsAreRejectedWithoutNetwork() = runBlocking {
        var called = false
        val provider = MangaDexReadingRuntimeProvider(jsonFetcher = { called = true; null })
        val chapter = ReadingChapterEntry(
            "c", "1", sources = listOf(
                ReadingChapterSource("s", "unknown", "https://example.com/chapter/123")
            )
        )
        val novel = ReadingCatalogEntry("n", ReadingKind.NOVEL, "Novel", "https://example.com/book", ReadingEntryOrigin.MANUAL)
        val manga = novel.copy(id = "m", kind = ReadingKind.MANGA)

        assertNull(provider.chapter(novel, chapter))
        assertNull(provider.chapter(manga, chapter))
        assertTrue(!called)
    }
    @Test
    fun rejectsUntrustedAtHomeCdnBaseUrls() = runBlocking {
        val chapterId = "12345678-1234-1234-1234-123456789abc"
        val entry = ReadingCatalogEntry("m", ReadingKind.MANGA, "Manga", "https://mangadex.org/title/series", ReadingEntryOrigin.MANUAL)
        val chapter = ReadingChapterEntry("c", "1", sources = listOf(
            ReadingChapterSource("s", "mangadex", "https://mangadex.org/chapter/$chapterId")
        ))
        val unsafeBases = listOf(
            "http://uploads.mangadex.org",
            "https://attacker.example",
            "https://user@uploads.mangadex.org",
            "https://uploads.mangadex.org:8443",
            "https://uploads.mangadex.org/path"
        )
        for (base in unsafeBases) {
            val provider = MangaDexReadingRuntimeProvider(jsonFetcher = {
                JsonParser.parseString(
                    """{"baseUrl":"$base","chapter":{"hash":"0123456789abcdef0123456789abcdef","data":["page-1.jpg"]}}"""
                ).asJsonObject
            })
            assertNull(provider.chapter(entry, chapter), "baseUrl should be rejected: $base")
        }
    }

    @Test
    fun rejectsDotSegmentsInAtHomeFileNames() = runBlocking {
        val chapterId = "12345678-1234-1234-1234-123456789abc"
        val provider = MangaDexReadingRuntimeProvider(jsonFetcher = {
            JsonParser.parseString(
                """{"baseUrl":"https://uploads.mangadex.org","chapter":{"hash":"0123456789abcdef0123456789abcdef","data":["..",".","page-1.jpg"]}}"""
            ).asJsonObject
        })
        val entry = ReadingCatalogEntry("m", ReadingKind.MANGA, "Manga", "https://mangadex.org/title/series", ReadingEntryOrigin.MANUAL)
        val chapter = ReadingChapterEntry("c", "1", sources = listOf(
            ReadingChapterSource("s", "mangadex", "https://mangadex.org/chapter/$chapterId")
        ))
        assertEquals(listOf("https://uploads.mangadex.org/data/0123456789abcdef0123456789abcdef/page-1.jpg"), provider.chapter(entry, chapter)?.pageUrls)
    }


    @Test
    fun rejectsMalformedAtHomeHashesAndUnsafePageFilenames() = runBlocking {
        val chapterId = "12345678-1234-1234-1234-123456789abc"
        val entry = ReadingCatalogEntry("m", ReadingKind.MANGA, "Manga", "https://mangadex.org/title/series", ReadingEntryOrigin.MANUAL)
        val chapter = ReadingChapterEntry("c", "1", sources = listOf(
            ReadingChapterSource("s", "mangadex", "https://mangadex.org/chapter/$chapterId")
        ))
        val malformedHashes = listOf("abc123", "g".repeat(32), "0123456789abcdef0123456789abcdef?x=1")
        for (hash in malformedHashes) {
            val provider = MangaDexReadingRuntimeProvider(jsonFetcher = {
                JsonParser.parseString(
                    """{"baseUrl":"https://uploads.mangadex.org","chapter":{"hash":"$hash","data":["page-1.jpg"]}}"""
                ).asJsonObject
            })
            assertNull(provider.chapter(entry, chapter), "hash should be rejected: $hash")
        }
        val unsafeFilenames = listOf("page.jpg.", "../page.jpg", "page.jpg?x=1", "folder/page.jpg")
        for (filename in unsafeFilenames) {
            val provider = MangaDexReadingRuntimeProvider(jsonFetcher = {
                JsonParser.parseString(
                    """{"baseUrl":"https://uploads.mangadex.org","chapter":{"hash":"0123456789abcdef0123456789abcdef","data":["$filename"]}}"""
                ).asJsonObject
            })
            assertNull(provider.chapter(entry, chapter), "unsafe filename should not produce a page: $filename")
        }
    }

    @Test
    fun ignoresCanonicalMangaDexUrlWhenCatalogAssignsAnotherProvider() = runBlocking {
        var called = false
        val provider = MangaDexReadingRuntimeProvider(jsonFetcher = { called = true; null })
        val chapterId = "12345678-1234-1234-1234-123456789abc"
        val entry = ReadingCatalogEntry("m", ReadingKind.MANGA, "Manga", "https://mangadex.org/title/series", ReadingEntryOrigin.MANUAL)
        val chapter = ReadingChapterEntry("c", "1", sources = listOf(
            ReadingChapterSource("s", "different-provider", "https://mangadex.org/chapter/$chapterId")
        ))

        assertTrue(!provider.supports(entry, chapter))
        assertNull(provider.chapter(entry, chapter))
        assertEquals(false, called)
    }

    @Test
    fun rejectsMangaDexChapterUrlsWithCredentialsOrNonstandardPorts() = runBlocking {
        val chapterId = "12345678-1234-1234-1234-123456789abc"
        val unsafeUrls = listOf(
            "https://user@mangadex.org/chapter/$chapterId",
            "https://mangadex.org:8443/chapter/$chapterId",
            "https://mangadex.org/chapter/$chapterId?redirect=https://attacker.example",
            "https://mangadex.org/chapter/$chapterId#fragment"
        )
        var called = false
        val provider = MangaDexReadingRuntimeProvider(jsonFetcher = { called = true; null })
        val entry = ReadingCatalogEntry("m", ReadingKind.MANGA, "Manga", "https://mangadex.org/title/series", ReadingEntryOrigin.MANUAL)
        for (url in unsafeUrls) {
            val chapter = ReadingChapterEntry("c", "1", sources = listOf(ReadingChapterSource("s", "mangadex", url)))
            assertNull(provider.chapter(entry, chapter), "chapter URL should be rejected: $url")
        }
        assertEquals(false, called)
    }


    @Test
    fun discoveryWaitsUntilOtherCatalogSourcesHaveBeenFilteredOut() {
        val provider = MangaDexReadingRuntimeProvider(jsonFetcher = { null })
        val entry = ReadingCatalogEntry(
            "entry", ReadingKind.MANHWA, "Example Title", "", ReadingEntryOrigin.MANUAL
        )
        val activeChapter = ReadingChapterEntry(
            "chapter-1", "1",
            sources = listOf(
                ReadingChapterSource("other-source", "other-provider", "https://other.example/chapter/1")
            )
        )
        val failedSourcesRemovedByRace = activeChapter.copy(sources = emptyList())

        assertTrue(!provider.supports(entry, activeChapter))
        assertTrue(provider.supports(entry, failedSourcesRemovedByRace))
    }

    @Test
    fun discoversExactTitleAndChapterThroughOfficialApiWhenCatalogHasNoSourceUrl() = runBlocking {
        val requested = mutableListOf<String>()
        val chapterId = "12345678-1234-1234-1234-123456789abc"
        val provider = MangaDexReadingRuntimeProvider(jsonFetcher = { url ->
            requested += url
            when {
                url.startsWith("https://api.mangadex.org/manga?") ->
                    JsonParser.parseString("""{"data":[
                      {"id":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","attributes":{"title":{"en":"Different Manga"}}},
                      {"id":"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb","attributes":{"title":{"en":"One Piece"}}},
                      {"id":"dddddddd-dddd-dddd-dddd-dddddddddddd","attributes":{"title":{"en":"ONE PIECE"}}}
                    ]}""").asJsonObject
                url.startsWith("https://api.mangadex.org/chapter?") && url.contains("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb") ->
                    JsonParser.parseString("""{"data":[{"id":"cccccccc-cccc-cccc-cccc-cccccccccccc","attributes":{"chapter":"12.5"}}]}""").asJsonObject
                url.startsWith("https://api.mangadex.org/chapter?") ->
                    JsonParser.parseString("""{"data":[{"id":"$chapterId","attributes":{"chapter":"12"}}]}""").asJsonObject
                url == "https://api.mangadex.org/at-home/server/$chapterId" ->
                    JsonParser.parseString("""{"baseUrl":"https://uploads.mangadex.org","chapter":{"hash":"0123456789abcdef0123456789abcdef","data":["page-1.jpg"]}}""").asJsonObject
                else -> error("Unexpected URL: $url")
            }
        })
        val entry = ReadingCatalogEntry(
            "entry", ReadingKind.MANGA, "One Piece", "", ReadingEntryOrigin.MANUAL, metadataId = 21L
        )
        val chapter = ReadingChapterEntry("12", "12")

        assertTrue(provider.supports(entry, chapter))
        val result = provider.chapter(entry, chapter)

        assertEquals(listOf(
            "https://api.mangadex.org/manga?title=One+Piece&limit=10",
            "https://api.mangadex.org/chapter?manga%5B%5D=bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb&chapter=12&limit=100&order%5BreadableAt%5D=asc",
            "https://api.mangadex.org/chapter?manga%5B%5D=dddddddd-dddd-dddd-dddd-dddddddddddd&chapter=12&limit=100&order%5BreadableAt%5D=asc",
            "https://api.mangadex.org/at-home/server/$chapterId"
        ), requested)
        assertEquals(listOf("https://uploads.mangadex.org/data/0123456789abcdef0123456789abcdef/page-1.jpg"), result?.pageUrls)
    }

    @Test
    fun discoveryFiltersTheRequestedTranslatedLanguage() = runBlocking {
        val requested = mutableListOf<String>()
        val chapterId = "12345678-1234-1234-1234-123456789abc"
        val provider = MangaDexReadingRuntimeProvider(jsonFetcher = { url ->
            requested += url
            when {
                url.startsWith("https://api.mangadex.org/manga?") ->
                    JsonParser.parseString("""{"data":[{"id":"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb","attributes":{"title":{"en":"One Piece"}}}]}""").asJsonObject
                url.startsWith("https://api.mangadex.org/chapter?") ->
                    JsonParser.parseString("""{"data":[{"id":"$chapterId","attributes":{"chapter":"1"}}]}""").asJsonObject
                else ->
                    JsonParser.parseString("""{"baseUrl":"https://uploads.mangadex.org","chapter":{"hash":"0123456789abcdef0123456789abcdef","data":["page-1.jpg"]}}""").asJsonObject
            }
        })
        val entry = ReadingCatalogEntry("entry", ReadingKind.MANHWA, "One Piece", "", ReadingEntryOrigin.MANUAL)
        val chapter = ReadingChapterEntry("1", "1", language = "id")

        assertTrue(provider.supports(entry, chapter))
        assertTrue(provider.chapter(entry, chapter)?.pageUrls?.isNotEmpty() == true)
        assertTrue(requested.any { it.contains("translatedLanguage%5B%5D=id") })
    }

    @Test
    fun discoveryRejectsFuzzyTitleMatchesAndDoesNotGuessChapter() = runBlocking {
        var requestedChapterList = false
        val provider = MangaDexReadingRuntimeProvider(jsonFetcher = { url ->
            when {
                url.startsWith("https://api.mangadex.org/manga?") ->
                    JsonParser.parseString("""{"data":[{"id":"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb","attributes":{"title":{"en":"One Piece Film Red"}}}]}""").asJsonObject
                url.startsWith("https://api.mangadex.org/chapter?") -> {
                    requestedChapterList = true
                    JsonParser.parseString("""{"data":[]}""").asJsonObject
                }
                else -> null
            }
        })
        val entry = ReadingCatalogEntry("entry", ReadingKind.MANGA, "One Piece", "", ReadingEntryOrigin.MANUAL, metadataId = 21L)
        assertNull(provider.chapter(entry, ReadingChapterEntry("99", "99")))
        assertEquals(false, requestedChapterList)
    }

}
