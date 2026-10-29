package streamix.readingcatalog

import java.net.URI
import org.jsoup.nodes.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class IndoWebNovelReadingRuntimeProvider(private val documentFetcher: ((String)->Document?)?=null):ReadingRuntimeProvider{
 override val id="indowebnovel"
 override fun supports(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry)=entry.kind==ReadingKind.NOVEL&&chapter.sources.any{it.enabled&&it.providerId.equals(id,true)&&valid(it.url)}
 override suspend fun chapter(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry):ReadingContent?=withContext(Dispatchers.IO){
  if(entry.kind!=ReadingKind.NOVEL)return@withContext null
  val source=chapter.sources.firstOrNull{it.enabled&&it.providerId.equals(id,true)&&valid(it.url)}?:return@withContext null
  val doc=runCatching{documentFetcher?.invoke(source.url)?:if(documentFetcher==null)fetchNovelHtml(source.url)else null}.getOrNull()?:return@withContext null
  val text=extractFirstReadingNovelText(doc, ".text-left, .text-right, .entry-content, .chapter-content")
  text.takeIf(String::isNotBlank)?.let{ReadingContent(entry.kind,"reading-provider:$id",source.url,text=it,format="text")}
 }
 private fun valid(v:String)=novelHost(v,setOf("indowebnovel.com","indowebnovel.id"))&&URI(v).path.isNotBlank()
}

class MtlNovelIdReadingRuntimeProvider(private val documentFetcher: ((String)->Document?)?=null):ReadingRuntimeProvider{
 override val id="mtl-novel-id"
 override fun supports(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry)=entry.kind==ReadingKind.NOVEL&&chapter.sources.any{it.enabled&&it.providerId.equals(id,true)&&valid(it.url)}
 override suspend fun chapter(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry):ReadingContent?=withContext(Dispatchers.IO){
  if(entry.kind!=ReadingKind.NOVEL)return@withContext null
  val source=chapter.sources.firstOrNull{it.enabled&&it.providerId.equals(id,true)&&valid(it.url)}?:return@withContext null
  val doc=runCatching{documentFetcher?.invoke(source.url)?:if(documentFetcher==null)fetchNovelHtml(source.url)else null}.getOrNull()?:return@withContext null
  val text=extractFirstReadingNovelText(doc, "div.par")
  text.takeIf(String::isNotBlank)?.let{ReadingContent(entry.kind,"reading-provider:$id",source.url,text=it,format="text")}
 }
 private fun valid(v:String)=novelHost(v,setOf("mtlnovel.me"))&&URI(v).path.isNotBlank()
}

class MeioNovelReadingRuntimeProvider(private val documentFetcher: ((String)->Document?)?=null):ReadingRuntimeProvider{
 override val id="meionovel"
 override fun supports(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry)=entry.kind==ReadingKind.NOVEL&&chapter.sources.any{it.enabled&&it.providerId.equals(id,true)&&valid(it.url)}
 override suspend fun chapter(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry):ReadingContent?=withContext(Dispatchers.IO){
  if(entry.kind!=ReadingKind.NOVEL)return@withContext null
  val source=chapter.sources.firstOrNull{it.enabled&&it.providerId.equals(id,true)&&valid(it.url)}?:return@withContext null
  val doc=runCatching{documentFetcher?.invoke(source.url)?:if(documentFetcher==null)fetchNovelHtml(source.url)else null}.getOrNull()?:return@withContext null
  val text=extractFirstReadingNovelText(doc, ".text-left, .text-right, .entry-content, .chapter-content, .chapter-text, .c-blog-post > div > div:nth-child(2)")
  text.takeIf(String::isNotBlank)?.let{ReadingContent(entry.kind,"reading-provider:$id",source.url,text=it,format="text")}
 }
 private fun valid(v:String)=novelHost(v,setOf("meionovels.com","meionovel.id"))&&URI(v).path.isNotBlank()
}

class SakuraNovelReadingRuntimeProvider(private val documentFetcher: ((String)->Document?)?=null):ReadingRuntimeProvider{
 override val id="sakuranovel"
 override fun supports(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry)=entry.kind==ReadingKind.NOVEL&&chapter.sources.any{it.enabled&&it.providerId.equals(id,true)&&valid(it.url)}
 override suspend fun chapter(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry):ReadingContent?=withContext(Dispatchers.IO){
  if(entry.kind!=ReadingKind.NOVEL)return@withContext null
  val source=chapter.sources.firstOrNull{it.enabled&&it.providerId.equals(id,true)&&valid(it.url)}?:return@withContext null
  val doc=runCatching{documentFetcher?.invoke(source.url)?:if(documentFetcher==null)fetchNovelHtml(source.url)else null}.getOrNull()?:return@withContext null
  val text=extractFirstReadingNovelText(doc, ".readerarea, .epcontent, .entry-content, .chapter-content, div:contains(Daftar Isi) + div")
  text.takeIf(String::isNotBlank)?.let{ReadingContent(entry.kind,"reading-provider:$id",source.url,text=it,format="text")}
 }
 private fun valid(v:String)=novelHost(v,setOf("sakuranovel.id"))&&URI(v).path.isNotBlank()
}

