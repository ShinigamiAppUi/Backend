package streamix.readingcatalog
import java.net.URI
import org.jsoup.nodes.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MangaHubReadingRuntimeProvider(private val documentFetcher: ((String)->Document?)?=null):ReadingRuntimeProvider{
 override val id="mangahub"
 override fun supports(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry)=entry.kind==ReadingKind.MANGA&&chapter.sources.any{it.enabled&&it.providerId.equals(id,true)&&valid(it.url)}
 override suspend fun chapter(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry):ReadingContent?=withContext(Dispatchers.IO){
  val source=chapter.sources.firstOrNull{it.enabled&&it.providerId.equals(id,true)&&valid(it.url)}?:return@withContext null
  val doc=runCatching{documentFetcher?.invoke(source.url)?:if(documentFetcher==null)fetch(source.url)else null}.getOrNull()?:return@withContext null
  val pages=doc.select(".reader-image img, .chapter-images img, #readerarea img").mapNotNull{img->sequenceOf("data-lazy-src","data-src","data-original","src").map{img.attr(it).trim()}.firstNotNullOfOrNull{raw->raw.takeIf{it.isNotBlank()&&!it.startsWith("data:")}?.let{runCatching{URI(source.url).resolve(it).toString()}.getOrNull()}}?.takeIf(::validImage)}.distinct()
  pages.takeIf{it.isNotEmpty()}?.let{ReadingContent(entry.kind,"reading-provider:$id",source.url,it,format="image-pages")}
 }
 private fun valid(v:String)=isSafeReadingChapterUrl(v,setOf("mangahub.ru")){it.startsWith("/title/")}
 private fun validImage(v: String) = isSafeReadingImageUrl(v)
 private fun fetch(v:String):Document?=fetchReadingSiteHtml(v)
}

class MangaReaderReadingRuntimeProvider(private val documentFetcher: ((String)->Document?)?=null):ReadingRuntimeProvider{
 override val id="mangareader"
 override fun supports(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry)=entry.kind==ReadingKind.MANGA&&chapter.sources.any{it.enabled&&it.providerId.equals(id,true)&&valid(it.url)}
 override suspend fun chapter(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry):ReadingContent?=withContext(Dispatchers.IO){
  val source=chapter.sources.firstOrNull{it.enabled&&it.providerId.equals(id,true)&&valid(it.url)}?:return@withContext null
  val doc=runCatching{documentFetcher?.invoke(source.url)?:if(documentFetcher==null)fetch(source.url)else null}.getOrNull()?:return@withContext null
  val pages=doc.select(".reader-pages img, .page-break img, #readerarea img").mapNotNull{img->sequenceOf("data-src","data-original","src").map(String::trim).firstNotNullOfOrNull{k->img.attr(k).takeIf{it.isNotBlank()&&!it.startsWith("data:")}}?.let{runCatching{URI(source.url).resolve(it).toString()}.getOrNull()}?.takeIf(::validImage)}.distinct()
  pages.takeIf{it.isNotEmpty()}?.let{ReadingContent(entry.kind,"reading-provider:$id",source.url,it,format="image-pages")}
 }
 private fun valid(v:String)=isSafeReadingChapterUrl(v,setOf("mangareader.site")){it.contains("chapter",true)}
 private fun validImage(v: String) = isSafeReadingImageUrl(v)
 private fun fetch(v:String):Document?=fetchReadingSiteHtml(v)
}

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

class VizReadingRuntimeProvider(private val documentFetcher: ((String)->Document?)?=null):ReadingRuntimeProvider{
 override val id="viz"
 override fun supports(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry)=entry.kind==ReadingKind.MANGA&&chapter.sources.any{it.enabled&&it.providerId.equals(id,true)&&valid(it.url)}
 override suspend fun chapter(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry):ReadingContent?=withContext(Dispatchers.IO){
  val source=chapter.sources.firstOrNull{it.enabled&&it.providerId.equals(id,true)&&valid(it.url)}?:return@withContext null
  val doc=runCatching{documentFetcher?.invoke(source.url)?:if(documentFetcher==null)fetch(source.url)else null}.getOrNull()?:return@withContext null
  val pages=doc.select(".chapter-page img, .page-image img, img.chapter-page, #chapter-reader img").mapNotNull{img->sequenceOf("data-lazy-src","data-src","data-original","src").map{img.attr(it).trim()}.firstNotNullOfOrNull{raw->raw.takeIf{it.isNotBlank()&&!it.startsWith("data:")}?.let{runCatching{URI(source.url).resolve(it).toString()}.getOrNull()}}?.takeIf(::validImage)}.distinct()
  pages.takeIf{it.isNotEmpty()}?.let{ReadingContent(entry.kind,"reading-provider:$id",source.url,it,format="image-pages")}
 }
 private fun valid(v:String)=isSafeReadingChapterUrl(v,setOf("www.viz.com")){it.contains("/chapters/")||it.contains("/chapter/")}
 private fun validImage(v: String) = isSafeReadingImageUrl(v)
 private fun fetch(v:String):Document?=fetchReadingSiteHtml(v)
}

