package streamix.readingcatalog

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class ShinigamiReadingRuntimeProviderTest {
    @Test fun parsesNativeChapterDetailPayloadWithoutFetchingCatalogUrl() = runBlocking {
        var requested = ""
        val provider = ShinigamiReadingRuntimeProvider { url ->
            requested = url
            """{"data":{"pageList":{"baseUrl":"https://cdn.shngm.io","chapterPage":{"path":"series/123","pages":["001.webp","002.webp"]}}}}"""
        }
        val entry = ReadingCatalogEntry("m", ReadingKind.MANGA, "Test", "https://11.shinigami.asia/series/123", ReadingEntryOrigin.MANUAL)
        val chapter = ReadingChapterEntry("c", "1", sources = listOf(ReadingChapterSource("s", "shinigami", "chapter_opaque-123")))
        val result = provider.chapter(entry, chapter)
        assertNotNull(result)
        assertEquals("https://api.shngm.io/v1/chapter/detail/chapter_opaque-123", requested)
        assertEquals(listOf("https://cdn.shngm.io/series/123/001.webp", "https://cdn.shngm.io/series/123/002.webp"), result.pageUrls)
    }

    @Test fun rejectsChapterIdentifiersWithUrlSyntax() = runBlocking {
        val provider = ShinigamiReadingRuntimeProvider { error("should not request") }
        val entry = ReadingCatalogEntry("m", ReadingKind.MANGA, "Test", "https://11.shinigami.asia/series/123", ReadingEntryOrigin.MANUAL)
        val chapter = ReadingChapterEntry("c", "1", sources = listOf(ReadingChapterSource("s", "shinigami", "https://evil.invalid/steal")))
        assertNull(provider.chapter(entry, chapter))
    }
}
