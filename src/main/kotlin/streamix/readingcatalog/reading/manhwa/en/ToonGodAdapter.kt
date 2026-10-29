package streamix.readingcatalog

import java.net.URI
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ToonGodReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
 override val id="toongod"
 override fun supports(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry)=entry.kind==ReadingKind.MANHWA&&chapter.sources.any{it.enabled&&it.providerId.equals(id,true)&&validChapter(it.url)}
 override suspend fun chapter(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry):ReadingContent?=withContext(Dispatchers.IO){
  val source=chapter.sources.firstOrNull{it.enabled&&it.providerId.equals(id,true)&&validChapter(it.url)}?:return@withContext null
  val doc=runCatching{documentFetcher?.invoke(source.url)?:if(documentFetcher==null)fetch(source.url)else null}.getOrNull()?:return@withContext null
  val pages=doc.select("#readerarea img").mapNotNull{img->sequenceOf("data-lazy-src","data-src","src").map(String::trim).firstNotNullOfOrNull{k->img.attr(k).takeIf{it.isNotBlank()&&!it.startsWith("data:")}}?.let{runCatching{URI(source.url).resolve(it).toString()}.getOrNull()}?.takeIf(::validImage)}.distinct()
  pages.takeIf{it.isNotEmpty()}?.let{ReadingContent(entry.kind,"reading-provider:$id",source.url,it,format="image-pages")}
 }
 private fun validChapter(v:String)=isSafeReadingChapterUrl(v,setOf("www.toongod.org")){it.contains("chapter",true)}
 private fun validImage(v: String) = isSafeReadingImageUrl(v)
 private fun fetch(v:String):Document? = fetchReadingHtmlDocument(v, mapOf("User-Agent" to "ShinigamiReadingRuntime/1.0"))
}
