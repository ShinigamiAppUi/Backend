package streamix.readingcatalog
import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
class IndonesianNovelRuntimeProviderTest {
 private fun e()=ReadingCatalogEntry("n",ReadingKind.NOVEL,"Novel","https://example.invalid/novel",ReadingEntryOrigin.MANUAL)
 private fun c(id:String,url:String)=ReadingChapterEntry("c","1",sources=listOf(ReadingChapterSource("s",id,url)))
 @Test fun indoWebNovelExtractsTextLeft()=runBlocking{val p=IndoWebNovelReadingRuntimeProvider{u->Jsoup.parse("""<div class="text-left">Bab satu</div>""",u)};assertEquals("Bab satu",p.chapter(e(),c("indowebnovel","https://indowebnovel.id/chapter/1"))?.text);assertEquals(null,p.chapter(e(),c("indowebnovel","https://indowebnovel.id:8443/chapter/1")));assertEquals(null,p.chapter(e(),c("indowebnovel","https://user@indowebnovel.id/chapter/1")))}
 @Test fun mtlNovelIdExtractsChapterText()=runBlocking{val p=MtlNovelIdReadingRuntimeProvider{u->Jsoup.parse("""<div class="par">Chapter text</div>""",u)};assertEquals("Chapter text",p.chapter(e(),c("mtl-novel-id","https://mtlnovel.me/title/chapter-1"))?.text)}
 @Test fun meioNovelExtractsChapterText()=runBlocking{val p=MeioNovelReadingRuntimeProvider{u->Jsoup.parse("""<div class="chapter-text">Meio chapter</div>""",u)};assertEquals("Meio chapter",p.chapter(e(),c("meionovel","https://meionovels.com/title/chapter-1"))?.text)}
 @Test fun sakuraNovelExtractsEntryContent()=runBlocking{val p=SakuraNovelReadingRuntimeProvider{u->Jsoup.parse("""<div class="entry-content">Sakura chapter</div>""",u)};assertEquals("Sakura chapter",p.chapter(e(),c("sakuranovel","https://sakuranovel.id/chapter/1"))?.text)}
 @Test fun sekteNovelExtractsEntryContent()=runBlocking{val p=SekteNovelReadingRuntimeProvider{u->Jsoup.parse("""<div class="entry-content">Sekte chapter</div>""",u)};assertEquals("Sekte chapter",p.chapter(e(),c("sekte-novel","https://sektenovel.web.id/item/title-bab-1"))?.text)}
 @Test fun sekteNovelUsesTadamiLightNovelWpReader()= runBlocking {
  val p=SekteNovelReadingRuntimeProvider { u -> Jsoup.parse("""<div class="epcontent"><p>Isi chapter Sekte</p></div><div class="bottomnav">Navigasi</div>""",u) }
  assertEquals("Isi chapter Sekte",p.chapter(e(),c("sekte-novel","https://sektenovel.web.id/title/bab-1"))?.text)
 }
 @Test fun vanovelExtractsChapterContent()=runBlocking{val p=VanovelReadingRuntimeProvider{u->Jsoup.parse("""<div class="chapter-content">Vanovel chapter</div>""",u)};assertEquals("Vanovel chapter",p.chapter(e(),c("vanovel","https://vanovel.com/chapter/title-1"))?.text)}
 @Test fun wbnoveExtractsEntryContent()=runBlocking{val p=WBNoveReadingRuntimeProvider{u->Jsoup.parse("""<div class="entry-content">WB chapter</div>""",u)};assertEquals("WB chapter",p.chapter(e(),c("wbnove","https://wbnovel.com/chapter/title-1"))?.text)}
 @Test fun novelBookIdUsesTadamiMadaraReaderContent()=runBlocking {
  val p=NovelBookIdReadingRuntimeProvider { u -> Jsoup.parse("""<div class="text-left">NovelBook chapter</div><footer>navigation</footer>""",u) }
  assertEquals("NovelBook chapter",p.chapter(e(),c("novelbookid","https://novelbook.id/title/chapter-1"))?.text)
  assertEquals("NovelBook chapter",p.chapter(e(),c("novelbookid","https://novelbook.id/read/title/1"))?.text)
  assertEquals(null,p.chapter(e(),c("novelbookid","https://novelbook.id.evil.example/title/chapter-1")))
  assertEquals(null,p.chapter(e(),c("novelbookid","https://novelbook.id:8443/title/chapter-1")))
 }
 @Test fun wbnoveFallsBackWhenEarlierReaderSelectorIsEmpty()=runBlocking{val p=WBNoveReadingRuntimeProvider{u->Jsoup.parse("""<div class="text-left"></div><div class="entry-content">Fallback chapter</div>""",u)};assertEquals("Fallback chapter",p.chapter(e(),c("wbnove","https://wbnovel.com/chapter/title-1"))?.text)}
    
