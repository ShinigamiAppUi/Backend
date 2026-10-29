package streamix.readingcatalog
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CancellationException
fun interface ReadingSourceHandler { suspend fun resolve(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry,source:ReadingChapterSource):ReadingContent? }
fun interface ReadingProviderRuntime { suspend fun race(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry,failedSourceIds:Set<String>):ReadingContent? }
class ReadingCatalogService(private val repository:ReadingCatalogRepository,private val handlers:Map<ReadingSourceType,ReadingSourceHandler> = emptyMap(),private val providerRuntime:ReadingProviderRuntime?=null){
 fun list(kind:ReadingKind?=null):List<ReadingCatalogEntry> = repository.all(kind)
 fun get(id:String):ReadingCatalogEntry?=repository.find(id)
 fun putManual(entry:ReadingCatalogEntry):ReadingCatalogEntry{
  require(entry.title.isNotBlank()){ "title is required" };require(entry.metadataId==null||entry.metadataId>0L){ "metadataId must be a positive AniList ID" }
  val requestedUrl=entry.url.trim()
  val url=if(requestedUrl.isBlank()) {
   require(entry.metadataId!=null || !entry.catalogKey.isNullOrBlank()) { "url is required unless the entry has an AniList identity" }
   ""
  } else validateWebUrl(requestedUrl,"url")
  val cover=entry.coverUrl?.takeIf{it.isNotBlank()}?.let{validateWebUrl(it,"coverUrl")}
  val refs=normalizeRefs(entry.sourceRefs).onEach{validateWebUrl(it.url,"sourceRef.url")}
  val chapters=normalizeChapters(entry.chapters,ReadingEntryOrigin.MANUAL).onEach{chapter->chapter.sources.forEach{validateWebUrl(it.url,"chapter.source.url")}}
  entry.chapterUrls.filter(String::isNotBlank).forEach{validateWebUrl(it,"chapterUrl")}
  val requestedKey=entry.catalogKey?.trim()?.takeIf{it.isNotEmpty()}
  val candidates=repository.all(entry.kind)
  val byKey=requestedKey?.let { key -> candidates.firstOrNull { it.catalogKey==key } }
  val normalizedUrl=url.trimEnd('/')
  val byUrl=normalizedUrl.takeIf{it.isNotBlank()}?.let { candidateUrl -> candidates.firstOrNull { it.url.trimEnd('/')==candidateUrl } }
  val byMetadata=entry.metadataId?.let { metadataId -> candidates.firstOrNull { it.metadataId==metadataId } }
  // Prefer stable catalog identity, then URL, then AniList identity. Never silently
  // rebind an existing URL or metadata identity to a different canonical catalog key.
  if(byKey!=null) {
   require(byUrl==null || byUrl.id==byKey.id) { "url already belongs to another catalog entry" }
   require(byMetadata==null || byMetadata.id==byKey.id) { "metadataId already belongs to another catalog entry" }
  } else if(requestedKey!=null) {
   require(byUrl==null || byUrl.catalogKey.isNullOrBlank() || byUrl.catalogKey==requestedKey) { "url already belongs to another catalog identity" }
   require(byMetadata==null || byMetadata.catalogKey.isNullOrBlank() || byMetadata.catalogKey==requestedKey) { "metadataId already belongs to another catalog identity" }
  }
  require(byUrl==null || byMetadata==null || byUrl.id==byMetadata.id) { "url and metadataId belong to different catalog entries" }
  val existing=byKey ?: byUrl ?: byMetadata
  val catalogKey=requestedKey ?: existing?.catalogKey ?: entry.metadataId?.let { "anilist:$it" }
  val metadataId=entry.metadataId ?: existing?.metadataId
  val effectiveUrl=url.ifBlank { existing?.url.orEmpty() }
  // Keep the existing primary key when a URL-only entry is linked to AniList later.
  // Re-keying here would leave the old row behind and create duplicate catalog entries.
  val id=existing?.id ?: stableId(entry.kind,catalogKey?:effectiveUrl)
  // Empty source collections on a metadata edit mean "not supplied"; curated sources
  // are removed through the dedicated chapter/source mutation routes, not accidentally.
  val effectiveRefs=if(entry.sourceRefs.isEmpty() && existing!=null) existing.sourceRefs else refs
  val effectiveChapters=if(entry.chapters.isEmpty() && existing!=null) existing.chapters else chapters
  val effectiveChapterUrls=if(entry.chapterUrls.isEmpty() && existing!=null) existing.chapterUrls else entry.chapterUrls.filter(String::isNotBlank).distinct()
  // Visibility and AniList identity are controlled independently from editable metadata fields.
  val enabled=existing?.enabled ?: entry.enabled
  return repository.upsert(entry.copy(id=id,url=effectiveUrl,coverUrl=cover,title=entry.title.trim(),catalogKey=catalogKey,metadataId=metadataId,origin=ReadingEntryOrigin.MANUAL,sourceRefs=effectiveRefs,chapterUrls=effectiveChapterUrls,chapters=effectiveChapters,enabled=enabled,updatedAt=System.currentTimeMillis()))
 }
 /** Anime Catalog tier order: DIRECT -> RESOLVER -> EXTRACTOR -> multi-provider runtime race. */
 suspend fun resolveChapter(entryId:String,chapterId:String,failedSourceIds:Set<String> = emptySet(),language:String?=null):ReadingContent?{
  val entry=repository.find(entryId)?:return null
  if (!entry.enabled) return null
  // Catalog chapters are optional: a selected AniList chapter can be passed to discovery-capable providers without persisting a synthetic chapter or URL.
  val requestedLanguage=language?.trim()?.lowercase(Locale.ROOT)?.takeIf { it == "id" || it == "en" }
  val chapter=(entry.chapters.firstOrNull{it.id==chapterId||it.number==chapterId}
   ?: ReadingChapterEntry(id=chapterId.trim(),number=chapterId.trim())).let { selected ->
    selected.copy(
     language=requestedLanguage ?: selected.language,
     // Keep language-neutral sources as fallbacks, but never select a source explicitly
     // tagged for the opposite language when the caller requested id/en.
     sources=if(requestedLanguage==null) selected.sources else selected.sources.filter { source ->
      source.language.isNullOrBlank() || source.language.trim().equals(requestedLanguage,ignoreCase=true)
     }
    )
   }
  if (chapter.id.isBlank() || chapter.number.isBlank()) return null
  val failed=failedSourceIds.toMutableSet()
  for(type in SOURCE_TIER_ORDER){
   val sources=chapter.sources.filter{it.enabled&&it.type==type}.filterNot{it.id in failed||it.providerId in failed||it.url in failed}.sortedWith(compareByDescending<ReadingChapterSource>{it.priority}.thenBy{it.id})
   for(source in sources){
    val handler=handlers[type]
    val result=try{
     if(handler!=null) handler.resolve(entry,chapter,source)
     else if(type==ReadingSourceType.DIRECT) ReadingContent(entry.kind,source.id,contentUrl=source.url)
     else null
    }catch(e:CancellationException){throw e}catch(_:Exception){null}
    if(result?.isUsableFor(entry.kind)==true)return result
    // If no explicit EXTRACTOR handler exists, this chapter is intended for the
    // site-specific runtime adapter. Do not poison that adapter's provider/source
    // identity before the final provider race starts.
    if(type==ReadingSourceType.EXTRACTOR && handler==null){
     // No extractor handler attempted this URL; keep it eligible for the site-specific provider race.
    }else{
     failed+=source.id;failed+=source.providerId;failed+=source.url
    }
   }
  }
  return try{providerRuntime?.race(entry,chapter,failedSourceIds+failed)?.takeIf{it.isUsableFor(entry.kind)}}catch(e:CancellationException){throw e}catch(_:Exception){null}
 }
 fun putChapter(entryId:String,chapter:ReadingChapterEntry):ReadingCatalogEntry? {
  val entry=repository.find(entryId)?:return null
  require(chapter.number.isNotBlank()) { "chapter number is required" }
  chapter.sources.filter{it.url.isNotBlank()}.forEach{validateWebUrl(it.url,"chapter.source.url")}
  val normalized=normalizeChapters(listOf(chapter),entry.origin).single().copy(
   id=chapter.id.trim().ifBlank { stableId(entry.kind,(entry.catalogKey?:entry.url)+"|chapter|"+chapter.number.trim().lowercase(Locale.ROOT)) },
   sources=chapter.sources.map { source -> source.copy(id=source.id.trim().ifBlank { stableId(entry.kind,source.providerId+"|"+source.url) }, providerId=source.providerId.trim(), url=source.url.trim()) }
  )
  val replaced=entry.chapters.filter { it.id==normalized.id || it.number==normalized.number }
  val chapters=entry.chapters.filterNot { it.id==normalized.id || it.number==normalized.number } + normalized
  val replacedUrls=replaced.flatMap{it.sources}.map{it.url}.toSet()
  val retainedUrls=(chapters.flatMap{it.sources}.map{it.url}+entry.sourceRefs.map{it.url}).toSet()
  val chapterUrls=(entry.chapterUrls.filter{url->url !in replacedUrls || url in retainedUrls}+normalized.sources.map{it.url}).distinct()
  return repository.replace(entry.copy(chapters=chapters,chapterUrls=chapterUrls,updatedAt=System.currentTimeMillis()))
 }
 fun deleteChapter(entryId:String,chapterId:String):Boolean {
  val entry=repository.find(entryId)?:return false
  val remaining=entry.chapters.filterNot{it.id==chapterId||it.number==chapterId}
  if(remaining.size==entry.chapters.size)return false
  val remainingUrls=(remaining.flatMap{it.sources}.map{it.url}+entry.sourceRefs.map{it.url}).toSet()
  val previousChapterUrls=entry.chapters.flatMap{it.sources}.map{it.url}.toSet()
  repository.replace(entry.copy(chapters=remaining,chapterUrls=entry.chapterUrls.filter{url->url !in previousChapterUrls || url in remainingUrls},updatedAt=System.currentTimeMillis()))
  return true
 }
 fun setChapterSourceEnabled(entryId:String,chapterId:String,sourceId:String,enabled:Boolean):ReadingChapterSource? {
  val entry=repository.find(entryId)?:return null
  val chapter=entry.chapters.firstOrNull{it.id==chapterId||it.number==chapterId}?:return null
  val source=chapter.sources.firstOrNull{it.id==sourceId}?:return null
  if(source.enabled==enabled)return source
  val updated=source.copy(enabled=enabled)
  val chapters=entry.chapters.map{if(it.id==chapter.id) it.copy(sources=it.sources.map{candidate->if(candidate.id==sourceId) updated else candidate}) else it}
  repository.replace(entry.copy(chapters=chapters,updatedAt=System.currentTimeMillis()))
  return updated
 }
 fun deleteChapterSource(entryId:String,chapterId:String,sourceId:String):Boolean {
  val entry=repository.find(entryId)?:return false
  val chapter=entry.chapters.firstOrNull{it.id==chapterId||it.number==chapterId}?:return false
  if(chapter.sources.none{it.id==sourceId})return false
  val remainingSources=chapter.sources.filterNot{it.id==sourceId}
  val chapters=entry.chapters.map{if(it.id==chapter.id) it.copy(sources=remainingSources) else it}
  val remainingUrls=(chapters.flatMap{it.sources}.map{it.url}+entry.sourceRefs.map{it.url}).toSet()
  val removedUrl=chapter.sources.first{it.id==sourceId}.url
  val chapterUrls=entry.chapterUrls.filter{it!=removedUrl || it in remainingUrls}
  repository.replace(entry.copy(chapters=chapters,chapterUrls=chapterUrls,updatedAt=System.currentTimeMillis()))
  return true
 }
 fun updateChapterSourceHealth(
  entryId:String,
  chapterId:String,
  sourceId:String,
  health:ReadingSourceHealth,
  checkedAt:Long=System.currentTimeMillis(),
  httpStatus:Int?=null,
  failureReason:String?=null
 ):ReadingChapterSource? {
  require(checkedAt>0) { "checkedAt must be positive" }
  require(httpStatus==null || httpStatus in 100..599) { "httpStatus must be between 100 and 599" }
  val entry=repository.find(entryId)?:return null
  val chapter=entry.chapters.firstOrNull{it.id==chapterId||it.number==chapterId}?:return null
  val source=chapter.sources.firstOrNull{it.id==sourceId}?:return null
  val updated=source.copy(
   health=health,
   lastCheckedAt=checkedAt,
   lastHttpStatus=httpStatus,
   lastError=failureReason?.take(512)
  )
  val chapters=entry.chapters.map{if(it.id==chapter.id) it.copy(sources=it.sources.map{candidate->if(candidate.id==sourceId) updated else candidate}) else it}
  repository.replace(entry.copy(chapters=chapters,updatedAt=System.currentTimeMillis()))
  return updated
 }
 fun setEntryEnabled(id:String,enabled:Boolean):ReadingCatalogEntry? {
  val entry=repository.find(id)?:return null
  if(entry.enabled==enabled)return entry
  return repository.replace(entry.copy(enabled=enabled,updatedAt=System.currentTimeMillis()))
 }
 fun delete(id:String)=repository.delete(id)
 private fun validateWebUrl(raw:String,field:String):String {
  val value=raw.trim()
  require(value.isNotBlank()) { "$field is required" }
  val uri=runCatching{java.net.URI(value)}.getOrElse{throw IllegalArgumentException("$field must be an absolute HTTP(S) URL")}
  val scheme=uri.scheme?.lowercase(Locale.ROOT)
  require((scheme=="https"||scheme=="http") && !uri.isOpaque && !uri.host.isNullOrBlank() && uri.rawUserInfo==null) { "$field must be an absolute HTTP(S) URL without credentials" }
  require(uri.port==-1 || (scheme=="https"&&uri.port==443) || (scheme=="http"&&uri.port==80)) { "$field uses a non-standard port" }
  require(!uri.host.equals("localhost",true) && !uri.host.endsWith(".localhost",true) && !uri.host.endsWith(".local",true)) { "$field cannot target a local hostname" }
  require(!isUnsafeIpLiteral(uri.host)) { "$field cannot target a non-public IP address" }
  return uri.toASCIIString()
 }
 private fun isUnsafeIpLiteral(rawHost:String):Boolean {
  val host=rawHost.removePrefix("[").removeSuffix("]").lowercase(Locale.ROOT)
  if(host.contains(':')) {
   val address=runCatching { java.net.InetAddress.getByName(host) }.getOrNull() ?: return true
   return address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress
  }
  if(host.matches(Regex("[0-9.]+"))) {
   val parts=host.split(".")
   if(parts.size!=4) return true
   val octets=parts.map { it.toIntOrNull()?.takeIf { value -> value in 0..255 } ?: return true }
   val first=octets[0]
   val second=octets[1]
   return first==0 || first==10 || first==127 ||
    (first==169 && second==254) ||
    (first==172 && second in 16..31) ||
    (first==192 && second==168) ||
    (first==100 && second in 64..127) ||
    first>=224
  }
  return false
 }
 private fun normalizeRefs(refs:List<ReadingSourceRef>)=refs.filter{it.sourceId.isNotBlank()&&it.url.isNotBlank()}.map{it.copy(sourceId=it.sourceId.trim(),url=it.url.trim())}.distinctBy{it.sourceId+"|"+it.url}
 private fun normalizeChapters(chapters:List<ReadingChapterEntry>,origin:ReadingEntryOrigin)=chapters.map{c->c.copy(number=c.number.trim(),sources=c.sources.filter{it.url.isNotBlank()&&it.providerId.isNotBlank()}.map{it.copy(url=it.url.trim(),origin=origin)}.distinctBy{it.type.name+"|"+it.providerId+"|"+it.url})}.distinctBy{it.id}
 private fun stableId(kind:ReadingKind,url:String):String{val key=kind.name.lowercase(Locale.ROOT)+"|"+url.trim().trimEnd('/').lowercase(Locale.ROOT);return MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).take(16).joinToString(""){"%02x".format(it)}}
 private companion object{val SOURCE_TIER_ORDER=listOf(ReadingSourceType.DIRECT,ReadingSourceType.RESOLVER,ReadingSourceType.EXTRACTOR)}
}
