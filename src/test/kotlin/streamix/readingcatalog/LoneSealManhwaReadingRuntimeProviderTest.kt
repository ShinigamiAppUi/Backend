package streamix.readingcatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
class LoneSealManhwaRuntimeProviderTest {
 private fun e()=ReadingCatalogEntry("m",ReadingKind.MANHWA,"Manhwa","https://example.invalid/title",ReadingEntryOrigin.MANUAL)
 private fun c(id:String,url:String)=ReadingChapterEntry("c","1",sources=listOf(ReadingChapterSource("s",id,url)))
 @Test fun ainzUsesItsDedicatedApi()=runBlocking{var url="";val p=AinzScansIdReadingRuntimeProvider{u->url=u;"""{"chapter":{"pages":[{"image_url":"https://cdn.ainzscans01.com/1.jpg"}]}}"""};assertEquals(listOf("https://cdn.ainzscans01.com/1.jpg"),p.chapter(e(),c("ainz-scans-id","https://v3.ainzscans01.com/comic/title/chapter/1"))?.pageUrls);assertEquals("https://api.ainzscans01.com/api/series/comic/title/chapter/1",url)}
 @Test fun roseveilUsesApiPages()=runBlocking{val p=RoseveilReadingRuntimeProvider{"""{"chapter":{"pages":[{"image_url":"https://cdn.roseveil.org/1.jpg"}]}}"""};assertEquals(listOf("https://cdn.roseveil.org/1.jpg"),p.chapter(e(),c("roseveil","https://roseveil.org/comic/title/chapter/1"))?.pageUrls)}
 @Test fun soulScansUsesApiPages()=runBlocking{val p=SoulScansReadingRuntimeProvider{"""{"chapter":{"pages":[{"image_url":"https://cdn.soulscans.org/1.jpg"}]}}"""};assertEquals(listOf("https://cdn.soulscans.org/1.jpg"),p.chapter(e(),c("soul-scans","https://v1.soulscans.org/comic/title/chapter/1"))?.pageUrls)}
}
