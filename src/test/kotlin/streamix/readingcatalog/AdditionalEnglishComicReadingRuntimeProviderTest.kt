package streamix.readingcatalog

import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class AdditionalEnglishComicReadingRuntimeProviderTest {
    @Test fun batoSupportsAllComicKindsAndReadsReaderImages() = runBlocking {
        val provider = BatoReadingRuntimeProvider { url ->
            Jsoup.parse("""<div class="pages"><div class="page"><img data-src="/img/1.jpg"></div><div class="page notice-page"><img src="/notice.jpg"></div></div>""", url)
        }
        for (kind in listOf(ReadingKind.MANGA, ReadingKind.MANHWA, ReadingKind.MANHUA)) {
            val entry = ReadingCatalogEntry("entry", kind, "Title", "https://bato.to/manga/title", ReadingEntryOrigin.MANUAL)
            val chapter = ReadingChapterEntry("chapter", "1", sources = listOf(ReadingChapterSource("source", "bato", "https://bato.to/read/title/chapter-1")))
            assertTrue(provider.supports(entry, chapter))
            assertEquals(listOf("https://bato.to/img/1.jpg"), provider.chapter(entry, chapter)?.pageUrls)
        }
        val novel = ReadingCatalogEntry("novel", ReadingKind.NOVEL, "Novel", "https://bato.to/read/novel", ReadingEntryOrigin.MANUAL)
        assertFalse(provider.supports(novel, ReadingChapterEntry("c", "1", sources = listOf(ReadingChapterSource("s", "bato", "https://bato.to/read/novel/chapter-1")))))
    }

    @Test fun batoRejectsForeignChapterHosts() = runBlocking {
        val provider = BatoReadingRuntimeProvider { error("must not fetch") }
        val entry = ReadingCatalogEntry("entry", ReadingKind.MANGA, "Title", "https://bato.to/manga/title", ReadingEntryOrigin.MANUAL)
        val chapter = ReadingChapterEntry("chapter", "1", sources = listOf(ReadingChapterSource("source", "bato", "https://evil.example/read/title/chapter-1")))
        assertFalse(provider.supports(entry, chapter))
        assertNull(provider.chapter(entry, chapter))
    }

    @Test fun comickReadsEmbeddedJsonForAllComicKinds() = runBlocking {
        val provider = ComickReadingRuntimeProvider { url ->
            Jsoup.parse("""<script id="sv-data" type="application/json">{"chapter":{"images":[{"url":"https://meo.comick.live/img/1.jpg"},{"url":"https://meo.comick.live/img/2.jpg"}]}}</script>""", url)
        }
        for (kind in listOf(ReadingKind.MANGA, ReadingKind.MANHWA, ReadingKind.MANHUA)) {
            val entry = ReadingCatalogEntry("entry", kind, "Title", "https://comick.dev/comic/title", ReadingEntryOrigin.MANUAL)
            val chapter = ReadingChapterEntry("chapter", "1", sources = listOf(ReadingChapterSource("source", "comick", "https://comick.dev/comic/title/abc-chapter-1-en")))
            assertTrue(provider.supports(entry, chapter))
            assertEquals(listOf("https://meo.comick.live/img/1.jpg", "https://meo.comick.live/img/2.jpg"), provider.chapter(entry, chapter)?.pageUrls)
        }
    }

    @Test fun comickDomImagesFallBackToDataOriginal() = runBlocking {
        val provider = ComickReadingRuntimeProvider { url -> Jsoup.parse("""<div class="chapter-container"><img data-original="/original.jpg" src="data:image/gif;base64,placeholder"></div>""", url) }
        val entry = ReadingCatalogEntry("entry", ReadingKind.MANGA, "Title", "https://comick.dev/comic/title", ReadingEntryOrigin.MANUAL)
        val chapter = ReadingChapterEntry("chapter", "1", sources = listOf(ReadingChapterSource("source", "comick", "https://comick.dev/comic/title/abc-chapter-1-en")))
        assertEquals(listOf("https://comick.dev/original.jpg"), provider.chapter(entry, chapter)?.pageUrls)
    }

    @Test fun comickRejectsForeignChapterHosts() = runBlocking {
        val provider = ComickReadingRuntimeProvider { error("must not fetch") }
        val entry = ReadingCatalogEntry("entry", ReadingKind.MANGA, "Title", "https://comick.dev/comic/title", ReadingEntryOrigin.MANUAL)
        val chapter = ReadingChapterEntry("chapter", "1", sources = listOf(ReadingChapterSource("source", "comick", "https://evil.example/comic/title/abc-chapter-1-en")))
        assertFalse(provider.supports(entry, chapter))
        assertNull(provider.chapter(entry, chapter))
    }

    @Test fun batoFallsBackToLazyAndOriginalImageAttributes() = runBlocking {
        val provider = BatoReadingRuntimeProvider { url ->
            Jsoup.parse("""<div class="pages"><div class="page"><img data-lazy-src="/lazy.jpg" src="data:image/gif;base64,placeholder"></div><div class="page"><img data-original="/original.jpg" src="data:image/gif;base64,placeholder"></div></div>""", url)
        }
        val entry = ReadingCatalogEntry("entry", ReadingKind.MANHUA, "Title", "https://bato.to/manga/title", ReadingEntryOrigin.MANUAL)
        val chapter = ReadingChapterEntry("chapter", "1", sources = listOf(ReadingChapterSource("source", "bato", "https://bato.to/read/title/chapter-1")))
        assertEquals(listOf("https://bato.to/lazy.jpg", "https://bato.to/original.jpg"), provider.chapter(entry, chapter)?.pageUrls)
    }

    @Test fun comickDomImagesFallBackWhenEmbeddedJsonIsMalformed() = runBlocking {
        val provider = ComickReadingRuntimeProvider { url ->
            Jsoup.parse(
                """<script id="sv-data" type="application/json">not-json</script><div id="chapter-container"><img data-original="/original.jpg" src="data:image/gif;base64,placeholder"></div>""",
                url
            )
        }
        val entry = ReadingCatalogEntry("entry", ReadingKind.MANHWA, "Title", "https://comick.dev/comic/title", ReadingEntryOrigin.MANUAL)
        val chapter = ReadingChapterEntry("chapter", "1", sources = listOf(ReadingChapterSource("source", "comick", "https://comick.dev/comic/title/abc-chapter-1-en")))
        assertEquals(listOf("https://comick.dev/original.jpg"), provider.chapter(entry, chapter)?.pageUrls)
    }
}
