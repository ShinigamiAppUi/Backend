package streamix.readingcatalog
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
class ReadingCatalogServiceTest{
 @Test fun deletingChapterKeepsUrlReferencedBySeriesSourceAndOtherChapters(){
  val service=ReadingCatalogService(FileReadingCatalogRepository(createTempDirectory("reading-delete-chapter-refs").resolve("reading.json")))
  val entry=service.putManual(ReadingCatalogEntry(
   "x",ReadingKind.MANGA,"Manga","https://reader.example/manga",ReadingEntryOrigin.MANUAL,
   sourceRefs=listOf(ReadingSourceRef("reader","https://reader.example/chapter/shared")),
   chapterUrls=listOf("https://reader.example/chapter/shared","https://reader.example/chapter/only"),
   chapters=listOf(
    ReadingChapterEntry("remove","1",sources=listOf(ReadingChapterSource("s1","reader","https://reader.example/chapter/shared"),ReadingChapterSource("s2","reader","https://reader.example/chapter/only"))),
    ReadingChapterEntry("keep","2",sources=listOf(ReadingChapterSource("s3","mirror","https://reader.example/chapter/shared")))
   )
  ))
  assertTrue(service.deleteChapter(entry.id,"1"))
  val updated=service.get(entry.id)!!
  assertEquals(listOf("keep"),updated.chapters.map{it.id})
  assertEquals(listOf("https://reader.example/chapter/shared"),updated.chapterUrls)
 }
 @Test fun replacingChapterSourcesRemovesOnlyUnreferencedOldUrls() {
  val service=ReadingCatalogService(FileReadingCatalogRepository(createTempDirectory("reading-replace-chapter").resolve("reading.json")))
  val entry=service.putManual(ReadingCatalogEntry(
   "x",ReadingKind.MANGA,"Manga","https://reader.example/manga",ReadingEntryOrigin.MANUAL,
   sourceRefs=listOf(ReadingSourceRef("series","https://reader.example/chapter/shared")),
   chapterUrls=listOf("https://reader.example/chapter/old","https://reader.example/chapter/shared"),
   chapters=listOf(ReadingChapterEntry("c","1",sources=listOf(
    ReadingChapterSource("old","reader","https://reader.example/chapter/old"),
    ReadingChapterSource("shared","reader","https://reader.example/chapter/shared")
   )))
  ))
  service.putChapter(entry.id,ReadingChapterEntry("c","1",sources=listOf(
   ReadingChapterSource("new","reader","https://reader.example/chapter/new")
  )))
  val updated=service.get(entry.id)!!
  assertEquals(listOf("https://reader.example/chapter/shared","https://reader.example/chapter/new"),updated.chapterUrls)
  assertEquals(listOf("new"),updated.chapters.single().sources.map{it.id})
 }

 @Test fun sourceHealthRejectsInvalidTimestampAndHttpStatus(){
  val service=ReadingCatalogService(FileReadingCatalogRepository(createTempDirectory("reading-health-validation").resolve("reading.json")))
  val entry=service.putManual(ReadingCatalogEntry("x",ReadingKind.MANGA,"Manga","https://reader.example/manga",ReadingEntryOrigin.MANUAL,chapters=listOf(
   ReadingChapterEntry("c","1",sources=listOf(ReadingChapterSource("s","reader","https://reader.example/chapter")))
  )))
  assertTrue(runCatching{service.updateChapterSourceHealth(entry.id,"c","s",ReadingSourceHealth.AVAILABLE,checkedAt=0)}.isFailure)
  assertTrue(runCatching{service.updateChapterSourceHealth(entry.id,"c","s",ReadingSourceHealth.AVAILABLE,httpStatus=99)}.isFailure)
  assertTrue(runCatching{service.updateChapterSourceHealth(entry.id,"c","s",ReadingSourceHealth.AVAILABLE,httpStatus=600)}.isFailure)
 }