class MangaUpReadingRuntimeProvider(private val documentFetcher: ((String)->Document?)?=null):ReadingRuntimeProvider{
 override val id="mangaup"
 override fun supports(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry)=entry.kind==ReadingKind.MANGA&&chapter.sources.any{it.enabled&&it.providerId.equals(id,true)&&valid(it.url)}
 override suspend fun chapter(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry):ReadingContent?=withContext(Dispatchers.IO){
  val source=chapter.sources.firstOrNull{it.enabled&&it.providerId.equals(id,true)&&valid(it.url)}?:return@withContext null
  val doc=runCatching{documentFetcher?.invoke(source.url)?:if(documentFetcher==null)fetch(source.url)else null}.getOrNull()?:return@withContext null
  val pages=doc.select(".viewer-page img, .chapter-viewer img, img.page-image").mapNotNull{img->sequenceOf("data-lazy-src","data-src","data-original","src").map{img.attr(it).trim()}.firstNotNullOfOrNull{raw->raw.takeIf{it.isNotBlank()&&!it.startsWith("data:")}?.let{runCatching{URI(source.url).resolve(it).toString()}.getOrNull()}}?.takeIf(::validImage)}.distinct()
  pages.takeIf{it.isNotEmpty()}?.let{ReadingContent(entry.kind,"reading-provider:$id",source.url,it,format="image-pages")}
 }
 private fun valid(v:String)=isSafeReadingChapterUrl(v,setOf("global.manga-up.com")){it.startsWith("/manga/")}
 private fun validImage(v: String) = isSafeReadingImageUrl(v)
 private fun fetch(v:String):Document?=fetchReadingSiteHtml(v)
}

class ReaperScansReadingRuntimeProvider(private val documentFetcher: ((String)->Document?)?=null):ReadingRuntimeProvider{
 override val id="reaper-scans"
 override fun supports(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry)=entry.kind==ReadingKind.MANHWA&&chapter.sources.any{it.enabled&&it.providerId.equals(id,true)&&valid(it.url)}
 override suspend fun chapter(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry):ReadingContent?=withContext(Dispatchers.IO){
  val source=chapter.sources.firstOrNull{it.enabled&&it.providerId.equals(id,true)&&valid(it.url)}?:return@withContext null
  val doc=runCatching{documentFetcher?.invoke(source.url)?:if(documentFetcher==null)fetch(source.url)else null}.getOrNull()?:return@withContext null
  val pages=doc.select("#readerarea img, .reading-content img, .chapter-content img").mapNotNull{img->sequenceOf("data-lazy-src","data-src","data-original","src").map(String::trim).firstNotNullOfOrNull{k->img.attr(k).takeIf{it.isNotBlank()&&!it.startsWith("data:")}}?.let{runCatching{URI(source.url).resolve(it).toString()}.getOrNull()}?.takeIf(::validImage)}.distinct()
  pages.takeIf{it.isNotEmpty()}?.let{ReadingContent(entry.kind,"reading-provider:$id",source.url,it,format="image-pages")}
 }
 private fun valid(v:String)=isSafeReadingChapterUrl(v,setOf("reaperscans.com")){it.contains("chapter",true)}
 private fun validImage(v: String) = isSafeReadingImageUrl(v)
 private fun fetch(v:String):Document?=fetchReadingSiteHtml(v)
}


private fun fetchReadingSiteHtml(value:String):Document? = fetchReadingHtmlDocument(
 value,
 mapOf("User-Agent" to "ShinigamiReadingRuntime/1.0", "Accept" to "text/html,application/xhtml+xml"),
)
