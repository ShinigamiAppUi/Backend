package streamix.readingcatalog

import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class AdditionalSiteSpecificReadingRuntimeProviderTest {
    private fun entry() = ReadingCatalogEntry("m", ReadingKind.MANHWA, "Manhwa", "https://example.invalid/title", ReadingEntryOrigin.MANUAL)
    private fun chapter(id: String, url: String) = ReadingChapterEntry("c", "1", sources = listOf(ReadingChapterSource("s", id, url)))

    @Test fun noromaxFallsBackToDataOriginal() = runBlocking {
        val provider = NoromaxReadingRuntimeProvider { url -> Jsoup.parse("""<div id="readerarea"><img data-original="/original.jpg" src="data:image/gif;base64,placeholder"></div>""", url) }
        assertEquals(listOf("https://noromax02.my.id/original.jpg"), provider.chapter(entry(), chapter("noromax", "https://noromax02.my.id/chapter/title"))?.pageUrls)
    }

    @Test fun otaScansFallsBackToDataOriginal() = runBlocking {
        val provider = OtaScansReadingRuntimeProvider { url -> Jsoup.parse("""<div id="readerarea"><img data-original="/original.jpg" src="data:image/gif;base64,placeholder"></div>""", url) }
        assertEquals(listOf("https://yurilab.top/original.jpg"), provider.chapter(entry(), chapter("ota-scans", "https://yurilab.top/chapter/title"))?.pageUrls)
    }

    @Test fun mangaDistrictFallsBackToDataOriginal() = runBlocking {
        val provider = MangaDistrictReadingRuntimeProvider { url -> Jsoup.parse("""<div class="reading-content"><img data-original="/original.jpg" src="data:image/gif;base64,placeholder"></div>""", url) }
        assertEquals(listOf("https://mangadistrict.com/original.jpg"), provider.chapter(entry(), chapter("mangadistrict", "https://mangadistrict.com/chapter/title"))?.pageUrls)
    }

    @Test fun noromaxUsesReaderArea() = runBlocking {
        val provider = NoromaxReadingRuntimeProvider { url -> Jsoup.parse("""<div id="readerarea"><img src="/p.jpg"></div>""", url) }
        assertEquals(listOf("https://noromax02.my.id/p.jpg"), provider.chapter(entry(), chapter("noromax", "https://noromax02.my.id/chapter/test"))?.pageUrls)
    }

    @Test fun sekteKomikUsesColorlibReaderMarkup() = runBlocking {
        val provider = SekteKomikReadingRuntimeProvider { url -> Jsoup.parse("""<div class="container"><div class="read-img"><img src="/p.jpg"></div></div>""", url) }
        assertEquals(listOf("https://01.sektekomik.id/p.jpg"), provider.chapter(entry(), chapter("sekte-komik", "https://01.sektekomik.id/chapter/test"))?.pageUrls)
    }

    @Test fun otaScansUsesReaderArea() = runBlocking {
        val provider = OtaScansReadingRuntimeProvider { url -> Jsoup.parse("""<div id="readerarea"><img data-src="/p.jpg"></div>""", url) }
        assertEquals(listOf("https://yurilab.top/p.jpg"), provider.chapter(entry(), chapter("ota-scans", "https://yurilab.top/chapter/test"))?.pageUrls)
    }

    @Test fun mangaDistrictUsesReaderArea() = runBlocking {
        val provider = MangaDistrictReadingRuntimeProvider { url -> Jsoup.parse("""<div id="readerarea"><img src="/p.jpg"></div>""", url) }
        assertEquals(listOf("https://mangadistrict.com/p.jpg"), provider.chapter(entry(), chapter("mangadistrict", "https://mangadistrict.com/chapter/test"))?.pageUrls)
    }

    @Test fun sekteKomikFallsBackToLazyImageAttributes() = runBlocking {
        val provider = SekteKomikReadingRuntimeProvider { url -> Jsoup.parse("""<div class="container"><div class="read-img"><img data-original="/lazy.jpg"></div></div>""", url) }
        assertEquals(listOf("https://01.sektekomik.id/lazy.jpg"), provider.chapter(entry(), chapter("sekte-komik", "https://01.sektekomik.id/chapter/test"))?.pageUrls)
    }
}
