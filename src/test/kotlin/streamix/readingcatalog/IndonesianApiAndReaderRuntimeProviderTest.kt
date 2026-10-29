package streamix.readingcatalog

import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class IndonesianApiAndReaderRuntimeProviderTest {
    @Test fun komikuAsiaUsesChapterDetailsApi() = runBlocking {
        var requested = ""
        val p = KomikuAsiaReadingRuntimeProvider { u -> requested = u; """{"id":12,"pages":[{"url":"https://cdn.komiku.asia/1.jpg"},{"url":"https://cdn.komiku.asia/2.jpg"}]}""" }
        val e = ReadingCatalogEntry("m", ReadingKind.MANGA, "Komik", "https://01.komiku.asia/manga/title", ReadingEntryOrigin.MANUAL)
        val c = ReadingChapterEntry("c", "1", sources = listOf(ReadingChapterSource("s", "komiku-asia", "https://01.komiku.asia/title/123")))
        assertEquals(true, p.supports(e,c))
        val result = p.chapter(e,c)
        assertEquals("https://01.komiku.asia/api/v2/comics/title/chapters/id/123", requested)
        assertEquals(listOf("https://cdn.komiku.asia/1.jpg", "https://cdn.komiku.asia/2.jpg"), result?.pageUrls)
    }
    @Test fun komikuAsiaRejectsForeignHost() = runBlocking {
        val p = KomikuAsiaReadingRuntimeProvider { error("must not fetch") }
        val e = ReadingCatalogEntry("m", ReadingKind.MANGA, "Komik", "https://01.komiku.asia/manga/title", ReadingEntryOrigin.MANUAL)
        val c = ReadingChapterEntry("c", "1", sources = listOf(ReadingChapterSource("s", "komiku-asia", "https://evil.example/title/123")))
        assertFalse(p.supports(e,c)); assertNull(p.chapter(e,c))
    }
    @Test fun komikuAsiaRejectsMalformedJson() = runBlocking {
        val p = KomikuAsiaReadingRuntimeProvider { """{"pages":[null,"",{"unexpected":"shape"}]}""" }
        val e = ReadingCatalogEntry("m", ReadingKind.MANGA, "Komik", "https://01.komiku.asia/manga/title", ReadingEntryOrigin.MANUAL)
        val c = ReadingChapterEntry("c", "1", sources = listOf(ReadingChapterSource("s", "komiku-asia", "https://01.komiku.asia/title/123")))
        assertNull(p.chapter(e,c))
    }
    @Test fun siikomikUsesMadaraReaderArea() = runBlocking {
        val p = SiikomikReadingRuntimeProvider { u -> Jsoup.parse("""<div id="readerarea"><img data-src="/p.jpg"></div>""", u) }
        val e = ReadingCatalogEntry("m", ReadingKind.MANGA, "Komik", "https://siikomik.id/komik/title", ReadingEntryOrigin.MANUAL)
        val c = ReadingChapterEntry("c", "1", sources = listOf(ReadingChapterSource("s", "siikomik", "https://siikomik.id/komik/title/chapter-1")))
        assertEquals(listOf("https://siikomik.id/p.jpg"), p.chapter(e,c)?.pageUrls)
    }
    @Test fun narasiNinjaUsesTsMainImageReader() = runBlocking {
        val p = NarasiNinjaReadingRuntimeProvider { u -> Jsoup.parse("""<div id="readerarea"><img class="ts-main-image" src="/p.jpg"></div>""", u) }
        val e = ReadingCatalogEntry("m", ReadingKind.MANHWA, "Manhwa", "https://narasininja.net/komik/title", ReadingEntryOrigin.MANUAL)
        val c = ReadingChapterEntry("c", "1", sources = listOf(ReadingChapterSource("s", "narasininja", "https://narasininja.net/komik/title/chapter-1")))
        assertEquals(listOf("https://narasininja.net/p.jpg"), p.chapter(e,c)?.pageUrls)
    }
}
