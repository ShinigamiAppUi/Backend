package streamix.readingcatalog

import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class IndonesianMangaReadingRuntimeProviderTest {
    private fun entry() = ReadingCatalogEntry("m", ReadingKind.MANGA, "Komik", "https://example.invalid/manga", ReadingEntryOrigin.MANUAL)
    private fun chapter(id: String, url: String) = ReadingChapterEntry("c", "1", sources = listOf(ReadingChapterSource("s", id, url)))

    @Test fun komikuUsesBacaKomikContainerAndSkipsPromo() = runBlocking {
        val provider = KomikuReadingRuntimeProvider { url -> Jsoup.parse("""<div id="Baca_Komik"><img src="/p1.jpg"><img src="/komiku-promosi.png"></div>""", url) }
        assertEquals(listOf("https://komiku.org/p1.jpg"), provider.chapter(entry(), chapter("komiku", "https://komiku.org/manga/title/chapter-1"))?.pageUrls)
    }

    @Test fun bacamiReadsImageUrlsScript() = runBlocking {
        val provider = BacamiReadingRuntimeProvider { url -> Jsoup.parse("""<script>const data = { imageUrls: ["https://cdn.example/p1.jpg","https://cdn.example/p2.jpg"] };</script>""", url) }
        assertEquals(2, provider.chapter(entry(), chapter("bacami", "https://v1.bacami.site/title-chapter-1"))?.pageUrls?.size)
    }

    @Test fun komikIndoUsesImageLandmineAndFallback() = runBlocking {
        val provider = KomikIndoIdReadingRuntimeProvider { url -> Jsoup.parse("""<div class="img-landmine"><img data-src="/p1.jpg"></div>""", url) }
        assertEquals(listOf("https://komikindo.ch/p1.jpg"), provider.chapter(entry(), chapter("komikindoid", "https://komikindo.ch/chapter/title-1"))?.pageUrls)
    }

    @Test fun komikIndoSkipsDataUriPlaceholderAndUsesOriginalImage() = runBlocking {
        val provider = KomikIndoIdReadingRuntimeProvider { url -> Jsoup.parse("""<div class="img-landmine"><img data-lazy-src="data:image/gif;base64,placeholder" data-original="/original.jpg"></div>""", url) }
        assertEquals(listOf("https://komikindo.ch/original.jpg"), provider.chapter(entry(), chapter("komikindoid", "https://komikindo.ch/chapter/title-1"))?.pageUrls)
    }

    @Test fun klikMangaFallsBackToDataOriginalAndSkipsDataUri() = runBlocking {
        val provider = KlikMangaReadingRuntimeProvider { url -> Jsoup.parse("""<div class="reading-content"><img data-original="/original.jpg" src="data:image/gif;base64,placeholder"></div>""", url) }
        assertEquals(listOf("https://klikmanga.org/original.jpg"), provider.chapter(entry(), chapter("klikmanga", "https://klikmanga.org/chapter/title-1"))?.pageUrls)
    }

    @Test fun klikMangaRejectsUnknownHost() = runBlocking {
        val provider = KlikMangaReadingRuntimeProvider { url -> Jsoup.parse("""<div class="reading-content"><img src="/p1.jpg"></div>""", url) }
        assertNull(provider.chapter(entry(), chapter("klikmanga", "https://evil.invalid/chapter/title-1")))
    }

    @Test fun komikuFallsBackToLazyImageAndStillSkipsPromo() = runBlocking {
        val provider = KomikuReadingRuntimeProvider { url -> Jsoup.parse("""<div id="Baca_Komik"><img data-src="/p1.jpg"><img data-original="/komiku-promosi.png"></div>""", url) }
        assertEquals(listOf("https://komiku.org/p1.jpg"), provider.chapter(entry(), chapter("komiku", "https://komiku.org/manga/title/chapter-1"))?.pageUrls)
    }
}