 @Test fun fallbackUsesTierOrderThenRuntimeRace()=runBlocking{
  val calls=mutableListOf<String>();val runtime=ReadingProviderRuntime{_,_,_->calls+="runtime";ReadingContent(ReadingKind.NOVEL,"runtime",text="chapter")}
  val handlers=mapOf(ReadingSourceType.DIRECT to ReadingSourceHandler{_,_,s->calls+="direct:"+s.id;null},ReadingSourceType.RESOLVER to ReadingSourceHandler{_,_,s->calls+="resolver:"+s.id;null},ReadingSourceType.EXTRACTOR to ReadingSourceHandler{_,_,s->calls+="extractor:"+s.id;null})
  val service=ReadingCatalogService(FileReadingCatalogRepository(createTempDirectory("reading-fallback").resolve("reading.json")),handlers,runtime)
  val entry=service.putManual(ReadingCatalogEntry("x",ReadingKind.NOVEL,"Novel","https://catalog.example/novel",ReadingEntryOrigin.MANUAL,chapters=listOf(ReadingChapterEntry("c","1",sources=listOf(ReadingChapterSource("e","e","https://e.example/1",ReadingSourceType.EXTRACTOR),ReadingChapterSource("r","r","https://r.example/1",ReadingSourceType.RESOLVER),ReadingChapterSource("d","d","https://d.example/1",ReadingSourceType.DIRECT))))))
  assertEquals("runtime",service.resolveChapter(entry.id,"c")?.sourceId);assertEquals(listOf("direct:d","resolver:r","extractor:e","runtime"),calls)
 }
 @Test fun failedCatalogSourceIsRemovedBeforeDiscoveryRuntimeRace()=runBlocking{
  var discovered=false
  val discoveryProvider=object:ReadingRuntimeProvider{
   override val id="discovery"
   override fun supports(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry)=chapter.sources.none{it.enabled}
   override suspend fun chapter(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry):ReadingContent?{
    discovered=chapter.sources.isEmpty()
    return ReadingContent(entry.kind,"reading-provider:$id",pageUrls=listOf("https://cdn.reader.example/discovered.jpg"),format="image-pages")
   }
  }
  val service=ReadingCatalogService(
   FileReadingCatalogRepository(createTempDirectory("reading-discovery-after-failure").resolve("reading.json")),
   handlers=mapOf(ReadingSourceType.EXTRACTOR to ReadingSourceHandler{_,_,_->null}),
   providerRuntime=ReadingProviderRuntimeRace(listOf(discoveryProvider))
  )
  val entry=service.putManual(ReadingCatalogEntry(
   "discovery-entry",ReadingKind.MANGA,"Title","https://catalog.example/title",ReadingEntryOrigin.MANUAL,
   chapters=listOf(ReadingChapterEntry("chapter-1","1",sources=listOf(
    ReadingChapterSource("catalog-source","broken-site","https://reader.example/chapter/1",ReadingSourceType.EXTRACTOR)
   )))
  ))
  val result=service.resolveChapter(entry.id,"chapter-1")
  assertEquals(listOf("https://cdn.reader.example/discovered.jpg"),result?.pageUrls)
  assertTrue(discovered)
 }
 @Test fun requestedLanguageReachesDiscoveryRuntimeWithoutPersistingSyntheticChapter()=runBlocking{
  var receivedLanguage:String?=null
  var receivedChapterId:String?=null
  val provider=object:ReadingRuntimeProvider{
   override val id="language-discovery"
   override fun supports(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry)=chapter.sources.isEmpty()
   override suspend fun chapter(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry):ReadingContent?{
    receivedLanguage=chapter.language
    receivedChapterId=chapter.id
    return ReadingContent(entry.kind,"reading-provider:$id",text="chapter text",format="text")
   }
  }
  val repository=FileReadingCatalogRepository(createTempDirectory("reading-language-discovery").resolve("reading.json"))
  val service=ReadingCatalogService(repository,providerRuntime=ReadingProviderRuntimeRace(listOf(provider)))
  val entry=service.putManual(ReadingCatalogEntry(
   "language-entry",ReadingKind.NOVEL,"Novel","https://catalog.example/novel",ReadingEntryOrigin.MANUAL
  ))
  val result=service.resolveChapter(entry.id,"12",language="id")
  assertEquals("chapter text",result?.text)
  assertEquals("id",receivedLanguage)
  assertEquals("12",receivedChapterId)
  assertTrue(service.get(entry.id)?.chapters?.isEmpty()==true)
 }
 @Test fun unhandledExtractorSourceReachesSiteSpecificRuntimeAdapter()=runBlocking{
  var calls=0
  val provider=object:ReadingRuntimeProvider{
   override val id="site-specific"
   override suspend fun chapter(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry):ReadingContent?{
    calls++
    return ReadingContent(entry.kind,"reading-provider:$id",pageUrls=listOf("https://cdn.reader.example/page-1.jpg"),format="image-pages")
   }
  }
  val service=ReadingCatalogService(
   FileReadingCatalogRepository(createTempDirectory("reading-unhandled-extractor").resolve("reading.json")),
   providerRuntime=ReadingProviderRuntimeRace(listOf(provider))
  )
  val entry=service.putManual(ReadingCatalogEntry(
   "ignored",ReadingKind.MANGA,"Title","https://catalog.example/title",ReadingEntryOrigin.MANUAL,
   chapters=listOf(ReadingChapterEntry("chapter-1","1",sources=listOf(
    ReadingChapterSource("source-id","site-specific","https://reader.example/chapter/1",ReadingSourceType.EXTRACTOR,ReadingEntryOrigin.MANUAL)
   )))
  ))
  val result=service.resolveChapter(entry.id,"chapter-1")
  assertEquals(listOf("https://cdn.reader.example/page-1.jpg"),result?.pageUrls)
  assertEquals(1,calls)
 }
 @Test fun directSuccessStopsLowerTiers()=runBlocking{
  val calls=mutableListOf<String>();val handlers=mapOf(ReadingSourceType.DIRECT to ReadingSourceHandler{_,_,s->calls+=s.id;ReadingContent(ReadingKind.MANGA,s.id,pageUrls=listOf("https://img.example/1.jpg"))},ReadingSourceType.RESOLVER to ReadingSourceHandler{_,_,s->calls+=s.id;null})
  val service=ReadingCatalogService(FileReadingCatalogRepository(createTempDirectory("reading-direct").resolve("reading.json")),handlers)
  val entry=service.putManual(ReadingCatalogEntry("x",ReadingKind.MANGA,"Manga","https://catalog.example/manga",ReadingEntryOrigin.MANUAL,chapters=listOf(ReadingChapterEntry("1","1",sources=listOf(ReadingChapterSource("d","direct","https://img.example/1.jpg",ReadingSourceType.DIRECT),ReadingChapterSource("r","resolver","https://reader.example/1",ReadingSourceType.RESOLVER))))))
  assertNotNull(service.resolveChapter(entry.id,"1"));assertEquals(listOf("d"),calls)
 }
 @Test fun wrongKindSourceResultIsRejectedAndRuntimeFallbackContinues()=runBlocking{
  val calls=mutableListOf<String>()
  val service=ReadingCatalogService(
   FileReadingCatalogRepository(createTempDirectory("reading-kind-guard").resolve("reading.json")),
   handlers=mapOf(ReadingSourceType.DIRECT to ReadingSourceHandler{_,_,s->
    calls+="direct"
    ReadingContent(ReadingKind.NOVEL,s.id,text="wrong kind")
   }),
   providerRuntime=ReadingProviderRuntime{entry,_,_->calls+="runtime";ReadingContent(entry.kind,"runtime",pageUrls=listOf("https://cdn.example/correct-kind.jpg"))}
  )
  val entry=service.putManual(ReadingCatalogEntry("x",ReadingKind.MANGA,"Manga","https://catalog.example/manga",ReadingEntryOrigin.MANUAL,chapters=listOf(
   ReadingChapterEntry("c","1",sources=listOf(ReadingChapterSource("d","direct","https://reader.example/chapter",ReadingSourceType.DIRECT)))
  )))
  val result=service.resolveChapter(entry.id,"c")
  assertEquals(ReadingKind.MANGA,result?.kind)
  assertEquals("runtime",result?.sourceId)
  assertEquals(listOf("direct","runtime"),calls)
 }

