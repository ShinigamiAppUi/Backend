package streamix.readingcatalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class RequestedKindGapAdapterTest {
    private fun entry(kind: ReadingKind) =
        ReadingCatalogEntry("m", kind, "Test", "https://shinigami.asia/title", ReadingEntryOrigin.MANUAL)

    private fun chapter(providerId: String, url: String) =
        ReadingChapterEntry("c", "1", sources = listOf(ReadingChapterSource("s", providerId, url)))

    @Test fun komikuComMangaUsesDedicatedProviderAndKind() = runBlocking {
        var requested = ""
        val provider = KomikuComMangaReadingRuntimeProvider { url ->
            requested = url
            """{"data":{"pages":["https://cdn.komiku.asia/p1.webp","https://cdn.komiku.asia/p2.webp"]}}"""
        }
        val ch = chapter("komiku-com-manga", "https://01.komiku.asia/api/v2/comics/comic-1/chapters/id/chapter-2")
        val result = provider.chapter(entry(ReadingKind.MANGA), ch)
        assertEquals("https://01.komiku.asia/api/v2/comics/comic-1/chapters/id/chapter-2", requested)
        assertEquals(listOf("https://cdn.komiku.asia/p1.webp", "https://cdn.komiku.asia/p2.webp"), result?.pageUrls)
        assertNull(provider.chapter(entry(ReadingKind.MANHUA), ch))
        assertFalse(provider.supports(entry(ReadingKind.MANGA), chapter("komiku-com-manhua", ch.sources.first().url)))
    }

    @Test fun shinigamiManhwaUsesNativeChapterApiWithKindIsolation() = runBlocking {
        var requested = ""
        val provider = ShinigamiManhwaReadingRuntimeProvider { url ->
            requested = url
            """{"data":{"pageList":{"baseUrl":"https://cdn.shngm.io","chapterPage":{"path":"series/1","pages":["001.webp"]}}}}"""
        }
        val ch = chapter("shinigami-manhwa", "chapter_opaque-1")
        val result = provider.chapter(entry(ReadingKind.MANHWA), ch)
        assertEquals("https://api.shngm.io/v1/chapter/detail/chapter_opaque-1", requested)
        assertEquals(listOf("https://cdn.shngm.io/series/1/001.webp"), result?.pageUrls)
        assertNull(provider.chapter(entry(ReadingKind.MANGA), ch))
    }

    @Test fun shinigamiManhuaUsesNativeChapterApiWithKindIsolation() = runBlocking {
        val provider = ShinigamiManhuaReadingRuntimeProvider { url ->
            assertEquals("https://api.shngm.io/v1/chapter/detail/chapter_opaque-2", url)
            """{"data":{"pageList":{"baseUrl":"https://cdn.shngm.io","chapterPage":{"path":"series/2","pages":["001.webp"]}}}}"""
        }
        val ch = chapter("shinigami-manhua", "chapter_opaque-2")
        assertEquals(listOf("https://cdn.shngm.io/series/2/001.webp"), provider.chapter(entry(ReadingKind.MANHUA), ch)?.pageUrls)
        assertNull(provider.chapter(entry(ReadingKind.MANGA), ch))
    }
}
