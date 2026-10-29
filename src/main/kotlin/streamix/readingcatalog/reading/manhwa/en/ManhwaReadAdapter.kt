package streamix.readingcatalog

import java.net.URI
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ManhwaReadReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
 override val id="manhwaread"
 override fun supports(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry)=entry.kind==ReadingKind.MANHWA&&chapter.sources.any{it.enabled&&it.providerId.equals(id,true)&&validChapter(it.url)}
 override suspend fun chapter(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry):ReadingContent?=withContext(Dispatchers.IO){
  val source=chapter.sources.firstOrNull{it.enabled&&it.providerId.equals(id,true)&&validChapter(it.url)}?:return@withContext null
  val doc=runCatching{documentFetcher?.invoke(source.url)?:if(documentFetcher==null)fetch(source.url)else null}.getOrNull()?:return@withContext null
  val script=doc.select("script").firstNotNullOfOrNull{it.data().takeIf{d->d.contains("chapterData")&&d.contains("base")}}?:return@withContext null
  val raw=Regex("""var\s+chapterData\s*=\s*(\{.*\})""").find(script)?.groupValues?.get(1)?:return@withContext null
  val pages=runCatching{val data=JSONObject(raw);val base=data.optString("base").trimEnd('/');val decoded=String(Base64.getDecoder().decode(data.optString("data")),Charsets.UTF_8);val arr=JSONArray(decoded);(0 until arr.length()).mapNotNull{i->val part=arr.optJSONObject(i)?.optString("src")?.takeIf(String::isNotBlank)?:return@mapNotNull null;val image=if(part.startsWith("https://"))part else base+"/"+part.trimStart('/');image.takeIf(::validImage)}}.getOrNull().orEmpty().distinct()
  pages.takeIf{it.isNotEmpty()}?.let{ReadingContent(entry.kind,"reading-provider:$id",source.url,it,format="image-pages")}
 }
 private fun validChapter(v:String)=isSafeReadingChapterUrl(v,setOf("manhwaread.com","manhwaread.org")){it.startsWith("/manhwa/")}
 private fun validImage(v: String) = isSafeReadingImageUrl(v)
 private fun fetch(v:String):Document? = fetchReadingHtmlDocument(v, mapOf("User-Agent" to "ShinigamiReadingRuntime/1.0"))
}