 @Test fun missingChapterStartsRuntimeDiscoveryWithoutPersistingSyntheticChapter()=runBlocking{
  var receivedChapter:ReadingChapterEntry?=null
  val repository=FileReadingCatalogRepository(createTempDirectory("reading-missing").resolve("reading.json"))
  val service=ReadingCatalogService(repository,providerRuntime=ReadingProviderRuntime{_,chapter,_->receivedChapter=chapter;ReadingContent(ReadingKind.NOVEL,"discovery",text="chapter text")})
  val entry=service.putManual(ReadingCatalogEntry("x",ReadingKind.NOVEL,"Novel","https://catalog.example/novel",ReadingEntryOrigin.MANUAL))
  val result=service.resolveChapter(entry.id,"17")
  assertEquals("17",receivedChapter?.number)
  assertEquals("discovery",result?.sourceId)
  assertTrue(repository.find(entry.id)?.chapters.orEmpty().isEmpty())
 }
 @Test fun runtimeRaceReturnsFirstUsableProviderAndSkipsFailedProviders()=runBlocking{
  val providers=listOf(
   object:ReadingRuntimeProvider{override val id="slow";override suspend fun chapter(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry):ReadingContent?{delay(200);return ReadingContent(entry.kind,id,text="slow")}},
   object:ReadingRuntimeProvider{override val id="fast";override suspend fun chapter(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry):ReadingContent?{delay(10);return ReadingContent(entry.kind,id,pageUrls=listOf("https://img.example/1.jpg"))}},
   object:ReadingRuntimeProvider{override val id="failed";override suspend fun chapter(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry):ReadingContent?=error("must be skipped")}
  )
  val race=ReadingProviderRuntimeRace(providers)
  val entry=ReadingCatalogEntry("x",ReadingKind.MANGA,"Manga","https://catalog.example/manga",ReadingEntryOrigin.MANUAL)
  val chapter=ReadingChapterEntry("c","1",sources=listOf(ReadingChapterSource("s1","slow","https://slow.example/c"),ReadingChapterSource("s2","fast","https://fast.example/c"),ReadingChapterSource("s3","failed","https://failed.example/c")))
  val result=race.race(entry,chapter,setOf("failed"))
  assertEquals("fast",result?.sourceId)
 }
 @Test fun runtimeRaceRejectsUsableContentOfWrongKind()=runBlocking{
  val provider=object:ReadingRuntimeProvider{
   override val id="wrong-kind"
   override suspend fun chapter(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry):ReadingContent?=
    ReadingContent(ReadingKind.NOVEL,id,text="novel text")
  }
  val race=ReadingProviderRuntimeRace(listOf(provider))
  val entry=ReadingCatalogEntry("x",ReadingKind.MANGA,"Manga","https://catalog.example/manga",ReadingEntryOrigin.MANUAL)
  val chapter=ReadingChapterEntry("c","1",sources=listOf(ReadingChapterSource("s","wrong-kind","https://reader.example/c")))
  assertNull(race.race(entry,chapter,emptySet()))
 }

