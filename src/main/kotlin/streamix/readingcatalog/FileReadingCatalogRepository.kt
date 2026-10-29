package streamix.readingcatalog
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
class FileReadingCatalogRepository(file:Path,private val gson:Gson=GsonBuilder().create()):ReadingCatalogRepository{
 private val file=file.toAbsolutePath().normalize();private val lock=locks.computeIfAbsent(this.file){Any()};private val listType=object:TypeToken<List<ReadingCatalogEntry>>(){}.type
 override fun all(kind:ReadingKind?):List<ReadingCatalogEntry> = synchronized(lock){read().filter{kind==null||it.kind==kind}.sortedBy{it.title.lowercase()}}
 override fun find(id:String):ReadingCatalogEntry?=synchronized(lock){read().firstOrNull{it.id==id}}
 override fun upsert(entry:ReadingCatalogEntry):ReadingCatalogEntry=synchronized(lock){
  val entries=read();val old=entries.firstOrNull{it.id==entry.id}
  val saved=entry.copy(sourceRefs=((old?.sourceRefs).orEmpty()+entry.sourceRefs).distinctBy{it.sourceId+"|"+it.url},chapters=mergeChapters(old?.chapters.orEmpty(),entry.chapters))
  write(entries.filterNot{it.id==saved.id}+saved);saved
 }
 override fun replace(entry:ReadingCatalogEntry):ReadingCatalogEntry=synchronized(lock){
  val entries=read();val saved=entry.copy(sourceRefs=entry.sourceRefs.distinctBy{it.sourceId+"|"+it.url},chapters=entry.chapters.distinctBy{it.id},updatedAt=System.currentTimeMillis())
  write(entries.filterNot{it.id==saved.id}+saved);saved
 }
 private fun mergeChapters(first:List<ReadingChapterEntry>,second:List<ReadingChapterEntry>):List<ReadingChapterEntry>{
  val result=linkedMapOf<String,ReadingChapterEntry>();(first+second).forEach{incoming->val old=result[incoming.id];result[incoming.id]=if(old==null)incoming else old.copy(title=old.title?:incoming.title,sources=(old.sources+incoming.sources).distinctBy{it.type.name+"|"+it.providerId+"|"+it.url})};return result.values.toList()
 }
 override fun delete(id:String):Boolean=synchronized(lock){val entries=read();val next=entries.filterNot{it.id==id};if(next.size==entries.size)false else{write(next);true}}
 private fun read():List<ReadingCatalogEntry>{
  if(!Files.exists(file))return emptyList()
  val raw=Files.readString(file)
  // Migrate persisted entries from the removed scraper pipeline while preserving every curated URL.
  val migrated=raw.replace(Regex("\"origin\"\\s*:\\s*\"SCRAPER\""), "\"origin\":\"MANUAL\"")
  val entries=runCatching{gson.fromJson<List<ReadingCatalogEntry>>(migrated,listType).orEmpty()}.getOrDefault(emptyList())
  if(migrated!=raw)write(entries)
  return entries
 }
 private fun write(entries:List<ReadingCatalogEntry>){Files.createDirectories(file.parent);val temp=file.resolveSibling(file.fileName.toString()+".tmp");Files.writeString(temp,gson.toJson(entries,listType));try{Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)}catch(_:Exception){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING)}}
 companion object{private val locks=ConcurrentHashMap<Path,Any>()}
}
