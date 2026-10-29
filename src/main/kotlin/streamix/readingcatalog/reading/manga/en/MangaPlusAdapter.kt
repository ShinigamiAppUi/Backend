package streamix.readingcatalog

import java.net.URI
import org.jsoup.nodes.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MangaPlusReadingRuntimeProvider(private val documentFetcher: ((String)->Document?)?=null):ReadingRuntimeProvider{
 override val id="mangaplus"
 override fun supports(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry)=entry.kind==ReadingKind.MANGA&&chapter.sources.any{it.enabled&&it.providerId.equals(id,true)&&valid(it.url)}
 override suspend fun chapter(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry):ReadingContent?=withContext(Dispatchers.IO){
  val source=chapter.sources.firstOrNull{it.enabled&&it.providerId.equals(id,true)&&valid(it.url)}?:return@withContext null
  val doc=runCatching{documentFetcher?.invoke(source.url)?:if(documentFetcher==null)fetch(source.url)else null}.getOrNull()?:return@withContext null
  val pages=doc.select(".viewer-container img, .reader-page img, img.manga-page").mapNotNull{img->sequenceOf("data-lazy-src","data-src","data-original","src").map{img.attr(it).trim()}.firstNotNullOfOrNull{raw->raw.takeIf{it.isNotBlank()&&!it.startsWith("data:")}?.let{runCatching{URI(source.url).resolve(it).toString()}.getOrNull()}}?.takeIf(::validImage)}.distinct()
  pages.takeIf{it.isNotEmpty()}?.let{ReadingContent(entry.kind,"reading-provider:$id",source.url,it,format="image-pages")}
 }
 private fun valid(v:String)=isSafeReadingChapterUrl(v,setOf("mangaplus.shueisha.co.jp")){it.startsWith("/viewer/")||it.startsWith("/titles/")}
 private fun validImage(v: String) = isSafeReadingImageUrl(v)
 private fun fetch(v:String):Document?=fetchReadingSiteHtml(v)
}

private fun fetchReadingSiteHtml(value:String):Document? = fetchReadingHtmlDocument(
 value,
 mapOf("User-Agent" to "ShinigamiReadingRuntime/1.0", "Accept" to "text/html,application/xhtml+xml"),
)