 @Test fun runtimeRaceMatchesFailedProviderIdsCaseInsensitivelyAndWithWhitespace()=runBlocking{
  var shouldNotRun=false
  val provider=object:ReadingRuntimeProvider{
   override val id="Fast"
   override suspend fun chapter(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry):ReadingContent?{
    shouldNotRun=true
    return ReadingContent(entry.kind,id,pageUrls=listOf("https://img.example/1.jpg"))
   }
  }
  val race=ReadingProviderRuntimeRace(listOf(provider))
  val entry=ReadingCatalogEntry("x",ReadingKind.MANGA,"Manga","https://catalog.example/manga",ReadingEntryOrigin.MANUAL)
  val chapter=ReadingChapterEntry("c","1",sources=listOf(ReadingChapterSource("s","fast","https://fast.example/c")))
  assertNull(race.race(entry,chapter,setOf("  READING-PROVIDER:FAST ")))
  assertTrue(!shouldNotRun)
 }

 @Test fun runtimeRaceDoesNotRunProviderForDisabledChapterSource()=runBlocking{
  var called=false
  val provider=object:ReadingRuntimeProvider{
   override val id="disabled-provider"
   override suspend fun chapter(entry:ReadingCatalogEntry,chapter:ReadingChapterEntry):ReadingContent?{
    called=true
    return ReadingContent(entry.kind,id,pageUrls=listOf("https://img.example/1.jpg"))
   }
  }
  val race=ReadingProviderRuntimeRace(listOf(provider))
  val entry=ReadingCatalogEntry("x",ReadingKind.MANGA,"Manga","https://catalog.example/manga",ReadingEntryOrigin.MANUAL)
  val chapter=ReadingChapterEntry("c","1",sources=listOf(ReadingChapterSource("s","disabled-provider","https://reader.example/c",enabled=false)))
  assertNull(race.race(entry,chapter,emptySet()))
  assertTrue(!called)
 }

