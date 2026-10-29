package streamix.readingcatalog

import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class ManhwaSiteReadingRuntimeProviderTest {
    private fun entry() = ReadingCatalogEntry("h", ReadingKind.MANHWA, "Manhwa", "https://example.invalid/series", ReadingEntryOrigin.MANUAL)
    private fun chapter(id: String, url: String) = ReadingChapterEntry("c", "1", sources = listOf(ReadingChapterSource("s", id, url)))

    @Test fun toonilyReadsPageBreakImages() = runBlocking {
        val provider = ToonilyReadingRuntimeProvider { url -> Jsoup.parse("""<div class="page-break"><img data-src="/p.jpg"></div>""", url) }
        assertEquals(listOf("https://toonily.com/p.jpg"), provider.chapter(entry(), chapter("toonily", "https://toonily.com/chapter/test"))?.pageUrls)
    }

    @Test fun toonilyFallsBackToDataOriginal() = runBlocking {
        val provider = ToonilyReadingRuntimeProvider { url -> Jsoup.parse("""<div class="page-break"><img data-original="/original.jpg" src="data:image/gif;base64,placeholder"></div>""", url) }
        assertEquals(listOf("https://toonily.com/original.jpg"), provider.chapter(entry(), chapter("toonily", "https://toonily.com/chapter/test"))?.pageUrls)
    }

    @Test fun weebCentralFallsBackToDataOriginal() = runBlocking {
        val provider = WeebCentralReadingRuntimeProvider { url -> Jsoup.parse("""<div class="chapter-body"><img data-original="/original.jpg" src="data:image/gif;base64,placeholder"></div>""", url) }
        assertEquals(listOf("https://weebcentral.com/original.jpg"), provider.chapter(entry(), chapter("weebcentral", "https://weebcentral.com/chapters/1"))?.pageUrls)
    }

    @Test fun flameComicsFallsBackToDataOriginal() = runBlocking {
        val provider = FlameComicsReadingRuntimeProvider { url -> Jsoup.parse("""<div class="chapter-content"><img data-original="/original.jpg" src="data:image/gif;base64,placeholder"></div>""", url) }
        assertEquals(listOf("https://flamecomics.com/original.jpg"), provider.chapter(entry(), chapter("flame-comics", "https://flamecomics.com/chapter/test"))?.pageUrls)
    }

    @Test fun manhwaBuddyFallsBackToDataOriginal() = runBlocking {
        val provider = ManhwaBuddyReadingRuntimeProvider { url -> Jsoup.parse("""<div class="chapter-content"><img data-original="/original.jpg" src="data:image/gif;base64,placeholder"></div>""", url) }
        assertEquals(listOf("https://manhwabuddy.com/original.jpg"), provider.chapter(entry(), chapter("manhwabuddy", "https://manhwabuddy.com/chapter/test"))?.pageUrls)
    }

    @Test fun dragonTeaFallsBackToDataOriginal() = runBlocking {
        val provider = DragonTeaReadingRuntimeProvider { url -> Jsoup.parse("""<div class="page-break"><img data-original="/original.jpg" src="data:image/gif;base64,placeholder"></div>""", url) }
        assertEquals(listOf("https://dragontea.ink/original.jpg"), provider.chapter(entry(), chapter("dragontea", "https://dragontea.ink/chapter/test"))?.pageUrls)
    }

    @Test fun weebCentralRejectsForeignHost() = runBlocking {
        val provider = WeebCentralReadingRuntimeProvider { url -> Jsoup.parse("""<div class="chapter-body"><img src="/p.jpg"></div>""", url) }
        assertNull(provider.chapter(entry(), chapter("weebcentral", "https://evil.invalid/chapters/1")))
    }

    @Test fun flameComicsReadsChapterContentImages() = runBlocking {
        val provider = FlameComicsReadingRuntimeProvider { url -> Jsoup.parse("""<div class="chapter-content"><img src="/p.jpg"></div>""", url) }
        assertEquals(listOf("https://flamecomics.com/p.jpg"), provider.chapter(entry(), chapter("flame-comics", "https://flamecomics.com/chapter/test"))?.pageUrls)
    }

    @Test fun manhwaBuddyReadsChapterImages() = runBlocking {
        val provider = ManhwaBuddyReadingRuntimeProvider { url -> Jsoup.parse("""<div class="chapter-content"><img src="/p.jpg"></div>""", url) }
        assertEquals(listOf("https://manhwabuddy.com/p.jpg"), provider.chapter(entry(), chapter("manhwabuddy", "https://manhwabuddy.com/chapter/test"))?.pageUrls)
    }

    @Test fun dragonTeaReadsItsPageBreakImages() = runBlocking {
        val provider = DragonTeaReadingRuntimeProvider { url -> Jsoup.parse("""<div class="page-break"><img src="/p.jpg"></div>""", url) }
        assertEquals(listOf("https://dragontea.ink/p.jpg"), provider.chapter(entry(), chapter("dragontea", "https://dragontea.ink/chapter/test"))?.pageUrls)
    }
}
