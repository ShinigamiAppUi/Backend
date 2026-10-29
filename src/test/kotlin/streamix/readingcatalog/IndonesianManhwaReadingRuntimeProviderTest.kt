package streamix.readingcatalog

import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class IndonesianManhwaRuntimeProviderTest {
    private fun entry() = ReadingCatalogEntry("m", ReadingKind.MANHWA, "Manhwa", "https://example.invalid/title", ReadingEntryOrigin.MANUAL)
    private fun ch(id: String, url: String) = ReadingChapterEntry("c", "1", sources = listOf(ReadingChapterSource("s", id, url)))
    @Test fun natsuSkipsDataUriPlaceholderAndUsesOriginalImage() = runBlocking {
        val provider = NatsuReadingRuntimeProvider { url -> Jsoup.parse("""<main><div class="relative"><section><img data-src="data:image/gif;base64,placeholder" data-original="/original.jpg"></section></div></main>""", url) }
        assertEquals(listOf("https://natsu.one/original.jpg"), provider.chapter(entry(), ch("natsu", "https://natsu.one/title/chapter-1"))?.pageUrls)
    }

    @Test fun manhwaIndoReaderArea() = runBlocking { val p=ManhwaIndoReadingRuntimeProvider { u -> Jsoup.parse("""<div id="readerarea"><img data-src="/p.jpg"></div>""",u) }; assertEquals(listOf("https://www.manhwaindo.my/p.jpg"),p.chapter(entry(),ch("manhwa-indo","https://www.manhwaindo.my/chapter/title"))?.pageUrls) }

    @Test fun sharedHostGuardRejectsCredentialsPortsAndWrongHostsBeforeFetching() = runBlocking {
        val unsafe = listOf(
            "https://user@www.manhwaindo.my/chapter/title",
            "https://www.manhwaindo.my:444/chapter/title",
            "http://www.manhwaindo.my/chapter/title",
            "https://evil.example/chapter/title",
        )
        unsafe.forEach { url ->
            var called = false
            val provider = ManhwaIndoReadingRuntimeProvider { called = true; Jsoup.parse("""<div id="readerarea"><img src="/p.jpg"></div>""", it) }
            val chapter = ch("manhwa-indo", url)
            kotlin.test.assertEquals(false, provider.supports(entry(), chapter))
            kotlin.test.assertEquals(null, provider.chapter(entry(), chapter))
            kotlin.test.assertEquals(false, called)
        }
    }
    @Test fun izanamiSkipsDataUriPlaceholderAndUsesOriginalImage() = runBlocking {
        val provider = IzanamiScansReadingRuntimeProvider { url -> Jsoup.parse("""<div id="readerarea"><img data-lazy-src="data:image/gif;base64,placeholder" data-original="/original.jpg"></div>""", url) }
        assertEquals(listOf("https://izanamiscans.my.id/original.jpg"), provider.chapter(entry(), ch("izanami-scans", "https://izanamiscans.my.id/chapter/title"))?.pageUrls)
    }

    @Test fun rosyScansSkipsDataUriPlaceholderAndUsesOriginalImage() = runBlocking {
        val provider = RosyscansReadingRuntimeProvider { url -> Jsoup.parse("""<div id="readerarea"><img data-lazy-src="data:image/gif;base64,placeholder" data-original="/original.jpg"></div>""", url) }
        assertEquals(listOf("https://rosyscans.com/original.jpg"), provider.chapter(entry(), ch("rosyscans", "https://rosyscans.com/chapter/title"))?.pageUrls)
    }

    @Test fun holotoonUsesReaderPages() = runBlocking { val p=HolotoonReadingRuntimeProvider { u -> Jsoup.parse("""<div id="reader-pages"><img src="/p.jpg"></div>""",u) }; assertEquals(listOf("https://holodek.run/p.jpg"),p.chapter(entry(),ch("holotoon","https://holodek.run/chapter/title"))?.pageUrls) }
    @Test fun hwagoUsesAltPageImages() = runBlocking { val p=HwagoReadingRuntimeProvider { u -> Jsoup.parse("""<img alt="Page 1" src="/p.jpg">""",u) }; assertEquals(listOf("https://02.hwago.xyz/p.jpg"),p.chapter(entry(),ch("hwago","https://02.hwago.xyz/chapter/title"))?.pageUrls) }
    @Test fun lumosKomikUsesAltPageImages() = runBlocking { val p=LumosKomikReadingRuntimeProvider { u -> Jsoup.parse("""<img alt="Page 1" src="/p.jpg">""",u) }; assertEquals(listOf("https://03.lumosgg.com/p.jpg"),p.chapter(entry(),ch("lumoskomik","https://03.lumosgg.com/chapter/title"))?.pageUrls) }
    @Test fun natsuUsesRelativeSectionImages() = runBlocking { val p=NatsuReadingRuntimeProvider { u -> Jsoup.parse("""<main><div class="relative"><section><img src="/p.jpg"></section></div></main>""",u) }; assertEquals(listOf("https://natsu.one/p.jpg"),p.chapter(entry(),ch("natsu","https://natsu.one/chapter/title"))?.pageUrls) }
    @Test fun izanamiUsesReaderArea() = runBlocking { val p=IzanamiScansReadingRuntimeProvider { u -> Jsoup.parse("""<div id="readerarea"><img src="/p.jpg"></div>""",u) }; assertEquals(listOf("https://izanamiscans.my.id/p.jpg"),p.chapter(entry(),ch("izanami-scans","https://izanamiscans.my.id/chapter/title"))?.pageUrls) }
    @Test fun rosyScansUsesReaderArea() = runBlocking { val p=RosyscansReadingRuntimeProvider { u -> Jsoup.parse("""<div id="readerarea"><img src="/p.jpg"></div>""",u) }; assertEquals(listOf("https://rosyscans.com/p.jpg"),p.chapter(entry(),ch("rosyscans","https://rosyscans.com/chapter/title"))?.pageUrls) }

    @Test fun holotoonFallsBackToLazyImageAttributes() = runBlocking { val p=HolotoonReadingRuntimeProvider { u -> Jsoup.parse("""<div id="reader-pages"><img data-lazy-src="/lazy.jpg"></div>""",u) }; assertEquals(listOf("https://holodek.run/lazy.jpg"),p.chapter(entry(),ch("holotoon","https://holodek.run/chapter/title"))?.pageUrls) }
    @Test fun hwagoFallsBackToLazyImageAttributes() = runBlocking { val p=HwagoReadingRuntimeProvider { u -> Jsoup.parse("""<img alt="Page 1" data-src="/lazy.jpg">""",u) }; assertEquals(listOf("https://02.hwago.xyz/lazy.jpg"),p.chapter(entry(),ch("hwago","https://02.hwago.xyz/chapter/title"))?.pageUrls) }
    @Test fun lumosKomikFallsBackToLazyImageAttributes() = runBlocking { val p=LumosKomikReadingRuntimeProvider { u -> Jsoup.parse("""<img alt="Page 1" data-original="/lazy.jpg">""",u) }; assertEquals(listOf("https://03.lumosgg.com/lazy.jpg"),p.chapter(entry(),ch("lumoskomik","https://03.lumosgg.com/chapter/title"))?.pageUrls) }

    @Test fun manhwaIndoFallsBackToDataOriginal() = runBlocking { val p=ManhwaIndoReadingRuntimeProvider { u -> Jsoup.parse("""<div id="readerarea"><img data-original="/original.jpg" src="data:image/gif;base64,placeholder"></div>""",u) }; assertEquals(listOf("https://www.manhwaindo.my/original.jpg"),p.chapter(entry(),ch("manhwa-indo","https://www.manhwaindo.my/chapter/title"))?.pageUrls) }
    @Test fun natsuFallsBackToDataOriginal() = runBlocking { val p=NatsuReadingRuntimeProvider { u -> Jsoup.parse("""<main><div class="relative"><section><img data-original="/original.jpg" src="data:image/gif;base64,placeholder"></section></div></main>""",u) }; assertEquals(listOf("https://natsu.one/original.jpg"),p.chapter(entry(),ch("natsu","https://natsu.one/chapter/title"))?.pageUrls) }
    @Test fun izanamiFallsBackToDataOriginal() = runBlocking { val p=IzanamiScansReadingRuntimeProvider { u -> Jsoup.parse("""<div id="readerarea"><img data-original="/original.jpg" src="data:image/gif;base64,placeholder"></div>""",u) }; assertEquals(listOf("https://izanamiscans.my.id/original.jpg"),p.chapter(entry(),ch("izanami-scans","https://izanamiscans.my.id/chapter/title"))?.pageUrls) }
    @Test fun rosyScansFallsBackToDataOriginal() = runBlocking { val p=RosyscansReadingRuntimeProvider { u -> Jsoup.parse("""<div id="readerarea"><img data-original="/original.jpg" src="data:image/gif;base64,placeholder"></div>""",u) }; assertEquals(listOf("https://rosyscans.com/original.jpg"),p.chapter(entry(),ch("rosyscans","https://rosyscans.com/chapter/title"))?.pageUrls) }
}
