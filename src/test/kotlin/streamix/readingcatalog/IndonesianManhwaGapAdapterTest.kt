package streamix.readingcatalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup

class IndonesianManhwaGapAdapterTest {
    private fun entry(kind: ReadingKind = ReadingKind.MANHWA) =
        ReadingCatalogEntry("m", kind, "Test", "https://example.invalid/title", ReadingEntryOrigin.MANUAL)

    private fun chapter(id: String, url: String) =
        ReadingChapterEntry("c", "1", sources = listOf(ReadingChapterSource("s", id, url)))

    @Test fun komikindoManhwaUsesDedicatedKindAndReaderSelectors() = runBlocking {
        val provider = KomikindoManhwaReadingRuntimeProvider { url ->
            Jsoup.parse("""<div id="readerarea"><img data-src="/p1.webp"><img src="https://cdn.example/p2.webp"></div>""", url)
        }
        val ch = chapter("komikindo-manhwa", "https://komikindo4.link/title-chapter-1/")
        assertEquals(listOf("https://komikindo4.link/p1.webp", "https://cdn.example/p2.webp"), provider.chapter(entry(), ch)?.pageUrls)
        assertFalse(provider.supports(entry(ReadingKind.MANGA), ch))
    }

    @Test fun komiknesiaManhwaUsesDedicatedChapterApi() = runBlocking {
        var requested = ""
        val provider = KomikNesiaManhwaReadingRuntimeProvider { url ->
            requested = url
            """{"data":{"images":["https://cdn.example/1.webp","https://cdn.example/2.webp"]}}"""
        }
        val ch = chapter("komiknesia-manhwa", "https://v2.komiknesiaku.com/view/chapter-slug")
        assertEquals(listOf("https://cdn.example/1.webp", "https://cdn.example/2.webp"), provider.chapter(entry(), ch)?.pageUrls)
        assertEquals("https://api-be.komiknesia.my.id/api/chapters/slug/chapter-slug", requested)
        assertNull(provider.chapter(entry(ReadingKind.MANGA), ch))
    }

    @Test fun westMangaManhwaUsesChapterSlugApi() = runBlocking {
        var requested = ""
        val provider = WestMangaManhwaReadingRuntimeProvider { url, _ ->
            requested = url
            """{"data":{"images":["https://cdn.example/west.webp"]}}"""
        }
        val ch = chapter("west-manga-manhwa", "https://v1.westmanga.my/title/chapter-7")
        assertEquals(listOf("https://cdn.example/west.webp"), provider.chapter(entry(), ch)?.pageUrls)
        assertEquals("https://data.mantweh.online/api/v/chapter-7", requested)
        assertNull(provider.chapter(entry(ReadingKind.MANHUA), ch))
    }

    @Test fun mgKomikManhwaExtractsReaderAreaImages() = runBlocking {
        val provider = MGKomikManhwaReadingRuntimeProvider { url ->
            Jsoup.parse("""<div id="readerarea"><img data-original="/mg-page.webp"></div>""", url)
        }
        val ch = chapter("mg-komik-manhwa", "https://id.mgkomik.cc/title/chapter-1")
        assertEquals(listOf("https://id.mgkomik.cc/mg-page.webp"), provider.chapter(entry(), ch)?.pageUrls)
        assertFalse(provider.supports(entry(ReadingKind.MANGA), ch))
    }

    @Test fun komikuManhwaExtractsReaderImagesAndRejectsOtherKinds() = runBlocking {
        val provider = KomikuManhwaReadingRuntimeProvider { url ->
            Jsoup.parse("""<div id="Baca_Komik"><img data-src="/komiku-page.webp"></div>""", url)
        }
        val ch = chapter("komiku-manhwa", "https://komiku.org/title/chapter-1")
        assertEquals(listOf("https://komiku.org/title/komiku-page.webp"), provider.chapter(entry(), ch)?.pageUrls)
        assertNull(provider.chapter(entry(ReadingKind.MANGA), ch))
    }

    @Test fun allFiveProvidersRejectWrongProviderIds() = runBlocking {
        val cases = listOf(
            KomikindoManhwaReadingRuntimeProvider() to chapter("komikindo", "https://komikindo4.link/title-chapter-1"),
            KomikNesiaManhwaReadingRuntimeProvider() to chapter("komiknesia", "https://v2.komiknesiaku.com/view/chapter-slug"),
            WestMangaManhwaReadingRuntimeProvider() to chapter("west-manga", "https://v1.westmanga.my/title/chapter-7"),
            MGKomikManhwaReadingRuntimeProvider() to chapter("mg-komik", "https://id.mgkomik.cc/title/chapter-1"),
            KomikuManhwaReadingRuntimeProvider() to chapter("komiku", "https://komiku.org/title/chapter-1"),
        )
        cases.forEach { (provider, ch) -> assertFalse(provider.supports(entry(), ch)) }
    }
}