 @Test fun chapterSourceHealthUpdatesWithoutChangingCatalogMetadataOrOtherMirrors() {
  val service=ReadingCatalogService(FileReadingCatalogRepository(createTempDirectory("reading-source-health").resolve("reading.json")))
  val entry=service.putManual(ReadingCatalogEntry("ignored",ReadingKind.MANGA,"Title","https://catalog.example/title",ReadingEntryOrigin.MANUAL,description="Keep metadata",chapters=listOf(
   ReadingChapterEntry("chapter-1","1",sources=listOf(
    ReadingChapterSource("source-a","provider-a","https://a.example/1",ReadingSourceType.DIRECT),
    ReadingChapterSource("source-b","provider-b","https://b.example/1",ReadingSourceType.RESOLVER)
   ))
  )))
  val updated=service.updateChapterSourceHealth(entry.id,"chapter-1","source-a",ReadingSourceHealth.EXPIRED,1234L,410,"gone")
  assertEquals(ReadingSourceHealth.EXPIRED,updated?.health)
  assertEquals(1234L,updated?.lastCheckedAt)
  assertEquals(410,updated?.lastHttpStatus)
  assertEquals("gone",updated?.lastError)
  val saved=service.get(entry.id)
  assertEquals("Keep metadata",saved?.description)
  assertEquals(ReadingSourceHealth.EXPIRED,saved?.chapters?.single()?.sources?.first()?.health)
  assertEquals(ReadingSourceHealth.UNKNOWN,saved?.chapters?.single()?.sources?.last()?.health)
  assertNull(service.updateChapterSourceHealth(entry.id,"missing","source-a",ReadingSourceHealth.FAILED))
 }
 @Test fun individualSourceCanBeDisabledAndReenabledWithoutChangingMirrorsOrMetadata() {
  val service=ReadingCatalogService(FileReadingCatalogRepository(createTempDirectory("reading-source-enabled").resolve("reading.json")))
  val entry=service.putManual(ReadingCatalogEntry("ignored",ReadingKind.MANHWA,"Series","https://catalog.example/series",ReadingEntryOrigin.MANUAL,description="Canonical metadata",chapterUrls=listOf("https://a.example/1","https://b.example/1"),chapters=listOf(
   ReadingChapterEntry("chapter-1","1",sources=listOf(
    ReadingChapterSource("source-a","provider-a","https://a.example/1",ReadingSourceType.DIRECT),
    ReadingChapterSource("source-b","provider-b","https://b.example/1",ReadingSourceType.RESOLVER)
   ))
  )))
  assertEquals(false,service.setChapterSourceEnabled(entry.id,"chapter-1","source-a",false)?.enabled)
  var saved=service.get(entry.id)!!
  assertEquals("Canonical metadata",saved.description)
  assertEquals(false,saved.chapters.single().sources.first{it.id=="source-a"}.enabled)
  assertEquals(true,saved.chapters.single().sources.first{it.id=="source-b"}.enabled)
  assertEquals(true,service.setChapterSourceEnabled(entry.id,"chapter-1","source-a",true)?.enabled)
  assertNull(service.setChapterSourceEnabled(entry.id,"missing","source-a",false))
 }
 @Test fun deletingOneChapterSourceKeepsChapterAndOtherMirror() {
  val service=ReadingCatalogService(FileReadingCatalogRepository(createTempDirectory("reading-source-delete").resolve("reading.json")))
  val entry=service.putManual(ReadingCatalogEntry("ignored",ReadingKind.MANGA,"Series","https://catalog.example/series",ReadingEntryOrigin.MANUAL,chapterUrls=listOf("https://a.example/1","https://b.example/1"),chapters=listOf(
   ReadingChapterEntry("chapter-1","1",sources=listOf(
    ReadingChapterSource("source-a","provider-a","https://a.example/1",ReadingSourceType.DIRECT),
    ReadingChapterSource("source-b","provider-b","https://b.example/1",ReadingSourceType.RESOLVER)
   ))
  )))
  assertTrue(service.deleteChapterSource(entry.id,"chapter-1","source-a"))
  val saved=service.get(entry.id)!!
  assertEquals(1,saved.chapters.size)
  assertEquals(listOf("source-b"),saved.chapters.single().sources.map{it.id})
  assertEquals(listOf("https://b.example/1"),saved.chapterUrls)
  assertTrue(!service.deleteChapterSource(entry.id,"chapter-1","source-a"))
  assertTrue(service.deleteChapterSource(entry.id,"chapter-1","source-b"))
  assertEquals(1,service.get(entry.id)!!.chapters.size)
  assertEquals(emptyList(),service.get(entry.id)!!.chapters.single().sources)
 }
 @Test fun deletingSourceKeepsSharedChapterUrlWhenAnotherMirrorUsesSameUrl() {
  val service=ReadingCatalogService(FileReadingCatalogRepository(createTempDirectory("reading-source-shared-url").resolve("reading.json")))
  val entry=service.putManual(ReadingCatalogEntry("ignored",ReadingKind.MANGA,"Series","https://catalog.example/series",ReadingEntryOrigin.MANUAL,chapterUrls=listOf("https://shared.example/1"),chapters=listOf(
   ReadingChapterEntry("chapter-1","1",sources=listOf(
    ReadingChapterSource("source-a","provider-a","https://shared.example/1",ReadingSourceType.DIRECT),
    ReadingChapterSource("source-b","provider-b","https://shared.example/1",ReadingSourceType.RESOLVER)
   ))
  )))
  assertTrue(service.deleteChapterSource(entry.id,"chapter-1","source-a"))
  assertEquals(listOf("https://shared.example/1"),service.get(entry.id)?.chapterUrls)
 }
 @Test fun manualChapterCanBeAddedAndRemovedWithoutDeletingSeries() {
  val service=ReadingCatalogService(FileReadingCatalogRepository(createTempDirectory("reading-chapters").resolve("reading.json")))
  val entry=service.putManual(ReadingCatalogEntry("x",ReadingKind.NOVEL,"Novel","https://novel.example/book",ReadingEntryOrigin.MANUAL))
  val saved=service.putChapter(entry.id,ReadingChapterEntry("chapter-1","1","Chapter One",listOf(ReadingChapterSource("source-1","novel-host","https://novel.example/book/1",ReadingSourceType.DIRECT,ReadingEntryOrigin.MANUAL))))
  assertEquals(1,saved?.chapters?.size)
  assertTrue(service.deleteChapter(entry.id,"chapter-1"))
  assertEquals(0,service.get(entry.id)?.chapters?.size)
  assertNotNull(service.get(entry.id))
 }


