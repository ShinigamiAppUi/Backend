package streamix.readingcatalog
import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
class MoreEnglishManhwaRuntimeProviderTest {
 @Test fun manhwaReadDecodesBase64ChapterData()=runBlocking{val p=ManhwaReadReadingRuntimeProvider{u->Jsoup.parse("""<script>var chapterData = {"data":"W3sic3JjIjoiMS5qcGcifV0=","base":"https://cdn.manhwaread.com"}</script>""",u)};val e=ReadingCatalogEntry("m",ReadingKind.MANHWA,"M","https://manhwaread.com/manhwa/title",ReadingEntryOrigin.MANUAL);val c=ReadingChapterEntry("c","1",sources=listOf(ReadingChapterSource("s","manhwaread","https://manhwaread.com/manhwa/title/chapter-1")));assertEquals(listOf("https://cdn.manhwaread.com/1.jpg"),p.chapter(e,c)?.pageUrls)}
 @Test fun toonGodUsesReaderArea()=runBlocking{val p=ToonGodReadingRuntimeProvider{u->Jsoup.parse("""<div id="readerarea"><img data-src="/p.jpg"></div>""",u)};val e=ReadingCatalogEntry("m",ReadingKind.MANHWA,"M","https://www.toongod.org/webtoons/title",ReadingEntryOrigin.MANUAL);val c=ReadingChapterEntry("c","1",sources=listOf(ReadingChapterSource("s","toongod","https://www.toongod.org/webtoons/title/chapter-1")));assertEquals(listOf("https://www.toongod.org/p.jpg"),p.chapter(e,c)?.pageUrls)}
 @Test fun bothAdaptersRejectCredentialedAndNonstandardPortChapterUrls() {
  val manhwaRead=ManhwaReadReadingRuntimeProvider{Jsoup.parse("<div></div>",it)}
  val toonGod=ToonGodReadingRuntimeProvider{Jsoup.parse("<div></div>",it)}
  val cases=listOf(
   Triple(manhwaRead,"manhwaread",ReadingKind.MANHWA),
   Triple(toonGod,"toongod",ReadingKind.MANHWA)
  )
  val urls=listOf(
   "https://manhwaread.com/manhwa/title/chapter-1" to true,
   "https://user@manhwaread.com/manhwa/title/chapter-1" to false,
   "https://manhwaread.com:8443/manhwa/title/chapter-1" to false,
   "https://www.toongod.org/webtoons/title/chapter-1" to true,
   "https://user@www.toongod.org/webtoons/title/chapter-1" to false,
   "https://www.toongod.org:8443/webtoons/title/chapter-1" to false
  )
  for ((provider,id,kind) in cases) {
   val providerUrls=urls.filter{it.first.contains(if(id=="manhwaread")"manhwaread" else "toongod")}
   for((url,valid) in providerUrls){
    val entry=ReadingCatalogEntry("m",kind,"M",url,ReadingEntryOrigin.MANUAL)
    val chapter=ReadingChapterEntry("c","1",sources=listOf(ReadingChapterSource("s",id,url)))
    if(valid) assertTrue(provider.supports(entry,chapter),"$id rejected $url")
    else assertFalse(provider.supports(entry,chapter),"$id accepted $url")
   }
  }
 }

}
