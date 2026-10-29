package streamix.readingcatalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class MangaFireReadingRuntimeProviderTest {
    @Test
    fun extractsOnlySafeHttpsPagesFromChapterApiPayload() = runBlocking {
        var requestedUrl = ""
        val provider = MangaFireReadingRuntimeProvider { url ->
            requestedUrl = url
            """{"data":{"pages":[
                {"url":"https://cdn.example/1.jpg"},
                {"url":"http://cdn.example/insecure.jpg"},
                {"url":"https://cdn.example:8443/nonstandard-port.jpg"},
                {"url":"https://cdn.example/2.jpg"}
            ]}}"""
        }
        val entry = ReadingCatalogEntry(
            "m", ReadingKind.MANGA, "Manga", "https://mangafire.to/manga/title", ReadingEntryOrigin.MANUAL
        )
        val chapter = ReadingChapterEntry(
            "c", "1", sources = listOf(
                ReadingChapterSource("s", "mangafire", "https://mangafire.to/manga/title/123-chapter-1")
            )
        )

        val result = provider.chapter(entry, chapter)

        assertEquals(true, requestedUrl.startsWith("https://mangafire.to/api/chapters/123?vrf="))
        assertEquals(listOf("https://cdn.example/1.jpg", "https://cdn.example/2.jpg"), result?.pageUrls)
    }
}
