package streamix.readingcatalog

import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class NovelSiteReadingRuntimeProviderTest {
    private fun entry() = ReadingCatalogEntry("n", ReadingKind.NOVEL, "Novel", "https://example.invalid/novel", ReadingEntryOrigin.MANUAL)
    private fun chapter(id: String, url: String) = ReadingChapterEntry("c", "1", sources = listOf(ReadingChapterSource("s", id, url)))

    @Test fun webNovelExtractsChapterContentOnly() = runBlocking {
        val provider = WebNovelReadingRuntimeProvider { url -> Jsoup.parse("""<div class="chapter_content">A WebNovel chapter</div><footer>not content</footer>""", url) }
        assertEquals("A WebNovel chapter", provider.chapter(entry(), chapter("webnovel", "https://www.webnovel.com/book/title/chapter-1"))?.text)
        assertNull(provider.chapter(entry(), chapter("webnovel", "https://evil.invalid/book/title/chapter-1")))
        assertNull(provider.chapter(entry(), chapter("webnovel", "https://user@www.webnovel.com/book/title/chapter-1")))
        assertNull(provider.chapter(entry(), chapter("webnovel", "https://www.webnovel.com:8443/book/title/chapter-1")))
    }

    @Test fun wuxiaWorldExtractsItsChapterArticle() = runBlocking {
        val provider = WuxiaWorldReadingRuntimeProvider { url -> Jsoup.parse("""<div id="chapter-content">A WuxiaWorld chapter</div>""", url) }
        assertEquals("A WuxiaWorld chapter", provider.chapter(entry(), chapter("wuxiaworld", "https://www.wuxiaworld.com/novel/title/chapter-1"))?.text)
    }

    @Test fun bacaLightNovelExtractsPostBody() = runBlocking {
        val provider = BacaLightNovelReadingRuntimeProvider { url -> Jsoup.parse("""<article class="entry-content">Teks novel Indonesia</article>""", url) }
        assertEquals("Teks novel Indonesia", provider.chapter(entry(), chapter("baca-light-novel", "https://bacalightnovel.co/chapter/test/"))?.text)
    }

    @Test fun novelCoolExtractsItsTextContainer() = runBlocking {
        val provider = NovelCoolReadingRuntimeProvider { url -> Jsoup.parse("""<div id="content">NovelCool chapter text</div>""", url) }
        assertEquals("NovelCool chapter text", provider.chapter(entry(), chapter("novelcool", "https://www.novelcool.com/chapter/test.html"))?.text)
    }

    @Test fun webNovelDoesNotReturnPageChromeWhenChapterContainerIsMissing() = runBlocking {
        val provider = WebNovelReadingRuntimeProvider { url -> Jsoup.parse("""<header>Site title</header><footer>Navigation and ads</footer>""", url) }
        assertNull(provider.chapter(entry(), chapter("webnovel", "https://www.webnovel.com/book/title/chapter-1")))
    }

    @Test fun novelProvidersRejectDisabledSources() = runBlocking {
        val source = chapter("novelcool", "https://www.novelcool.com/chapter/test.html").sources.single().copy(enabled = false)
        var fetchCalled = false
        val provider = NovelCoolReadingRuntimeProvider { url ->
            fetchCalled = true
            Jsoup.parse("""<div id="content">Should not load</div>""", url)
        }
        assertNull(provider.chapter(entry(), ReadingChapterEntry("c", "1", sources = listOf(source))))
        kotlin.test.assertFalse(fetchCalled)
    }

    @Test fun novelProvidersRejectNonNovelEntries() = runBlocking {
        val provider = NovelCoolReadingRuntimeProvider { url -> Jsoup.parse("""<div id="content">Not a novel</div>""", url) }
        assertNull(provider.chapter(entry().copy(kind = ReadingKind.MANGA), chapter("novelcool", "https://www.novelcool.com/chapter/test.html")))
    }

    @Test fun novelAdaptersRejectForeignHostsBeforeFetching() = runBlocking {
        var fetchCount = 0
        val fetcher: (String) -> org.jsoup.nodes.Document? = { url ->
            fetchCount++
            Jsoup.parse("""<div class="chapter_content">must not load</div><div id="chapter-content">must not load</div><article class="entry-content">must not load</article><div id="content">must not load</div>""", url)
        }
        val foreignCases = listOf(
            Triple(WebNovelReadingRuntimeProvider(fetcher), "webnovel", "https://webnovel.com.evil.example/book/title/chapter-1"),
            Triple(WuxiaWorldReadingRuntimeProvider(fetcher), "wuxiaworld", "https://www.wuxiaworld.com.evil.example/novel/title/chapter-1"),
            Triple(BacaLightNovelReadingRuntimeProvider(fetcher), "baca-light-novel", "https://bacalightnovel.co.evil.example/chapter/test/"),
            Triple(NovelCoolReadingRuntimeProvider(fetcher), "novelcool", "https://www.novelcool.com.evil.example/chapter/test.html")
        )
        foreignCases.forEach { (provider, id, url) ->
            assertNull(provider.chapter(entry(), chapter(id, url)), "provider $id accepted a foreign host")
        }
        assertEquals(0, fetchCount, "foreign hosts must be rejected before document fetching")
    }

    @Test fun novelAdaptersRejectEmptyChapterContainers() = runBlocking {
        val emptyPage = { url: String -> Jsoup.parse("""<header>Site chrome</header><footer>Ads and navigation</footer>""", url) }
        assertNull(WebNovelReadingRuntimeProvider(emptyPage).chapter(entry(), chapter("webnovel", "https://www.webnovel.com/book/title/chapter-1")))
        assertNull(WuxiaWorldReadingRuntimeProvider(emptyPage).chapter(entry(), chapter("wuxiaworld", "https://www.wuxiaworld.com/novel/title/chapter-1")))
        assertNull(BacaLightNovelReadingRuntimeProvider(emptyPage).chapter(entry(), chapter("baca-light-novel", "https://bacalightnovel.co/chapter/test/")))
        assertNull(NovelCoolReadingRuntimeProvider(emptyPage).chapter(entry(), chapter("novelcool", "https://www.novelcool.com/chapter/test.html")))
    }

    @Test fun bacaLightNovelUsesTadamiEpcontentReader() = runBlocking {
        val provider = BacaLightNovelReadingRuntimeProvider { url ->
            Jsoup.parse("""<div class="epcontent"><p>Bab dari reader Tadami</p></div><nav class="bottomnav">Next chapter</nav>""", url)
        }
        assertEquals("Bab dari reader Tadami", provider.chapter(entry(), chapter("baca-light-novel", "https://bacalightnovel.co/novel/chapter-1/"))?.text)
    }

}
