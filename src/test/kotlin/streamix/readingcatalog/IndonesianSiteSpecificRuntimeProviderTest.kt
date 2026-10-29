package streamix.readingcatalog

import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class IndonesianSiteSpecificRuntimeProviderTest {
    private fun entry() = ReadingCatalogEntry("m", ReadingKind.MANGA, "Komik", "https://example.invalid/title", ReadingEntryOrigin.MANUAL)
    private fun chapter(id: String, url: String) = ReadingChapterEntry("c", "1", sources = listOf(ReadingChapterSource("s", id, url)))

    @Test fun bacaKomikUsesChapterImageBlocks() = runBlocking {
        val p = BacaKomikReadingRuntimeProvider { u -> Jsoup.parse("""<div><img alt="Chapter 1" src="/p.jpg"></div>""", u) }
        assertEquals(listOf("https://bacakomik.my/p.jpg"), p.chapter(entry(), chapter("bacakomik", "https://bacakomik.my/komik/title/chapter-1"))?.pageUrls)
    }
    @Test fun mangaCanUsesReaderArea() = runBlocking {
        val p = MangaCanReadingRuntimeProvider { u -> Jsoup.parse("""<div id="readerarea"><img data-src="/p.jpg"></div>""", u) }
        assertEquals(listOf("https://mangacanblog.com/p.jpg"), p.chapter(entry(), chapter("manga-can", "https://mangacanblog.com/chapter/title"))?.pageUrls)
    }
    @Test fun mangalayDropsTrailingNavigationImage() = runBlocking {
        val p = MangalayReadingRuntimeProvider { u -> Jsoup.parse("""<div class="separator"><img src="/p1.jpg"></div><div class="separator"><img src="/nav.jpg"></div>""", u) }
        assertEquals(listOf("https://mangalay.blogspot.com/p1.jpg"), p.chapter(entry(), chapter("mangalay", "https://mangalay.blogspot.com/2026/01/chapter.html"))?.pageUrls)
    }
    @Test fun ngamenKomikUsesZeistSeparatorMarkup() = runBlocking {
        val p = NgamenKomikReadingRuntimeProvider { u -> Jsoup.parse("""<div class="check-box"><div class="separator"><img src="/p.jpg"></div></div>""", u) }
        assertEquals(listOf("https://ngamenkomik05.blogspot.com/p.jpg"), p.chapter(entry(), chapter("ngamenkomik", "https://ngamenkomik05.blogspot.com/2026/01/chapter.html"))?.pageUrls)
    }
    @Test fun ngomikUsesReaderArea() = runBlocking {
        val p = NgomikReadingRuntimeProvider { u -> Jsoup.parse("""<div id="readerarea"><img src="/p.jpg"></div>""", u) }
        assertEquals(listOf("https://02.ngomik.cc/p.jpg"), p.chapter(entry(), chapter("ngomik", "https://02.ngomik.cc/chapter/title"))?.pageUrls)
    }
    @Test fun okyyKomikUsesArticleSeparators() = runBlocking {
        val p = OkyyKomikReadingRuntimeProvider { u -> Jsoup.parse("""<article><div class="separator"><img src="/p.jpg"></div></article>""", u) }
        assertEquals(listOf("https://www.okyykomik.my.id/p.jpg"), p.chapter(entry(), chapter("okyykomik", "https://www.okyykomik.my.id/2026/01/chapter.html"))?.pageUrls)
    }
    @Test fun shiyuraSubUsesBloggerSeparatorImages() = runBlocking {
        val p = ShiyuraSubReadingRuntimeProvider { u -> Jsoup.parse("""<div class="separator"><img src="/p.jpg"></div>""", u) }
        assertEquals(listOf("https://shiyurasub.blogspot.com/p.jpg"), p.chapter(entry(), chapter("shiyurasub", "https://shiyurasub.blogspot.com/2026/01/chapter.html"))?.pageUrls)
    }

    @Test fun mangalayFallsBackToLazyImagesAndStillDropsNavigationImage() = runBlocking {
        val p = MangalayReadingRuntimeProvider { u -> Jsoup.parse("""<div class="separator"><img data-src="/p1.jpg"></div><div class="separator"><img data-original="/nav.jpg"></div>""", u) }
        assertEquals(listOf("https://mangalay.blogspot.com/p1.jpg"), p.chapter(entry(), chapter("mangalay", "https://mangalay.blogspot.com/2026/01/chapter.html"))?.pageUrls)
    }
    @Test fun ngamenKomikFallsBackToLazyImageAttributes() = runBlocking {
        val p = NgamenKomikReadingRuntimeProvider { u -> Jsoup.parse("""<div class="check-box"><div class="separator"><img data-lazy-src="/p.jpg"></div></div>""", u) }
        assertEquals(listOf("https://ngamenkomik05.blogspot.com/p.jpg"), p.chapter(entry(), chapter("ngamenkomik", "https://ngamenkomik05.blogspot.com/2026/01/chapter.html"))?.pageUrls)
    }
    @Test fun okyyKomikFallsBackToLazyImageAttributes() = runBlocking {
        val p = OkyyKomikReadingRuntimeProvider { u -> Jsoup.parse("""<article><div class="separator"><img data-src="/p.jpg"></div></article>""", u) }
        assertEquals(listOf("https://www.okyykomik.my.id/p.jpg"), p.chapter(entry(), chapter("okyykomik", "https://www.okyykomik.my.id/2026/01/chapter.html"))?.pageUrls)
    }
    @Test fun shiyuraSubFallsBackToLazyImageAttributes() = runBlocking {
        val p = ShiyuraSubReadingRuntimeProvider { u -> Jsoup.parse("""<div class="separator"><img data-original="/p.jpg"></div>""", u) }
        assertEquals(listOf("https://shiyurasub.blogspot.com/p.jpg"), p.chapter(entry(), chapter("shiyurasub", "https://shiyurasub.blogspot.com/2026/01/chapter.html"))?.pageUrls)
    }

    @Test fun bacaKomikFallsBackToLazyImageAttributes() = runBlocking {
        val p = BacaKomikReadingRuntimeProvider { u -> Jsoup.parse("""<div><img alt="Chapter 1" data-lazy-src="/lazy.jpg"></div>""", u) }
        assertEquals(listOf("https://bacakomik.my/lazy.jpg"), p.chapter(entry(), chapter("bacakomik", "https://bacakomik.my/komik/title/chapter-1"))?.pageUrls)
    }

    @Test fun mangaCanFallsBackToDataOriginal() = runBlocking {
        val p = MangaCanReadingRuntimeProvider { u -> Jsoup.parse("""<div id="readerarea"><img data-original="/original.jpg" src="data:image/gif;base64,placeholder"></div>""", u) }
        assertEquals(listOf("https://mangacanblog.com/original.jpg"), p.chapter(entry(), chapter("manga-can", "https://mangacanblog.com/chapter/title"))?.pageUrls)
    }
    @Test fun ngomikFallsBackToDataOriginal() = runBlocking {
        val p = NgomikReadingRuntimeProvider { u -> Jsoup.parse("""<div id="readerarea"><img data-original="/original.jpg" src="data:image/gif;base64,placeholder"></div>""", u) }
        assertEquals(listOf("https://02.ngomik.cc/original.jpg"), p.chapter(entry(), chapter("ngomik", "https://02.ngomik.cc/2026/01/chapter.html"))?.pageUrls)
    }
}