 @Test fun chapterSourceStateSurvivesRepositoryRestart() {
  val file=createTempDirectory("reading-restart").resolve("reading.json")
  val firstRepository=FileReadingCatalogRepository(file)
  val firstService=ReadingCatalogService(firstRepository)
  val entry=firstService.putManual(ReadingCatalogEntry(
   "ignored",ReadingKind.MANHWA,"Series","https://catalog.example/series",ReadingEntryOrigin.MANUAL,
   description="Canonical metadata",
   chapters=listOf(ReadingChapterEntry("chapter-1","1",sources=listOf(
    ReadingChapterSource("mirror-a","provider-a","https://a.example/1",ReadingSourceType.DIRECT,enabled=false,health=ReadingSourceHealth.EXPIRED,lastCheckedAt=1234L,lastError="gone",lastHttpStatus=410),
    ReadingChapterSource("mirror-b","provider-b","https://b.example/1",ReadingSourceType.RESOLVER,enabled=true,health=ReadingSourceHealth.AVAILABLE,lastCheckedAt=2345L,lastHttpStatus=200)
   )))
  ))
  val restarted=ReadingCatalogService(FileReadingCatalogRepository(file)).get(entry.id)!!
  assertEquals("Canonical metadata",restarted.description)
  val sources=restarted.chapters.single().sources.associateBy{it.id}
  assertEquals(false,sources.getValue("mirror-a").enabled)
  assertEquals(ReadingSourceHealth.EXPIRED,sources.getValue("mirror-a").health)
  assertEquals(1234L,sources.getValue("mirror-a").lastCheckedAt)
  assertEquals("gone",sources.getValue("mirror-a").lastError)
  assertEquals(410,sources.getValue("mirror-a").lastHttpStatus)
  assertEquals(true,sources.getValue("mirror-b").enabled)
  assertEquals(ReadingSourceHealth.AVAILABLE,sources.getValue("mirror-b").health)
  assertEquals(200,sources.getValue("mirror-b").lastHttpStatus)
 }

 
 @Test fun novelRejectsImageOnlyContentBeforeReturningFromCatalogTier()=runBlocking {
  val runtime=ReadingProviderRuntime{entry,_,_->ReadingContent(entry.kind,"runtime",text="Actual novel paragraph")}
  val handlers=mapOf(ReadingSourceType.DIRECT to ReadingSourceHandler{entry,_,source->
   ReadingContent(entry.kind,source.id,pageUrls=listOf("https://cdn.example/not-novel.jpg"),format="image-pages")
  })
  val service=ReadingCatalogService(
   FileReadingCatalogRepository(createTempDirectory("reading-novel-payload-contract").resolve("reading.json")),
   handlers,
   runtime
  )
  val entry=service.putManual(ReadingCatalogEntry(
   "ignored",ReadingKind.NOVEL,"Novel","https://catalog.example/novel",ReadingEntryOrigin.MANUAL,
   chapters=listOf(ReadingChapterEntry("chapter-1","1",sources=listOf(
    ReadingChapterSource("direct-source","direct","https://reader.example/chapter/1",ReadingSourceType.DIRECT)
   )))
  ))

  val result=service.resolveChapter(entry.id,"chapter-1")

  assertEquals("runtime",result?.sourceId)
  assertEquals("Actual novel paragraph",result?.text)
 }
 
 @Test fun validCatalogChapterStopsBeforeProviderRuntime()=runBlocking {
  var runtimeCalls=0
  val runtime=ReadingProviderRuntime{entry,_,_->runtimeCalls++;ReadingContent(entry.kind,"runtime",pageUrls=listOf("https://runtime.example/page.jpg"))}
  val handlers=mapOf(ReadingSourceType.DIRECT to ReadingSourceHandler{entry,_,source->
   ReadingContent(entry.kind,source.id,pageUrls=listOf("https://catalog-cdn.example/page.jpg"),format="image-pages")
  })
  val service=ReadingCatalogService(
   FileReadingCatalogRepository(createTempDirectory("reading-catalog-first").resolve("reading.json")),
   handlers,
   runtime
  )
  val entry=service.putManual(ReadingCatalogEntry(
   "ignored",ReadingKind.MANGA,"Manga","https://catalog.example/manga",ReadingEntryOrigin.MANUAL,
   chapters=listOf(ReadingChapterEntry("chapter-1","1",sources=listOf(
    ReadingChapterSource("catalog-source","catalog","https://catalog.example/chapter/1",ReadingSourceType.DIRECT)
   )))
  ))

  val result=service.resolveChapter(entry.id,"chapter-1")

  assertEquals("catalog-source",result?.sourceId)
  assertEquals(listOf("https://catalog-cdn.example/page.jpg"),result?.pageUrls)
  assertEquals(0,runtimeCalls)
 }
 @Test fun missingCatalogChapterIsPassedToDiscoveryRuntimeWithoutPersistingIt()=runBlocking{
  var seenNumber:String?=null
  var seenSources:Int?=null
  val runtime=ReadingProviderRuntime{entry,chapter,_->
   seenNumber=chapter.number
   seenSources=chapter.sources.size
   ReadingContent(entry.kind,"discovery",text="chapter text")
  }
  val service=ReadingCatalogService(FileReadingCatalogRepository(createTempDirectory("reading-discovery-no-catalog-chapter").resolve("reading.json")),providerRuntime=runtime)
  val entry=service.putManual(ReadingCatalogEntry("ignored",ReadingKind.NOVEL,"Novel","https://catalog.example/novel",ReadingEntryOrigin.MANUAL,catalogKey="anilist:12345"))
  val content=service.resolveChapter(entry.id,"17")
  assertEquals("chapter text",content?.text)
  assertEquals("17",seenNumber)
  assertEquals(0,seenSources)
  assertTrue(service.get(entry.id)!!.chapters.isEmpty())
 }


