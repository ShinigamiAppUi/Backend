package streamix.readingcatalog

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class KomikuComManhuaReadingRuntimeProviderTest {
    @Test
    fun apiChapterRouteUsesComicIdBeforeLanguageSegment() = runBlocking {
        val requested = mutableListOf<String>()
        val provider = KomikuComManhuaReadingRuntimeProvider(responseFetcher = { url ->
            requested += url
            """{}"""
        })
        val entry = ReadingCatalogEntry(
            "title", ReadingKind.MANHUA, "Title", "", ReadingEntryOrigin.MANUAL
        )
        val sourceUrl = "https://01.komiku.asia/api/v2/comics/comic-42/chapters/id/chapter-9"
        val chapter = ReadingChapterEntry(
            "chapter", "9",
            sources = listOf(ReadingChapterSource("source", "komiku-com-manhua", sourceUrl))
        )

        assertNull(provider.chapter(entry, chapter))
        assertEquals(
            listOf("https://01.komiku.asia/api/v2/comics/comic-42/chapters/id/chapter-9"),
            requested
        )
    }

    @Test
    fun rejectsNonCanonicalKomikuChapterRoutes() {
        val provider = KomikuComManhuaReadingRuntimeProvider(responseFetcher = { null })
        val entry = ReadingCatalogEntry(
            "title", ReadingKind.MANHUA, "Title", "", ReadingEntryOrigin.MANUAL
        )
        val invalidRoutes = listOf(
            "https://01.komiku.asia/api/v2/comics/comic-42/chapters/id/chapter-9/extra",
            "https://01.komiku.asia/api/v2/comics/comic-42/chapters/en/chapter-9",
            "https://01.komiku.asia/api/v2/comics/id/chapters/id/chapter-9"
        )

        for (url in invalidRoutes) {
            val chapter = ReadingChapterEntry(
                "chapter", "9",
                sources = listOf(ReadingChapterSource("source", "komiku-com-manhua", url))
            )
            assertFalse(provider.supports(entry, chapter), "route should be rejected: $url")
        }
    }
}
