package streamix.readingcatalog

import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class MangaKReadingRuntimeProviderTest {
    @Test fun extractsNextJsInitialChapterImages() = runBlocking {
        val p = MangaKReadingRuntimeProvider { u -> Jsoup.parse("""<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"initialChapter":{"images":["https://cdn.mangak.io/1.jpg","https://cdn.mangak.io/2.jpg"]}}}}</script>""", u) }
        val e = ReadingCatalogEntry("m", ReadingKind.MANGA, "Manga", "https://mangak.io/manga/title", ReadingEntryOrigin.MANUAL)
        val c = ReadingChapterEntry("c", "1", sources = listOf(ReadingChapterSource("s", "mangak", "https://mangak.io/manga/title/chapter-1")))
        assertEquals(listOf("https://cdn.mangak.io/1.jpg", "https://cdn.mangak.io/2.jpg"), p.chapter(e,c)?.pageUrls)
    }
}
