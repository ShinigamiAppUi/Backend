package streamix.readingcatalog

import java.net.URI
import org.jsoup.nodes.Document
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class JNovelClubReadingRuntimeProvider(private val documentFetcher: ((String)->Document?)?=null):ReadingRuntimeProvider{
 override val id="j-novel-club"
 override fun supports(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry)=entry.kind==ReadingKind.NOVEL&&chapter.sources.any{it.enabled&&it.providerId.equals(id,true)&&valid(it.url)}
 override suspend fun chapter(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry):ReadingContent?=withContext(Dispatchers.IO){
  if(entry.kind!=ReadingKind.NOVEL)return@withContext null
  val source=chapter.sources.firstOrNull{it.enabled&&it.providerId.equals(id,true)&&valid(it.url)}?:return@withContext null
  val doc=try{documentFetcher?.invoke(source.url)?:if(documentFetcher==null)fetch(source.url)else null}catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){null}?:return@withContext null
  val text=extractFirstReadingNovelText(doc, ".chapter-content, .reader-content, article .chapter-text")
  text.takeIf(String::isNotBlank)?.let{ReadingContent(entry.kind,"reading-provider:$id",source.url,text=it,format="text")}
 }
 private fun valid(v:String)=isSafeReadingChapterUrl(v,setOf("j-novel.club")){it.contains("read",true)}
 private fun fetch(v:String):Document?=fetchReadingSiteHtml(v)
}
