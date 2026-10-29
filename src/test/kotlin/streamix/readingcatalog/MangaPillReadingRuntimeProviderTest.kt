package streamix.readingcatalog

import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class MangaPillReadingRuntimeProviderTest {
    private fun entry() = ReadingCatalogEntry(
        "m", ReadingKind.MANGA, "Manga", "https://mangapill.com/manga/title", ReadingEntryOrigin.MANUAL
    )

    private fun chapter(url: String) = ReadingChapterEntry(
        "c", "1", sources = listOf(ReadingChapterSource("s", "mangapill", url))
    )

    @Test
    fun extractsLazyPictureImagesInDocumentOrderAndDeduplicates() = runBlocking {
        val provider = MangaPillReadingRuntimeProvider { url ->
            Jsoup.parse(
                """<picture><img data-src="/images/1.jpg" src="data:image/gif;base64,x"></picture>
                   <picture><img data-original="https://cdn.example/2.jpg"></picture>
                   <picture><img src="/images/1.jpg"></picture>""",
                url
            )
        }
        val result = provider.chapter(entry(), chapter("https://mangapill.com/chapters/title-chapter-1"))
        assertEquals(
            listOf("https://mangapill.com/images/1.jpg", "https://cdn.example/2.jpg"),
            result?.pageUrls
        )
    }

    @Test
    fun fallsBackToDataLazySrcWhenDataSrcIsMissing() = runBlocking {
        val provider = MangaPillReadingRuntimeProvider { url ->
            Jsoup.parse("""<picture><img data-lazy-src="/lazy.jpg" src="data:image/gif;base64,x"></picture>""", url)
        }
        assertEquals(listOf("https://mangapill.com/lazy.jpg"), provider.chapter(entry(), chapter("https://mangapill.com/chapters/title-chapter-1"))?.pageUrls)
    }

    @Test
    fun rejectsChapterUrlsWithCredentialsOrNonstandardPorts() = runBlocking {
        val provider = MangaPillReadingRuntimeProvider { Jsoup.parse("<picture><img src='/1.jpg'></picture>") }
        assertNull(provider.chapter(entry(), chapter("https://user@mangapill.com/chapters/title-chapter-1")))
        assertNull(provider.chapter(entry(), chapter("https://mangapill.com:8443/chapters/title-chapter-1")))
    }

    @Test
    fun rejectsImageUrlsWithNonstandardPortsAndUnsafeSchemes() = runBlocking {
        val provider = MangaPillReadingRuntimeProvider { url ->
            Jsoup.parse(
                """<picture><img src="https://cdn.example:8443/1.jpg"></picture>
                   <picture><img src="http://cdn.example/2.jpg"></picture>
                   <picture><img src="https://cdn.example/3.jpg"></picture>""",
                url
            )
        }
        val result = provider.chapter(entry(), chapter("https://mangapill.com/chapters/title-chapter-1"))
        assertEquals(listOf("https://cdn.example/3.jpg"), result?.pageUrls)
    }
}
