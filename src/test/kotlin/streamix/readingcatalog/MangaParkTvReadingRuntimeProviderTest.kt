package streamix.readingcatalog

import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class MangaParkTvReadingRuntimeProviderTest {
    @Test fun parsesMadaraReaderImagesForMangaAndManhwa() = runBlocking {
        val provider = MangaParkTvReadingRuntimeProvider { url ->
            Jsoup.parse("""<div class="reading-content"><img data-src="/images/1.jpg"><img src="https://cdn.mangapark.tv/images/2.jpg"></div>""", url)
        }
        for (kind in listOf(ReadingKind.MANGA, ReadingKind.MANHWA, ReadingKind.MANHUA)) {
            val entry = ReadingCatalogEntry("title", kind, "Title", "https://mangapark.tv/manga/title", ReadingEntryOrigin.MANUAL)
            val chapter = ReadingChapterEntry("chapter", "1", sources = listOf(ReadingChapterSource("source", "mangapark-tv", "https://mangapark.tv/manga/title/chapter-1")))
            assertTrue(provider.supports(entry, chapter))
            assertEquals(listOf("https://mangapark.tv/images/1.jpg", "https://cdn.mangapark.tv/images/2.jpg"), provider.chapter(entry, chapter)?.pageUrls)
        }
    }

    @Test fun fallsBackToDataOriginalAndSkipsDataUriPlaceholder() = runBlocking {
        val provider = MangaParkTvReadingRuntimeProvider { url -> Jsoup.parse("""<div class="reading-content"><img data-original="/original.jpg" src="data:image/gif;base64,placeholder"></div>""", url) }
        val entry = ReadingCatalogEntry("title", ReadingKind.MANGA, "Title", "https://mangapark.tv/manga/title", ReadingEntryOrigin.MANUAL)
        val chapter = ReadingChapterEntry("chapter", "1", sources = listOf(ReadingChapterSource("source", "mangapark-tv", "https://mangapark.tv/manga/title/chapter-1")))
        assertEquals(listOf("https://mangapark.tv/original.jpg"), provider.chapter(entry, chapter)?.pageUrls)
    }

    @Test fun rejectsForeignHostsAndNovelKind() = runBlocking {
        val provider = MangaParkTvReadingRuntimeProvider { error("must not fetch") }
        val manhua = ReadingCatalogEntry("title", ReadingKind.NOVEL, "Title", "https://mangapark.tv/manga/title", ReadingEntryOrigin.MANUAL)
        val foreign = ReadingChapterEntry("chapter", "1", sources = listOf(ReadingChapterSource("source", "mangapark-tv", "https://evil.example/manga/title/chapter-1")))
        assertFalse(provider.supports(manhua, foreign))
        assertNull(provider.chapter(manhua, foreign))
    }
}