class SekteNovelReadingRuntimeProvider(private val documentFetcher: ((String)->Document?)?=null):ReadingRuntimeProvider{
 override val id="sekte-novel"
 override fun supports(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry)=entry.kind==ReadingKind.NOVEL&&chapter.sources.any{it.enabled&&it.providerId.equals(id,true)&&valid(it.url)}
 override suspend fun chapter(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry):ReadingContent?=withContext(Dispatchers.IO){
  if(entry.kind!=ReadingKind.NOVEL)return@withContext null
  val source=chapter.sources.firstOrNull{it.enabled&&it.providerId.equals(id,true)&&valid(it.url)}?:return@withContext null
  val doc=runCatching{documentFetcher?.invoke(source.url)?:if(documentFetcher==null)fetchNovelHtml(source.url)else null}.getOrNull()?:return@withContext null
  val text=extractFirstReadingNovelText(doc, ".epcontent, .entry-content, .post-content, .chapter-content, .text-left, .text-right")
  text.takeIf(String::isNotBlank)?.let{ReadingContent(entry.kind,"reading-provider:$id",source.url,text=it,format="text")}
 }
 private fun valid(v:String)=novelHost(v,setOf("sektenovel.web.id","sektenovel.id"))&&URI(v).path.isNotBlank()
}

class VanovelReadingRuntimeProvider(private val documentFetcher: ((String)->Document?)?=null):ReadingRuntimeProvider{
 override val id="vanovel"
 override fun supports(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry)=entry.kind==ReadingKind.NOVEL&&chapter.sources.any{it.enabled&&it.providerId.equals(id,true)&&valid(it.url)}
 override suspend fun chapter(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry):ReadingContent?=withContext(Dispatchers.IO){
  if(entry.kind!=ReadingKind.NOVEL)return@withContext null
  val source=chapter.sources.firstOrNull{it.enabled&&it.providerId.equals(id,true)&&valid(it.url)}?:return@withContext null
  val doc=runCatching{documentFetcher?.invoke(source.url)?:if(documentFetcher==null)fetchNovelHtml(source.url)else null}.getOrNull()?:return@withContext null
  val text=extractFirstReadingNovelText(doc, ".text-left, .text-right, .entry-content, .chapter-content, .chapter-text, .c-blog-post > div > div:nth-child(2)")
  text.takeIf(String::isNotBlank)?.let{ReadingContent(entry.kind,"reading-provider:$id",source.url,text=it,format="text")}
 }
 private fun valid(v:String)=novelHost(v,setOf("vanovel.com"))&&URI(v).path.isNotBlank()
}

class WBNoveReadingRuntimeProvider(private val documentFetcher: ((String)->Document?)?=null):ReadingRuntimeProvider{
 override val id="wbnove"
 override fun supports(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry)=entry.kind==ReadingKind.NOVEL&&chapter.sources.any{it.enabled&&it.providerId.equals(id,true)&&valid(it.url)}
 override suspend fun chapter(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry):ReadingContent?=withContext(Dispatchers.IO){
  if(entry.kind!=ReadingKind.NOVEL)return@withContext null
  val source=chapter.sources.firstOrNull{it.enabled&&it.providerId.equals(id,true)&&valid(it.url)}?:return@withContext null
  val doc=runCatching{documentFetcher?.invoke(source.url)?:if(documentFetcher==null)fetchNovelHtml(source.url)else null}.getOrNull()?:return@withContext null
  val text=extractFirstReadingNovelText(doc, ".text-left, .text-right, .entry-content, .c-blog-post > div > div:nth-child(2)")
  text.takeIf(String::isNotBlank)?.let{ReadingContent(entry.kind,"reading-provider:$id",source.url,text=it,format="text")}
 }
 private fun valid(v:String)=novelHost(v,setOf("wbnovel.com","wbnove.com"))&&URI(v).path.isNotBlank()
}

private fun novelHost(value:String,hosts:Set<String>)=runCatching{URI(value).let{it.scheme=="https"&&hosts.any{h->it.host.equals(h,true)}&&it.userInfo==null&&it.port in setOf(-1,443)}}.getOrDefault(false)
private fun fetchNovelHtml(value:String):Document? = fetchReadingHtmlDocument(
 value,
 mapOf("User-Agent" to "ShinigamiReadingRuntime/1.0", "Accept" to "text/html,application/xhtml+xml"),
)


/**
 * NovelBookID adapter ported from the WOX Tadami Indonesian Madara plugin.
 * Madara chapter pages use the theme's text-left/text-right reader or entry-content article.
 */
class NovelBookIdReadingRuntimeProvider(private val documentFetcher: ((String) -> Document?)? = null) : ReadingRuntimeProvider {
    override val id = "novelbookid"
    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) =
        entry.kind == ReadingKind.NOVEL && chapter.sources.any { it.enabled && it.providerId.equals(id, true) && valid(it.url) }

    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = withContext(Dispatchers.IO) {
        if (entry.kind != ReadingKind.NOVEL) return@withContext null
        val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && valid(it.url) } ?: return@withContext null
        val doc = runCatching { documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetchNovelHtml(source.url) else null }.getOrNull() ?: return@withContext null
        val text = extractFirstReadingNovelText(doc, ".text-left, .text-right, .entry-content, .chapter-content")
        text.takeIf(String::isNotBlank)?.let { ReadingContent(entry.kind, "reading-provider:$id", source.url, text = it, format = "text") }
    }

    private fun valid(value: String) = novelHost(value, setOf("novelbook.id")) && runCatching { URI(value).path.isNotBlank() }.getOrDefault(false)
}
