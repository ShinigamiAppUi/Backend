package streamix.readingcatalog

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IndonesianSpecializedApiReadingRuntimeProviderTest {
    private fun entry(url: String) = ReadingCatalogEntry(
        id = "manga",
        kind = ReadingKind.MANGA,
        title = "Fixture",
        url = url,
        origin = ReadingEntryOrigin.MANUAL
    )

    private fun chapter(id: String, url: String) = ReadingChapterEntry(
        id = "chapter",
        number = "1",
        sources = listOf(ReadingChapterSource("source", id, url))
    )

    @Test
    fun westMangaParsesApiImageListAndRejectsUnsafeChapterUrls() = runBlocking {
        var requestedUrl = ""
        val provider = WestMangaReadingRuntimeProvider { url, _ ->
            requestedUrl = url
            """{"data":{"images":["https://cdn.example/west-1.jpg","http://cdn.example/unsafe.jpg"]}}"""
        }
        val url = "https://v1.westmanga.my/title-slug/chapter-1"
        val result = provider.chapter(entry(url), chapter(provider.id, url))
        assertEquals("https://data.mantweh.online/api/v/title-slug", requestedUrl)
        assertEquals(listOf("https://cdn.example/west-1.jpg"), result?.pageUrls)
        assertTrue(provider.supports(entry(url), chapter(provider.id, url)))
        val seriesUrl = "https://v1.westmanga.my/title-slug"
        assertFalse(provider.supports(entry(seriesUrl), chapter(provider.id, seriesUrl)), "Accepted a series URL as a chapter URL")
        for (unsafe in listOf(
            "https://user@v1.westmanga.my/title-slug/chapter-1",
            "https://v1.westmanga.my:8443/title-slug/chapter-1"
        )) {
            assertFalse(provider.supports(entry(unsafe), chapter(provider.id, unsafe)), "Accepted $unsafe")
        }
    }

    @Test
    fun riztranslationParsesSupabaseImageArrayAndRejectsUnsafeChapterUrls() = runBlocking {
        var requestedUrl = ""
        val provider = RiztranslationReadingRuntimeProvider { url, _ ->
            requestedUrl = url
            """[{"id":12,"isigambar":["https://cdn.example/riz-1.jpg","http://cdn.example/unsafe.jpg"]}]"""
        }
        val url = "https://riztranslation.pages.dev/chapters/123"
        val result = provider.chapter(entry(url), chapter(provider.id, url))
        assertTrue(requestedUrl.startsWith("https://uefnaojxivvxeamljskn.supabase.co/rest/v1/Chapter?"))
        assertEquals(listOf("https://cdn.example/riz-1.jpg"), result?.pageUrls)
        for (unsafe in listOf(
            "https://user@riztranslation.pages.dev/chapters/123",
            "https://riztranslation.pages.dev:8443/chapters/123"
        )) {
            assertFalse(provider.supports(entry(unsafe), chapter(provider.id, unsafe)), "Accepted $unsafe")
        }
    }

    @Test
    fun mikoRokuExtractsFirestoreImageFieldsAndRejectsUnsafeChapterUrls() = runBlocking {
        val provider = MikoRokuReadingRuntimeProvider {
            """{"fields":{"images":{"arrayValue":{"values":[{"stringValue":"https://cdn.example/miko-1.jpg"},{"stringValue":"http://cdn.example/unsafe.jpg"}]}}}}"""
        }
        val url = "https://mikoroku.com/manga/title-slug/chapter/abc123"
        val result = provider.chapter(entry(url), chapter(provider.id, url))
        assertEquals(listOf("https://cdn.example/miko-1.jpg"), result?.pageUrls)
        for (unsafe in listOf(
            "https://user@mikoroku.com/manga/title-slug/chapter/abc123",
            "https://mikoroku.com:8443/manga/title-slug/chapter/abc123"
        )) {
            assertFalse(provider.supports(entry(unsafe), chapter(provider.id, unsafe)), "Accepted $unsafe")
        }
    }
}
