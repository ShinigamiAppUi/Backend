package streamix.readingcatalog

import com.google.gson.JsonObject
import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class SiteSpecificReadingRuntimeProviderTest {
    @Test
    fun mangaPillUsesItsPictureImageMarkupAndRejectsWrongHost() = runBlocking {
        val provider = MangaPillReadingRuntimeProvider { url ->
            Jsoup.parse("""<picture><img data-src="/chapter/1.jpg"></picture>""", url)
        }
        val entry = ReadingCatalogEntry("m", ReadingKind.MANGA, "Test", "https://mangapill.com/manga/1", ReadingEntryOrigin.MANUAL)
        val good = ReadingChapterEntry("c", "1", sources = listOf(ReadingChapterSource("s", "mangapill", "https://mangapill.com/chapters/test-1")))
        assertEquals(listOf("https://mangapill.com/chapter/1.jpg"), provider.chapter(entry, good)?.pageUrls)
        val bad = good.copy(sources = listOf(ReadingChapterSource("s", "mangapill", "https://evil.example/chapters/test-1")))
        assertNull(provider.chapter(entry, bad))
    }

    @Test
    fun mangaFireUsesItsChapterPagesApi() = runBlocking {
        var requested = ""
        val provider = MangaFireReadingRuntimeProvider { url ->
            requested = url
            """{"data":{"pages":[{"url":"https://cdn.example/page-1.webp"},{"url":"https://cdn.example/page-2.webp"}]}}"""
        }
        val entry = ReadingCatalogEntry("m", ReadingKind.MANGA, "Test", "https://mangafire.to/title/series", ReadingEntryOrigin.MANUAL)
        val chapter = ReadingChapterEntry("c", "1", sources = listOf(ReadingChapterSource("s", "mangafire", "https://mangafire.to/title/series/123-chapter-1-en")))
        val result = provider.chapter(entry, chapter)
        assertNotNull(result)
        assertEquals("https://mangafire.to/api/chapters/123?vrf=${MangaFireVrfSigner.sign("/chapters/123")}", requested)
        assertEquals(2, result.pageUrls.size)

        val unsafePagesProvider = MangaFireReadingRuntimeProvider {
            """{"data":{"pages":[{"url":"https://cdn.example:444/page.webp"},{"url":"https://user@cdn.example/page.webp"}]}}"""
        }
        assertNull(unsafePagesProvider.chapter(entry, chapter))
    }

    @Test
    fun mangaFireRejectsUnsafeChapterUrlAuthorities() = runBlocking {
        val provider = MangaFireReadingRuntimeProvider { error("unsafe chapter URL must not trigger a request") }
        val entry = ReadingCatalogEntry("m", ReadingKind.MANGA, "Test", "https://mangafire.to/title/series", ReadingEntryOrigin.MANUAL)
        for (url in listOf(
            "https://user@mangafire.to/title/series/123-chapter-1-en",
            "https://mangafire.to:444/title/series/123-chapter-1-en",
            "https://evil.example/title/series/123-chapter-1-en",
        )) {
            val chapter = ReadingChapterEntry("c", "1", sources = listOf(ReadingChapterSource("s", "mangafire", url)))
            assertNull(provider.chapter(entry, chapter))
        }
    }

    @Test
    fun asuraAdapterParsesItsEmbeddedPagesPayload() = runBlocking {
        val provider = AsuraScansReadingRuntimeProvider { url ->
            Jsoup.parse("""<script type="application/json">{"pages":[{"url":"https://cdn.example/a.jpg"},{"url":"https://cdn.example/b.jpg"}]}</script>""", url)
        }
        val entry = ReadingCatalogEntry("m", ReadingKind.MANHWA, "Test", "https://asuracomic.net/series/test", ReadingEntryOrigin.MANUAL)
        val chapter = ReadingChapterEntry("c", "1", sources = listOf(ReadingChapterSource("s", "asura-scans", "https://asuracomic.net/series/test/chapter/1")))
        assertEquals(2, provider.chapter(entry, chapter)?.pageUrls?.size)
    }
}