    @Test fun novelTextExtractionPreservesParagraphBoundariesAndRemovesNavigationNoise() {
        val document = Jsoup.parse("""<article>
            <p>First paragraph &amp; details.</p>
            <p>Second paragraph<br>continues here.</p>
            <nav>Next chapter</nav>
            <div class="advertisement">Buy now</div>
            <script>ignored()</script>
        </article>""")
        val content = extractReadingNovelText(document.selectFirst("article")!!)
        assertEquals("First paragraph & details.\n\nSecond paragraph\ncontinues here.", content)
    }
 
    @Test fun novelTextExtractionRemovesCommonAdAndChapterChromeWithoutFlatteningBody() {
        val document = Jsoup.parse("""<article class="chapter-content">
            <p>Opening paragraph.</p>
            <div class="ad-container">Sponsored filler</div>
            <p>Middle paragraph <em>with emphasis</em>.</p>
            <div class="bottomnav"><a href="/next">Next chapter</a></div>
            <div id="disqus_thread">Comments</div>
            <p>Closing paragraph.</p>
        </article>""")
        assertEquals(
            "Opening paragraph.\n\nMiddle paragraph with emphasis.\n\nClosing paragraph.",
            extractReadingNovelText(document.selectFirst("article")!!)
        )
    }

    @Test fun novelSelectorPriorityFallsThroughWhenEarlierContainerHasOnlyNavigationNoise() {
        val document = Jsoup.parse("""<div class="text-left"><nav>Next chapter</nav></div>
            <div class="entry-content"><p>Actual chapter body.</p></div>""")
        assertEquals("Actual chapter body.", extractFirstReadingNovelText(document, ".text-left, .entry-content"))
    }

    @Test fun indoWebNovelSkipsAdsadsAndFallsThroughToRealChapterBody() = runBlocking {
        val provider = IndoWebNovelReadingRuntimeProvider { url ->
            Jsoup.parse("""<div class="adsads"><p>Sponsored content only</p></div>
                <div class="text-left"><p>Chapter opening.</p></div>""", url)
        }
        assertEquals(
            "Chapter opening.",
            provider.chapter(e(), c("indowebnovel", "https://indowebnovel.id/chapter/1"))?.text
        )
    }

    @Test fun novelTextExtractionRemovesIndonesianAdWrappersAndWordPressChrome() {
        val document = Jsoup.parse("""<article class="entry-content">
            <p>Actual chapter paragraph.</p>
            <div class="adsads">Iklan promosi</div>
            <div class="reader-navigation">Bab sebelumnya / berikutnya</div>
            <div class="comments-area">Komentar pembaca</div>
            <p>Final paragraph.</p>
        </article>""")
        assertEquals(
            "Actual chapter paragraph.\n\nFinal paragraph.",
            extractReadingNovelText(document.selectFirst("article")!!)
        )
    }


    @Test fun novelTextExtractionRejectsSelectedAdWrapperRoot() {
        val ad = Jsoup.parse("""<div class="adsads"><p>Sponsored content only</p></div>""").selectFirst(".adsads")!!
        assertEquals("", extractReadingNovelText(ad))
    }
}
