package streamix.readingcatalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class KomikNesiaReadingRuntimeProviderTest {
    @Test fun usesDedicatedChapterSlugApiAndExtractsImageArray() = runBlocking {
        var requested = ""
        val provider = KomikNesiaReadingRuntimeProvider { url ->
            requested = url
            """{"data":{"images":["https://cdn.example/a.webp","https://cdn.example/b.webp"]}}"""
        }
        val entry = ReadingCatalogEntry("m", ReadingKind.MANGA, "Test", "https://v2.komiknesiaku.com/komik/test", ReadingEntryOrigin.MANUAL)
        val chapter = ReadingChapterEntry("c", "1", sources = listOf(ReadingChapterSource("s", "komiknesia", "https://v2.komiknesiaku.com/view/chapter-slug")))
        val result = provider.chapter(entry, chapter)
        assertEquals("https://api-be.komiknesia.my.id/api/chapters/slug/chapter-slug", requested)
        assertEquals(listOf("https://cdn.example/a.webp", "https://cdn.example/b.webp"), result?.pageUrls)
    }

    @Test fun rejectsChapterUrlOutsideKnownKomikNesiaHosts() = runBlocking {
        val provider = KomikNesiaReadingRuntimeProvider { error("must not call an upstream URL for an invalid host") }
        val entry = ReadingCatalogEntry("m", ReadingKind.MANGA, "Test", "https://example.invalid", ReadingEntryOrigin.MANUAL)
        val chapter = ReadingChapterEntry("c", "1", sources = listOf(ReadingChapterSource("s", "komiknesia", "https://evil.invalid/view/chapter")))
        assertNull(provider.chapter(entry, chapter))
    }
}
