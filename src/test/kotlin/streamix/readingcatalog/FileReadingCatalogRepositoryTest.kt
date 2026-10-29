package streamix.readingcatalog
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.nio.file.Files

class FileReadingCatalogRepositoryTest {
 @Test fun legacyScraperOriginsMigrateToManualAndPreserveChapterUrls() {
  val dir=createTempDirectory("reading-catalog-origin-migration")
  val file=dir.resolve("reading.json")
  Files.writeString(file, """[{"id":"legacy","kind":"MANGA","title":"Legacy title","url":"https://reader.example/title","origin":"SCRAPER","chapterUrls":["https://reader.example/chapter/1"],"chapters":[{"id":"c1","number":"1","sources":[{"id":"s1","providerId":"reader","url":"https://reader.example/chapter/1","type":"EXTRACTOR","origin":"SCRAPER"}]}]}]""")
  val repository=FileReadingCatalogRepository(file)
  val entry=repository.find("legacy")!!
  assertEquals(ReadingEntryOrigin.MANUAL,entry.origin)
  assertEquals(listOf("https://reader.example/chapter/1"),entry.chapterUrls)
  assertEquals(ReadingEntryOrigin.MANUAL,entry.chapters.single().sources.single().origin)
  val persisted=Files.readString(file)
  assertTrue(!persisted.contains("SCRAPER"))
  assertTrue(persisted.contains("https://reader.example/chapter/1"))
 }
}
