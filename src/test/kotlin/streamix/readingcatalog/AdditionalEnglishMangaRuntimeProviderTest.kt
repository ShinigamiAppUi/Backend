package streamix.readingcatalog

import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class AdditionalEnglishMangaRuntimeProviderTest {
    private fun entry() = ReadingCatalogEntry("m", ReadingKind.MANGA, "Manga", "https://example.invalid/title", ReadingEntryOrigin.MANUAL)
    private fun chapter(id: String, url: String) = ReadingChapterEntry("c", "1", sources = listOf(ReadingChapterSource("s", id, url)))

    @Test fun mangaGGFallsBackToDataOriginal() = runBlocking {
        val provider = MangaGGReadingRuntimeProvider { url -> Jsoup.parse("""<div id="readerarea"><img data-original="/original.jpg" src="data:image/gif;base64,placeholder"></div>""", url) }
        assertEquals(listOf("https://mangagg.com/original.jpg"), provider.chapter(entry(), chapter("manggagg", "https://mangagg.com/chapter/title"))?.pageUrls)
    }

    @Test fun mangaGekoFallsBackToDataOriginal() = runBlocking {
        val provider = MangaGekoReadingRuntimeProvider { url -> Jsoup.parse("""<div id="chapter-reader"><img data-original="/original.jpg" src="data:image/gif;base64,placeholder"></div>""", url) }
        assertEquals(listOf("https://www.mgeko.cc/original.jpg"), provider.chapter(entry(), chapter("mangageko", "https://www.mgeko.cc/chapter/title"))?.pageUrls)
    }

    @Test fun mangaGGReadsItsLazyImageFallbacks() = runBlocking {
        val provider = MangaGGReadingRuntimeProvider { url -> Jsoup.parse("""<div id="readerarea"><img data-cfsrc="/p.jpg"></div>""", url) }
        assertEquals(listOf("https://mangagg.com/p.jpg"), provider.chapter(entry(), chapter("manggagg", "https://mangagg.com/chapter/title"))?.pageUrls)
    }

    @Test fun mangaNowUsesIvCardPageNodes() = runBlocking {
        val provider = MangaNowReadingRuntimeProvider { url -> Jsoup.parse("""<div class="container-reader-chapter"><div class="iv-card" data-url="/p.jpg"></div></div>""", url) }
        assertEquals(listOf("https://manganow.to/p.jpg"), provider.chapter(entry(), chapter("manganow", "https://manganow.to/chapter/title"))?.pageUrls)
    }

    @Test fun mangaNowFallsBackToDataOriginalWhenDataUrlIsPlaceholder() = runBlocking {
        val provider = MangaNowReadingRuntimeProvider { url -> Jsoup.parse("""<div class="container-reader-chapter"><div class="iv-card" data-url="data:image/gif;base64,placeholder" data-original="/original.jpg"></div></div>""", url) }
        assertEquals(listOf("https://manganow.to/original.jpg"), provider.chapter(entry(), chapter("manganow", "https://manganow.to/chapter/title"))?.pageUrls)
    }

    @Test fun mangaGekoUsesReaderImages() = runBlocking {
        val provider = MangaGekoReadingRuntimeProvider { url -> Jsoup.parse("""<div id="chapter-reader"><img data-src="/p.jpg"></div>""", url) }
        assertEquals(listOf("https://www.mgeko.cc/p.jpg"), provider.chapter(entry(), chapter("mangageko", "https://www.mgeko.cc/chapter/title"))?.pageUrls)
    }
}
