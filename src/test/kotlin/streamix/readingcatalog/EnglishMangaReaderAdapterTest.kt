package streamix.readingcatalog

import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class EnglishMangaReaderAdapterTest {
    private fun entry() = ReadingCatalogEntry("m", ReadingKind.MANGA, "Manga", "https://example.invalid/title", ReadingEntryOrigin.MANUAL)
    private fun chapter(id: String, url: String) = ReadingChapterEntry("c", "1", sources = listOf(ReadingChapterSource("s", id, url)))

    @Test fun mangaFoxUsesViewerDataOriginalAndMobileRollEndpoint() = runBlocking {
        var requested = ""
        val provider = MangaFoxReadingRuntimeProvider { url -> requested = url; Jsoup.parse("""<div id="viewer"><img data-original="/p.jpg"></div>""", url) }
        val result = provider.chapter(entry(), chapter("mangafox", "https://fanfox.net/manga/title/c001/"))
        assertEquals("https://m.fanfox.net/roll_manga/title/c001/", requested)
        assertEquals(listOf("https://m.fanfox.net/p.jpg"), result?.pageUrls)
    }

    @Test fun mangaBoltUsesJsPagesContainer() = runBlocking {
        val provider = MangaBoltReadingRuntimeProvider { url -> Jsoup.parse("""<div class="js-pages-container"><img class="js-page" data-src="/p1.jpg"><img class="js-page" src="data:image/gif;base64,AAA"></div>""", url) }
        assertEquals(listOf("https://mangabolt.com/p1.jpg"), provider.chapter(entry(), chapter("mangabolt", "https://mangabolt.com/series/chapter-1"))?.pageUrls)
    }

    @Test fun mangaFoxFallsBackToLazySrc() = runBlocking {
        val provider = MangaFoxReadingRuntimeProvider { url -> Jsoup.parse("""<div id="viewer"><img data-lazy-src="/lazy.jpg"></div>""", url) }
        assertEquals(listOf("https://m.fanfox.net/lazy.jpg"), provider.chapter(entry(), chapter("mangafox", "https://fanfox.net/manga/title/chapter-1"))?.pageUrls)
    }

    @Test fun mangaBoltFallsBackToLazyAndOriginalImageAttributes() = runBlocking {
        val provider = MangaBoltReadingRuntimeProvider { url ->
            Jsoup.parse("""<div class="js-pages-container"><img class="js-page" data-lazy-src="/lazy.jpg" src="data:image/gif;base64,placeholder"><img class="js-page" data-original="/original.jpg" src="data:image/gif;base64,placeholder"></div>""", url)
        }
        val result = provider.chapter(entry(), chapter("mangabolt", "https://mangabolt.com/chapter/title"))
        assertEquals(listOf("https://mangabolt.com/lazy.jpg", "https://mangabolt.com/original.jpg"), result?.pageUrls)
    }
}