 @Test fun linkingExistingUrlEntryToAniListKeepsOneCanonicalCatalogRow(){
  val service=ReadingCatalogService(FileReadingCatalogRepository(createTempDirectory("reading-link-anilist-existing-url").resolve("reading.json")))
  val original=service.putManual(ReadingCatalogEntry(
   "ignored",ReadingKind.MANGA,"Original title","https://reader.example/series",ReadingEntryOrigin.MANUAL
  ))

  val linked=service.putManual(ReadingCatalogEntry(
   "ignored",ReadingKind.MANGA,"Linked title","https://reader.example/series",ReadingEntryOrigin.MANUAL,
   catalogKey="anilist:4242",metadataId=4242L
  ))

  assertEquals(original.id,linked.id)
  assertEquals(1,service.list(ReadingKind.MANGA).size)
  assertEquals(4242L,service.get(original.id)?.metadataId)
  assertEquals("anilist:4242",service.get(original.id)?.catalogKey)
  assertEquals("Linked title",service.get(original.id)?.title)
 }

 @Test fun manualCatalogPreservesAniListMetadataIdentityAcrossPersistence(){
  val path=createTempDirectory("reading-anilist-link").resolve("reading.json")
  val repository=FileReadingCatalogRepository(path)
  val service=ReadingCatalogService(repository)
  val saved=service.putManual(ReadingCatalogEntry(
   "ignored",ReadingKind.MANHWA,"Example Manhwa","https://catalog.example/title",ReadingEntryOrigin.MANUAL,
   catalogKey="anilist:12345",metadataId=12345L
  ))
  val reloaded=ReadingCatalogService(FileReadingCatalogRepository(path)).get(saved.id)
  assertEquals(12345L,reloaded?.metadataId)
  assertEquals("anilist:12345",reloaded?.catalogKey)
  assertEquals(saved.id,reloaded?.id)
 }


 @Test fun manualCatalogRejectsInvalidAniListMetadataIdentity(){
  val service=ReadingCatalogService(FileReadingCatalogRepository(createTempDirectory("reading-anilist-invalid").resolve("reading.json")))
  assertTrue(runCatching{service.putManual(ReadingCatalogEntry(
   "x",ReadingKind.MANGA,"Manga","https://reader.example/manga",ReadingEntryOrigin.MANUAL,metadataId=0L
  ))}.isFailure)
  assertTrue(runCatching{service.putManual(ReadingCatalogEntry(
   "x",ReadingKind.MANGA,"Manga","https://reader.example/manga",ReadingEntryOrigin.MANUAL,metadataId=-1L
  ))}.isFailure)
 }


 @Test fun metadataOnlyUpdatePreservesCuratedChapterSourcesAndSeriesSources(){
  val service=ReadingCatalogService(FileReadingCatalogRepository(createTempDirectory("reading-metadata-only-update").resolve("reading.json")))
  val saved=service.putManual(ReadingCatalogEntry(
   "ignored",ReadingKind.MANHWA,"Original title","https://reader.example/series",ReadingEntryOrigin.MANUAL,
   catalogKey="anilist:9988",metadataId=9988L,
   sourceRefs=listOf(ReadingSourceRef("series-provider","https://reader.example/series/source")),
   chapterUrls=listOf("https://reader.example/series/chapter-1"),
   chapters=listOf(ReadingChapterEntry("chapter-1","1",sources=listOf(
    ReadingChapterSource("mirror-1","provider-a","https://reader.example/series/chapter-1",ReadingSourceType.DIRECT),
    ReadingChapterSource("mirror-2","provider-b","https://mirror.example/chapter-1",ReadingSourceType.RESOLVER)
   )))
  ))
  val updated=service.putManual(ReadingCatalogEntry(
   "ignored",ReadingKind.MANHWA,"Updated title","https://reader.example/series",ReadingEntryOrigin.MANUAL,
   description="Metadata edited"
  ))
  assertEquals(saved.id,updated.id)
  assertEquals("Updated title",updated.title)
  assertEquals("Metadata edited",updated.description)
  assertEquals(saved.sourceRefs,updated.sourceRefs)
  assertEquals(saved.chapterUrls,updated.chapterUrls)
  assertEquals(saved.chapters,updated.chapters)
 }

