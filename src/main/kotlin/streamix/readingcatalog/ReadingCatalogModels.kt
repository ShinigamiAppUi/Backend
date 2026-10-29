package streamix.readingcatalog
enum class ReadingKind { MANGA, MANHWA, MANHUA, NOVEL }
enum class ReadingEntryOrigin { MANUAL }
enum class ReadingSourceType { DIRECT, RESOLVER, EXTRACTOR }
enum class ReadingSourceHealth { UNKNOWN, AVAILABLE, EXPIRED, FAILED }
data class ReadingSourceRef(val sourceId:String,val url:String,val language:String?=null,val enabled:Boolean=true,val lastFetchedAt:Long?=null,val lastError:String?=null)
data class ReadingChapterSource(val id:String,val providerId:String,val url:String,val type:ReadingSourceType=ReadingSourceType.EXTRACTOR,val origin:ReadingEntryOrigin=ReadingEntryOrigin.MANUAL,val language:String?=null,val enabled:Boolean=true,val priority:Int=0,val health:ReadingSourceHealth=ReadingSourceHealth.UNKNOWN,val lastCheckedAt:Long?=null,val lastError:String?=null,val lastHttpStatus:Int?=null)
data class ReadingChapterEntry(val id:String,val number:String,val title:String?=null,val sources:List<ReadingChapterSource> = emptyList(),val language:String?=null)
data class ReadingContent(val kind:ReadingKind,val sourceId:String,val contentUrl:String?=null,val pageUrls:List<String> = emptyList(),val text:String?=null,val format:String?=null,val headers:Map<String,String> = emptyMap(),val referer:String?=null){fun isUsable()=!contentUrl.isNullOrBlank()||pageUrls.isNotEmpty()||!text.isNullOrBlank()}
data class ReadingCatalogEntry(val id:String,val kind:ReadingKind,val title:String,val url:String,val origin:ReadingEntryOrigin,val coverUrl:String?=null,val description:String?=null,val author:String?=null,val genres:List<String> = emptyList(),val status:String?=null,val sourceRefs:List<ReadingSourceRef> = emptyList(),val chapterUrls:List<String> = emptyList(),val chapters:List<ReadingChapterEntry> = emptyList(),val updatedAt:Long=System.currentTimeMillis(),val catalogKey:String?=null,val metadataId:Long?=null,val enabled:Boolean=true)
data class ReadingChapterSourceHealthUpdate(val health:ReadingSourceHealth,val checkedAt:Long?=null,val httpStatus:Int?=null,val failureReason:String?=null)

data class ReadingChapterSourceEnabledUpdate(val enabled:Boolean)
