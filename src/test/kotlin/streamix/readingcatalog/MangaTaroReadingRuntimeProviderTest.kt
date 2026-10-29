package streamix.readingcatalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class MangaTaroReadingRuntimeProviderTest {
    @Test fun fetchesChapterImagesFromDedicatedApi() = runBlocking {
        var requested = ""
        val provider = MangaTaroReadingRuntimeProvider { url -> requested = url; """{"images":["https://cdn.mangataro.org/1.jpg","/2.jpg"]}""" }
        val entry = ReadingCatalogEntry("m", ReadingKind.MANGA, "Manga", "https://mangataro.org/title", ReadingEntryOrigin.MANUAL)
        val chapter = ReadingChapterEntry("c", "1", sources = listOf(ReadingChapterSource("s", "mangataro", "https://mangataro.org/title-chapter-123")))
        val result = provider.chapter(entry, chapter)
        assertEquals("https://mangataro.org/auth/chapter-content?chapter_id=123", requested)
        assertEquals(listOf("https://cdn.mangataro.org/1.jpg", "https://mangataro.org/2.jpg"), result?.pageUrls)
    }
}