 @Test fun manualUpdatePreservesAniListIdentityWhenOptionalIdentityFieldsAreOmitted(){
  val service=ReadingCatalogService(FileReadingCatalogRepository(createTempDirectory("reading-identity-update").resolve("reading.json")))
  val saved=service.putManual(ReadingCatalogEntry(
   "ignored",ReadingKind.MANGA,"Original title","https://reader.example/series",ReadingEntryOrigin.MANUAL,
   catalogKey="anilist:6790",metadataId=6790L,description="Before"
  ))

  val updated=service.putManual(ReadingCatalogEntry(
   "ignored",ReadingKind.MANGA,"Updated title","https://reader.example/series",ReadingEntryOrigin.MANUAL,
   description="After"
  ))

  assertEquals(saved.id,updated.id)
  assertEquals(6790L,updated.metadataId)
  assertEquals("anilist:6790",updated.catalogKey)
  assertEquals("After",updated.description)
  assertEquals(1,service.list(ReadingKind.MANGA).size)
 }

 @Test fun manualMetadataUpdateDoesNotSilentlyReenableHiddenCatalogEntry(){
  val service=ReadingCatalogService(FileReadingCatalogRepository(createTempDirectory("reading-hidden-update").resolve("reading.json")))
  val saved=service.putManual(ReadingCatalogEntry(
   "ignored",ReadingKind.MANGA,"Original title","https://reader.example/series",ReadingEntryOrigin.MANUAL,
   catalogKey="anilist:6789",metadataId=6789L
  ))
  assertEquals(false,service.setEntryEnabled(saved.id,false)?.enabled)

  val updated=service.putManual(ReadingCatalogEntry(
   "ignored",ReadingKind.MANGA,"Updated title","https://reader.example/series",ReadingEntryOrigin.MANUAL,
   catalogKey="anilist:6789",metadataId=6789L,description="Updated description"
  ))

  assertEquals(saved.id,updated.id)
  assertEquals("Updated title",updated.title)
  assertEquals("Updated description",updated.description)
  assertEquals(false,updated.enabled)
  assertEquals(false,service.get(saved.id)?.enabled)
 }

 @Test fun catalogEntryCanBeHiddenAndReenabledWithoutDeletingMetadata(){
  val path=createTempDirectory("reading-entry-visibility").resolve("reading.json")
  val service=ReadingCatalogService(FileReadingCatalogRepository(path))
  val entry=service.putManual(ReadingCatalogEntry(
   "ignored",ReadingKind.MANHWA,"Example","https://reader.example/example",ReadingEntryOrigin.MANUAL,
   catalogKey="anilist:12345",metadataId=12345L
  ))
  val hidden=service.setEntryEnabled(entry.id,false)
  assertEquals(false,hidden?.enabled)
  assertEquals(12345L,hidden?.metadataId)
  assertEquals("anilist:12345",hidden?.catalogKey)
  val reloaded=ReadingCatalogService(FileReadingCatalogRepository(path))
  assertEquals(false,reloaded.get(entry.id)?.enabled)
  val visible=reloaded.setEntryEnabled(entry.id,true)
  assertEquals(true,visible?.enabled)
  assertEquals("Example",visible?.title)
 }


 @Test fun anilistLinkedEntryCanExistBeforeAnySourceUrlIsCurated(){
  val path=createTempDirectory("reading-anilist-without-source").resolve("reading.json")
  val service=ReadingCatalogService(FileReadingCatalogRepository(path))
  val entry=service.putManual(ReadingCatalogEntry(
   "ignored",ReadingKind.MANGA,"Not Yet Available","",ReadingEntryOrigin.MANUAL,
   metadataId=24680L,enabled=false
  ))
  assertEquals("anilist:24680",entry.catalogKey)
  assertEquals(24680L,entry.metadataId)
  assertEquals("",entry.url)
  assertEquals(false,entry.enabled)
  assertEquals(entry.id,ReadingCatalogService(FileReadingCatalogRepository(path)).get(entry.id)?.id)
 }
}
