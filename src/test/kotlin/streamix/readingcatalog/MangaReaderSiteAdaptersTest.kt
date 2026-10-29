package streamix.readingcatalog

import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class MangaReaderSiteAdaptersTest {
    private fun entry() = ReadingCatalogEntry("m", ReadingKind.MANGA, "Test", "https://example.invalid/title", ReadingEntryOrigin.MANUAL)
    private fun chapter(id: String, url: String) = ReadingChapterEntry(
        "c", "1", sources = listOf(ReadingChapterSource("s", id, url))
    )

    @Test fun mangaKatanaFallsBackToLazySrc() = runBlocking {
        val provider = MangaKatanaReadingRuntimeProvider { url -> Jsoup.parse("""<div id="imgs"><img data-lazy-src="/lazy.jpg"></div>""", url) }
        assertEquals(listOf("https://mangakatana.com/lazy.jpg"), provider.chapter(entry(), chapter("mangakatana", "https://mangakatana.com/manga/series/chapter-1"))?.pageUrls)
    }

    @Test fun mangakakalotFallsBackToLazySrc() = runBlocking {
        val provider = MangakakalotReadingRuntimeProvider { url -> Jsoup.parse("""<div class="container-chapter-reader"><img data-lazy-src="/lazy.jpg"></div>""", url) }
        assertEquals(listOf("https://mangakakalot.com/lazy.jpg"), provider.chapter(entry(), chapter("mangakakalot", "https://mangakakalot.com/chapter/series/chapter_1"))?.pageUrls)
    }

    @Test fun manganatoFallsBackToLazySrc() = runBlocking {
        val provider = ManganatoReadingRuntimeProvider { url -> Jsoup.parse("""<div class="page-chapter"><img data-lazy-src="/lazy.jpg"></div>""", url) }
        assertEquals(listOf("https://chapmanganato.to/lazy.jpg"), provider.chapter(entry(), chapter("manganato", "https://chapmanganato.to/manga-abc/chapter-1"))?.pageUrls)
    }

    @Test fun mangaReadFallsBackToLazySrc() = runBlocking {
        val provider = MangaReadReadingRuntimeProvider { url -> Jsoup.parse("""<div class="reading-content"><img data-lazy-src="/lazy.jpg"></div>""", url) }
        assertEquals(listOf("https://mangaread.org/lazy.jpg"), provider.chapter(entry(), chapter("mangaread", "https://mangaread.org/manga/series/chapter-1"))?.pageUrls)
    }

    @Test fun mangaBatFallsBackToLazySrc() = runBlocking {
        val provider = MangaBatReadingRuntimeProvider { url -> Jsoup.parse("""<div class="container-chapter-reader"><img data-lazy-src="/lazy.jpg"></div>""", url) }
        assertEquals(listOf("https://mangabat.com/lazy.jpg"), provider.chapter(entry(), chapter("mangabat", "https://mangabat.com/chapter-series/chapter-1"))?.pageUrls)
    }

    @Test fun mangaHereFallsBackToLazySrc() = runBlocking {
        val provider = MangaHereReadingRuntimeProvider { url -> Jsoup.parse("""<div id="viewer"><img data-lazy-src="/lazy.jpg"></div>""", url) }
        assertEquals(listOf("https://mangahere.cc/lazy.jpg"), provider.chapter(entry(), chapter("mangahere", "https://mangahere.cc/manga/series/chapter-1"))?.pageUrls)
    }

    @Test fun mangaKatanaUsesItsReaderContainerAndRejectsOtherHosts() = runBlocking {
        val provider = MangaKatanaReadingRuntimeProvider { url -> Jsoup.parse("""<div id="imgs"><img data-src="/page.jpg"></div>""", url) }
        assertEquals(listOf("https://mangakatana.com/page.jpg"), provider.chapter(entry(), chapter("mangakatana", "https://mangakatana.com/manga/series/chapter-1"))?.pageUrls)
        assertNull(provider.chapter(entry(), chapter("mangakatana", "https://evil.invalid/manga/series/chapter-1")))
    }

    @Test fun mangakakalotUsesItsChapterReaderContainer() = runBlocking {
        val provider = MangakakalotReadingRuntimeProvider { url -> Jsoup.parse("""<div class="container-chapter-reader"><img data-src="/p1.jpg"><img src="/p2.jpg"></div>""", url) }
        assertEquals(2, provider.chapter(entry(), chapter("mangakakalot", "https://mangakakalot.com/chapter/series/chapter_1"))?.pageUrls?.size)
    }

    @Test fun manganatoUsesItsPageChapterReader() = runBlocking {
        val provider = ManganatoReadingRuntimeProvider { url -> Jsoup.parse("""<div class="page-chapter"><img data-original="/p1.jpg"></div>""", url) }
        assertEquals(listOf("https://chapmanganato.to/p1.jpg"), provider.chapter(entry(), chapter("manganato", "https://chapmanganato.to/manga-abc/chapter-1"))?.pageUrls)
    }

    @Test fun mangaReadUsesItsReadingContentSelector() = runBlocking {
        val provider = MangaReadReadingRuntimeProvider { url -> Jsoup.parse("""<div class="reading-content"><img data-src="/p1.jpg"></div>""", url) }
        assertEquals(listOf("https://mangaread.org/p1.jpg"), provider.chapter(entry(), chapter("mangaread", "https://mangaread.org/manga/series/chapter-1"))?.pageUrls)
    }

    @Test fun mangabatUsesItsDedicatedChapterReader() = runBlocking {
        val provider = MangaBatReadingRuntimeProvider { url -> Jsoup.parse("""<div class="container-chapter-reader"><img data-src="/p1.jpg"></div>""", url) }
        assertEquals(listOf("https://mangabat.com/p1.jpg"), provider.chapter(entry(), chapter("mangabat", "https://mangabat.com/chapter-series/chapter-1"))?.pageUrls)
    }

    @Test fun mangaHereUsesViewerImages() = runBlocking {
        val provider = MangaHereReadingRuntimeProvider { url -> Jsoup.parse("""<div id="viewer"><img src="/p1.jpg"></div>""", url) }
        assertEquals(listOf("https://mangahere.cc/p1.jpg"), provider.chapter(entry(), chapter("mangahere", "https://mangahere.cc/manga/series/chapter-1"))?.pageUrls)
    }
}
