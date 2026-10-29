package streamix.readingcatalog

import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class MoreSiteSpecificReadingRuntimeProviderTest {
    private fun entry() = ReadingCatalogEntry("m", ReadingKind.MANHWA, "Manhwa", "https://example.invalid/title", ReadingEntryOrigin.MANUAL)
    private fun chapter(id: String, url: String) = ReadingChapterEntry("c", "1", sources = listOf(ReadingChapterSource("s", id, url)))

    @Test fun webtoonXYZFallsBackToDataOriginal()=runBlocking{val p=WebtoonXYZReadingRuntimeProvider{u->Jsoup.parse("""<div id="readerarea"><img data-original="/original.jpg" src="data:image/gif;base64,placeholder"></div>""",u)};val e=ReadingCatalogEntry("m",ReadingKind.MANHWA,"M","https://example.invalid/title",ReadingEntryOrigin.MANUAL);val c=ReadingChapterEntry("c","1",sources=listOf(ReadingChapterSource("s","webtoonxyz","https://www.webtoon.xyz/read/title/chapter-1")));assertEquals(listOf("https://www.webtoon.xyz/original.jpg"),p.chapter(e,c)?.pageUrls)}
 @Test fun thunderScansFallsBackToDataOriginal()=runBlocking{val p=ThunderScansReadingRuntimeProvider{u->Jsoup.parse("""<div class="reading-content"><img data-original="/original.jpg" src="data:image/gif;base64,placeholder"></div>""",u)};val e=ReadingCatalogEntry("m",ReadingKind.MANHWA,"M","https://example.invalid/title",ReadingEntryOrigin.MANUAL);val c=ReadingChapterEntry("c","1",sources=listOf(ReadingChapterSource("s","thunder-scans","https://en-thunderscans.com/chapter/title")));assertEquals(listOf("https://en-thunderscans.com/original.jpg"),p.chapter(e,c)?.pageUrls)}
 @Test fun dragonTeaUsesMadaraReaderArea() = runBlocking {
        val p = DragonTeaReadingRuntimeProvider { u -> Jsoup.parse("""<div class="reading-content"><img data-src="/p.jpg"></div>""", u) }
        assertEquals(listOf("https://dragontea.ink/p.jpg"), p.chapter(entry(), chapter("dragontea", "https://dragontea.ink/chapter/title"))?.pageUrls)
    }
    @Test fun toonilyUsesReaderArea() = runBlocking {
        val p = ToonilyReadingRuntimeProvider { u -> Jsoup.parse("""<div class="reading-content"><img src="/p.jpg"></div>""", u) }
        assertEquals(listOf("https://toonily.com/p.jpg"), p.chapter(entry(), chapter("toonily", "https://toonily.com/chapter/title"))?.pageUrls)
    }
    @Test fun manhwaBuddyUsesYiimangaReader() = runBlocking {
        val p = ManhwaBuddyReadingRuntimeProvider { u -> Jsoup.parse("""<div class="reading-content"><img src="/p.jpg"></div>""", u) }
        assertEquals(listOf("https://manhwabuddy.com/p.jpg"), p.chapter(entry(), chapter("manhwabuddy", "https://manhwabuddy.com/chapter/title"))?.pageUrls)
    }
    @Test fun webtoonXYZRequiresReadRoute() = runBlocking {
        val p = WebtoonXYZReadingRuntimeProvider { u -> Jsoup.parse("""<div id="readerarea"><img data-src="/p.jpg"></div>""", u) }
        assertEquals(listOf("https://www.webtoon.xyz/p.jpg"), p.chapter(entry(), chapter("webtoonxyz", "https://www.webtoon.xyz/read/title/chapter-1"))?.pageUrls)
    }
    @Test fun thunderScansUsesReaderArea() = runBlocking {
        val p = ThunderScansReadingRuntimeProvider { u -> Jsoup.parse("""<div id="readerarea"><img src="/p.jpg"></div>""", u) }
        assertEquals(listOf("https://en-thunderscans.com/p.jpg"), p.chapter(entry(), chapter("thunder-scans", "https://en-thunderscans.com/chapter/title"))?.pageUrls)
    }
    @Test fun sayManhwaUsesReaderPages() = runBlocking {
        val p = SayManhwaReadingRuntimeProvider { u -> Jsoup.parse("""<div class="reader-pages"><img src="/p.jpg"></div>""", u) }
        assertEquals(listOf("https://saymanhwa.com/p.jpg"), p.chapter(entry(), chapter("saymanhwa", "https://saymanhwa.com/chapter/title"))?.pageUrls)
    }
    @Test fun komikNextGUsesSplicedComicReader() = runBlocking {
        val p = KomikNextGOnlineReadingRuntimeProvider { u -> Jsoup.parse("""<div id="spliced-comic"><img src="/p.jpg"></div>""", u) }
        assertEquals(listOf("https://komiknextgonline.com/p.jpg"), p.chapter(entry(), chapter("komik-next-g-online", "https://komiknextgonline.com/chapter/title"))?.pageUrls)
    }

    @Test fun sayManhwaFallsBackToLazyImageAttributes() = runBlocking {
        val p = SayManhwaReadingRuntimeProvider { u -> Jsoup.parse("""<div class="reader-pages"><img data-src="/lazy.jpg"></div>""", u) }
        assertEquals(listOf("https://saymanhwa.com/lazy.jpg"), p.chapter(entry(), chapter("saymanhwa", "https://saymanhwa.com/chapter/title"))?.pageUrls)
    }

    @Test fun komikNextGOnlineFallsBackToLazyImageAttributes() = runBlocking {
        val p = KomikNextGOnlineReadingRuntimeProvider { u -> Jsoup.parse("""<div id="spliced-comic"><img data-original="/lazy.jpg"></div>""", u) }
        assertEquals(listOf("https://komiknextgonline.com/lazy.jpg"), p.chapter(entry(), chapter("komik-next-g-online", "https://komiknextgonline.com/chapter/title"))?.pageUrls)
    }
}
