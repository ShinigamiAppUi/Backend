package streamix.readingcatalog

import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class MGKomikReadingRuntimeProviderTest {
    @Test fun fallsBackToDataOriginalAndSkipsDataUriPlaceholder() = runBlocking {
        val provider = MGKomikReadingRuntimeProvider { url -> Jsoup.parse("""<div id="readerarea"><img data-original="/original.jpg" src="data:image/gif;base64,placeholder"></div>""", url) }
        val entry = ReadingCatalogEntry("m", ReadingKind.MANGA, "Manga", "https://id.mgkomik.cc/komik/title", ReadingEntryOrigin.MANUAL)
        val chapter = ReadingChapterEntry("c", "1", sources = listOf(ReadingChapterSource("s", "mg-komik", "https://id.mgkomik.cc/komik/title/chapter-1")))
        assertEquals(listOf("https://id.mgkomik.cc/original.jpg"), provider.chapter(entry,chapter)?.pageUrls)
    }

    @Test fun readsMadaraNoAjaxReaderArea() = runBlocking {
        val provider = MGKomikReadingRuntimeProvider { url -> Jsoup.parse("""<div id="readerarea"><img data-lazy-src="/p.jpg"></div>""", url) }
        val entry = ReadingCatalogEntry("m", ReadingKind.MANGA, "Manga", "https://id.mgkomik.cc/komik/title", ReadingEntryOrigin.MANUAL)
        val chapter = ReadingChapterEntry("c", "1", sources = listOf(ReadingChapterSource("s", "mg-komik", "https://id.mgkomik.cc/komik/title/chapter-1")))
        assertEquals(listOf("https://id.mgkomik.cc/p.jpg"), provider.chapter(entry, chapter)?.pageUrls)
    }
}
