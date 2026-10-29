package streamix.readingcatalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup

class KomikindoReadingRuntimeProviderTest {
    @Test
    fun extractsOrderedReaderImagesFromCuratedChapterUrl() = runBlocking {
        var requested = ""
        val provider = KomikindoReadingRuntimeProvider { url ->
            requested = url
            Jsoup.parse(
                """<div id="readerarea"><img data-src="/uploads/001.webp"><img src="https://cdn.example/002.jpg"></div>""",
                url,
            )
        }
        val entry = ReadingCatalogEntry(
            "m", ReadingKind.MANGA, "Test", "https://komikindo4.link/manga/test",
            ReadingEntryOrigin.MANUAL,
        )
        val chapter = ReadingChapterEntry(
            "c", "1",
            sources = listOf(
                ReadingChapterSource("s", "komikindo", "https://komikindo4.link/manga/test-chapter-1/"),
            ),
        )

        val result = provider.chapter(entry, chapter)

        assertEquals("https://komikindo4.link/manga/test-chapter-1/", requested)
        assertEquals(
            listOf(
                "https://komikindo4.link/uploads/001.webp",
                "https://cdn.example/002.jpg",
            ),
            result?.pageUrls,
        )
    }

    @Test
    fun rejectsNonCanonicalHostsAndNonChapterPathsBeforeFetching() = runBlocking {
        val provider = KomikindoReadingRuntimeProvider {
            error("invalid URLs must not be fetched")
        }
        val entry = ReadingCatalogEntry(
            "m", ReadingKind.MANGA, "Test", "https://example.invalid/manga/test",
            ReadingEntryOrigin.MANUAL,
        )
        val wrongHost = ReadingChapterEntry(
            "c1", "1",
            sources = listOf(ReadingChapterSource("s1", "komikindo", "https://evil.invalid/test-chapter-1/")),
        )
        val wrongPath = ReadingChapterEntry(
            "c2", "2",
            sources = listOf(ReadingChapterSource("s2", "komikindo", "https://komikindo4.link/manga/test/")),
        )

        assertNull(provider.chapter(entry, wrongHost))
        assertNull(provider.chapter(entry, wrongPath))
    }
}
