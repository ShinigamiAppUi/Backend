package streamix.readingcatalog

import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class EnglishManhwaSiteRuntimeProviderTest {
    private fun entry() = ReadingCatalogEntry("h", ReadingKind.MANHWA, "Manhwa", "https://example.invalid/title", ReadingEntryOrigin.MANUAL)
    private fun chapter(id: String, url: String) = ReadingChapterEntry("c", "1", sources = listOf(ReadingChapterSource("s", id, url)))

    @Test fun manhwaGalaxyUsesYiiReaderStructure() = runBlocking {
        val provider = ManhwaGalaxyReadingRuntimeProvider { url -> Jsoup.parse("""<div class="reading-content"><img src="/p.jpg"></div>""", url) }
        assertEquals(listOf("https://manhwagalaxy.com/p.jpg"), provider.chapter(entry(), chapter("manhwagalaxy", "https://manhwagalaxy.com/chapter/title"))?.pageUrls)
    }

    @Test fun manhwaHubUsesPageBreaks() = runBlocking {
        val provider = ManhwaHubReadingRuntimeProvider { url -> Jsoup.parse("""<div class="page-break"><img src="/p.jpg"></div>""", url) }
        assertEquals(listOf("https://manhwahub.to/p.jpg"), provider.chapter(entry(), chapter("manhwahub", "https://manhwahub.to/chapter/title"))?.pageUrls)
    }

    @Test fun manhwaHubUsesMadaraReaderAreaOnCurrentDomain() = runBlocking {
        val provider = ManhwaHubReadingRuntimeProvider { url -> Jsoup.parse("""<div class="reading-content"><img data-original="/original.jpg" src="data:image/gif;base64,placeholder"></div>""", url) }
        assertEquals(listOf("https://manhwahub.to/original.jpg"), provider.chapter(entry(), chapter("manhwahub", "https://manhwahub.to/manhwa/title/chapter-1"))?.pageUrls)
    }

    @Test fun manhwaNexUsesReaderArea() = runBlocking {
        val provider = ManhwaNexReadingRuntimeProvider { url -> Jsoup.parse("""<div id="readerarea"><img src="/p.jpg"></div>""", url) }
        assertEquals(listOf("https://manhwanex.com/p.jpg"), provider.chapter(entry(), chapter("manhwanex", "https://manhwanex.com/chapter/title"))?.pageUrls)
    }

    @Test fun manhwaOnlineUsesYiiReaderStructure() = runBlocking {
        val provider = ManhwaOnlineReadingRuntimeProvider { url -> Jsoup.parse("""<div class="read-content"><img src="/p.jpg"></div>""", url) }
        assertEquals(listOf("https://manhwaonline.org/p.jpg"), provider.chapter(entry(), chapter("manhwaonline", "https://manhwaonline.org/chapter/title"))?.pageUrls)
    }

    @Test fun manhwaZoneUsesLazyImageUrls() = runBlocking {
        val provider = ManhwaZoneReadingRuntimeProvider { url -> Jsoup.parse("""<img class="lazy-image" data-src="/p.jpg">""", url) }
        assertEquals(listOf("https://manhwazone.com/p.jpg"), provider.chapter(entry(), chapter("manhwazone", "https://manhwazone.com/chapter/title"))?.pageUrls)
    }

    @Test fun manhwaHubFallsBackToDataOriginal() = runBlocking {
        val provider = ManhwaHubReadingRuntimeProvider { url -> Jsoup.parse("""<div class="page-break"><img data-original="/original.jpg" src="data:image/gif;base64,placeholder"></div>""", url) }
        assertEquals(listOf("https://manhwahub.to/original.jpg"), provider.chapter(entry(), chapter("manhwahub", "https://manhwahub.to/chapter/title"))?.pageUrls)
    }

    @Test fun manhwaNexFallsBackToDataOriginal() = runBlocking {
        val provider = ManhwaNexReadingRuntimeProvider { url -> Jsoup.parse("""<div id="readerarea"><img data-original="/original.jpg" src="data:image/gif;base64,placeholder"></div>""", url) }
        assertEquals(listOf("https://manhwanex.com/original.jpg"), provider.chapter(entry(), chapter("manhwanex", "https://manhwanex.com/chapter/title"))?.pageUrls)
    }

    @Test fun manhwaZoneFallsBackToDataOriginalWhenDataSrcIsMissing() = runBlocking {
        val provider = ManhwaZoneReadingRuntimeProvider { url -> Jsoup.parse("""<img class="lazy-image" data-original="/original.jpg" src="data:image/gif;base64,placeholder">""", url) }
        assertEquals(listOf("https://manhwazone.com/original.jpg"), provider.chapter(entry(), chapter("manhwazone", "https://manhwazone.com/chapter/title"))?.pageUrls)
    }

    @Test fun manhwaZoneFallsBackToOriginalWhenLazySourceIsPlaceholder() = runBlocking {
        val provider = ManhwaZoneReadingRuntimeProvider { url -> Jsoup.parse("""<img class="lazy-image" data-src="data:image/gif;base64,placeholder" data-original="/original.jpg">""", url) }
        assertEquals(listOf("https://manhwazone.com/original.jpg"), provider.chapter(entry(), chapter("manhwazone", "https://manhwazone.com/chapter/title"))?.pageUrls)
    }

    @Test fun ravenScansUsesReaderArea() = runBlocking {
        val provider = RavenScansReadingRuntimeProvider { url -> Jsoup.parse("""<div id="readerarea"><img src="/p.jpg"></div>""", url) }
        assertEquals(listOf("https://ravenscans.org/p.jpg"), provider.chapter(entry(), chapter("raven-scans", "https://ravenscans.org/chapter/title"))?.pageUrls)
    }

    @Test fun rageScansUsesReaderArea() = runBlocking {
        val provider = RageScansReadingRuntimeProvider { url -> Jsoup.parse("""<div id="readerarea"><img src="/p.jpg"></div>""", url) }
        assertEquals(listOf("https://ragescans.com/p.jpg"), provider.chapter(entry(), chapter("rage-scans", "https://ragescans.com/chapter/title"))?.pageUrls)
    }

    @Test fun manhwaGalaxyFallsBackToLazyImageAttributes() = runBlocking {
        val provider = ManhwaGalaxyReadingRuntimeProvider { url -> Jsoup.parse("""<div class="reading-content"><img data-src="/lazy.jpg"></div>""", url) }
        assertEquals(listOf("https://manhwagalaxy.com/lazy.jpg"), provider.chapter(entry(), chapter("manhwagalaxy", "https://manhwagalaxy.com/chapter/title"))?.pageUrls)
    }

    @Test fun manhwaOnlineFallsBackToLazyImageAttributes() = runBlocking {
        val provider = ManhwaOnlineReadingRuntimeProvider { url -> Jsoup.parse("""<div class="read-content"><img data-lazy-src="/lazy.jpg"></div>""", url) }
        assertEquals(listOf("https://manhwaonline.org/lazy.jpg"), provider.chapter(entry(), chapter("manhwaonline", "https://manhwaonline.org/chapter/title"))?.pageUrls)
    }
}
