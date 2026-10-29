package streamix.readingcatalog

import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class KuroMangaReadingRuntimeProviderTest {
    @Test fun fallsBackToDataOriginalAndSkipsDataUriPlaceholder() = runBlocking {
        val p = KuroMangaReadingRuntimeProvider { u -> Jsoup.parse("""<div id="readerarea"><img data-original="/original.jpg" src="data:image/gif;base64,placeholder"></div>""", u) }
        val e = ReadingCatalogEntry("m", ReadingKind.MANGA, "Manga", "https://kuromanga.id/komik/title", ReadingEntryOrigin.MANUAL)
        val c = ReadingChapterEntry("c", "1", sources = listOf(ReadingChapterSource("s", "kuro-manga", "https://kuromanga.id/komik/title/chapter-1")))
        assertEquals(listOf("https://kuromanga.id/original.jpg"), p.chapter(e,c)?.pageUrls)
    }

    @Test fun readsLazyImagesFromMangaThemesiaReader() = runBlocking {
        val p = KuroMangaReadingRuntimeProvider { u -> Jsoup.parse("""<div id="readerarea"><img data-lazy-src="/p.jpg"></div>""", u) }
        val e = ReadingCatalogEntry("m", ReadingKind.MANGA, "Manga", "https://kuromanga.id/komik/title", ReadingEntryOrigin.MANUAL)
        val c = ReadingChapterEntry("c", "1", sources = listOf(ReadingChapterSource("s", "kuro-manga", "https://kuromanga.id/komik/title/chapter-1")))
        assertEquals(listOf("https://kuromanga.id/p.jpg"), p.chapter(e,c)?.pageUrls)
    }
}
