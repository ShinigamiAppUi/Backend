package streamix.readingcatalog

import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class IndonesianThemeReaderAdapterTest {
    private fun entry() = ReadingCatalogEntry("m", ReadingKind.MANGA, "Komik", "https://example.invalid/title", ReadingEntryOrigin.MANUAL)
    private fun chapter(id: String, url: String) = ReadingChapterEntry("c", "1", sources = listOf(ReadingChapterSource("s", id, url)))

    @Test fun kiryuuUsesNatsuIdImageStructure() = runBlocking {
        val provider = KiryuuReadingRuntimeProvider { url -> Jsoup.parse("""<main><div class="relative"><section><img src="/p.jpg"></section></div></main>""", url) }
        assertEquals(listOf("https://v7.kiryuu.to/p.jpg"), provider.chapter(entry(), chapter("kiryuu", "https://v7.kiryuu.to/title-chapter-1"))?.pageUrls)
    }

    @Test fun komikStationUsesReaderArea() = runBlocking {
        val provider = KomikStationReadingRuntimeProvider { url -> Jsoup.parse("""<div id="readerarea"><img data-src="/p.jpg"></div>""", url) }
        assertEquals(listOf("https://komikstation.org/p.jpg"), provider.chapter(entry(), chapter("komik-station", "https://komikstation.org/chapter/title"))?.pageUrls)
    }

    @Test fun kiryuuFallsBackToDataOriginal() = runBlocking {
        val provider = KiryuuReadingRuntimeProvider { url -> Jsoup.parse("""<main><div class="relative"><section><img data-original="/original.jpg" src="data:image/gif;base64,placeholder"></section></div></main>""", url) }
        assertEquals(listOf("https://v7.kiryuu.to/original.jpg"), provider.chapter(entry(), chapter("kiryuu", "https://v7.kiryuu.to/title-chapter-1"))?.pageUrls)
    }

    @Test fun komikStationFallsBackToDataOriginal() = runBlocking {
        val provider = KomikStationReadingRuntimeProvider { url -> Jsoup.parse("""<div id="readerarea"><img data-original="/original.jpg" src="data:image/gif;base64,placeholder"></div>""", url) }
        assertEquals(listOf("https://komikstation.org/original.jpg"), provider.chapter(entry(), chapter("komik-station", "https://komikstation.org/chapter/title"))?.pageUrls)
    }

    @Test fun lepoyTlFallsBackToDataOriginal() = runBlocking {
        val provider = LepoyTlReadingRuntimeProvider { url -> Jsoup.parse("""<div id="reader"><img data-original="/original.jpg" src="data:image/gif;base64,placeholder"></div>""", url) }
        assertEquals(listOf("https://www.lepoytl.my.id/original.jpg"), provider.chapter(entry(), chapter("lepoytl", "https://www.lepoytl.my.id/chapter/title"))?.pageUrls)
    }

    @Test fun apkomikUsesReaderArea() = runBlocking {
        val provider = ApkomikReadingRuntimeProvider { url -> Jsoup.parse("""<div id="readerarea"><img src="/p.jpg"></div>""", url) }
        assertEquals(listOf("https://01.apkomik.com/p.jpg"), provider.chapter(entry(), chapter("apkomik", "https://01.apkomik.com/chapter/title"))?.pageUrls)
    }

    @Test fun apkomikSupportsAlternateReaderAndLazyOriginalImage() = runBlocking {
        val provider = ApkomikReadingRuntimeProvider { url ->
            Jsoup.parse("""<div class="reading-content"><img data-original="/lazy/page-1.jpg"></div><div class="page-break"><img src="/lazy/page-2.jpg"></div>""", url)
        }
        assertEquals(
            listOf("https://01.apkomik.com/lazy/page-1.jpg", "https://01.apkomik.com/lazy/page-2.jpg"),
            provider.chapter(entry(), chapter("apkomik", "https://01.apkomik.com/chapter/title"))?.pageUrls,
        )
    }

    @Test fun apkomikRejectsLookalikeHostsAndNonHttpsChapterUrls() = runBlocking {
        val provider = ApkomikReadingRuntimeProvider { url -> Jsoup.parse("""<div id="readerarea"><img src="/p.jpg"></div>""", url) }
        val wrongHost = chapter("apkomik", "https://01.apkomik.com.evil.example/chapter/title")
        val plainHttp = chapter("apkomik", "http://01.apkomik.com/chapter/title")
        assertFalse(provider.supports(entry(), wrongHost))
        assertFalse(provider.supports(entry(), plainHttp))
        assertNull(provider.chapter(entry(), wrongHost))
        assertNull(provider.chapter(entry(), plainHttp))
    }

    @Test fun lepoyTlUsesReaderContainer() = runBlocking {
        val provider = LepoyTlReadingRuntimeProvider { url -> Jsoup.parse("""<div id="reader"><img src="/p.jpg"></div>""", url) }
        assertEquals(listOf("https://www.lepoytl.my.id/p.jpg"), provider.chapter(entry(), chapter("lepoytl", "https://www.lepoytl.my.id/chapter/title"))?.pageUrls)
    }
}
