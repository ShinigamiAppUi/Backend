package streamix.readingcatalog

import java.net.URI
import org.jsoup.nodes.Document
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MangaReaderReadingRuntimeProvider(private val documentFetcher: ((String)->Document?)?=null):ReadingRuntimeProvider{
 override val id="mangareader"
 override fun supports(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry)=entry.kind==ReadingKind.MANGA&&chapter.sources.any{it.enabled&&it.providerId.equals(id,true)&&valid(it.url)}
 override suspend fun chapter(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry):ReadingContent?=withContext(Dispatchers.IO){
  val source=chapter.sources.firstOrNull{it.enabled&&it.providerId.equals(id,true)&&valid(it.url)}?:return@withContext null
  val doc=try{documentFetcher?.invoke(source.url)?:if(documentFetcher==null)fetch(source.url)else null}catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){null}?:return@withContext null
  val pages=doc.select(".reader-pages img, .page-break img, #readerarea img").mapNotNull{img->sequenceOf("data-src","data-original","src").map(String::trim).firstNotNullOfOrNull{k->img.attr(k).takeIf{it.isNotBlank()&&!it.startsWith("data:", ignoreCase = true)}}?.let{runCatching{URI(source.url).resolve(it).toString()}.getOrNull()}?.takeIf(::validImage)}.normalizedReadingPageUrls()
  pages.takeIf{it.isNotEmpty()}?.let{ReadingContent(entry.kind,"reading-provider:$id",source.url,it,format="image-pages")}
 }
 private fun valid(v:String)=isSafeReadingChapterUrl(v,setOf("mangareader.site")){it.contains("chapter",true)}
 private fun validImage(v: String) = isSafeReadingImageUrl(v)
 private fun fetch(v:String):Document?=fetchReadingSiteHtml(v)
}

private fun fetchReadingSiteHtml(value:String):Document? = fetchReadingHtmlDocument(
 value,
 mapOf("User-Agent" to "ShinigamiReadingRuntime/1.0", "Accept" to "text/html,application/xhtml+xml"),
)
