package streamix.readingcatalog

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComicAdapterNonstandardPortContractTest {
    @Test
    fun siteSpecificAdaptersRejectNonstandardPortsWithoutRejectingTheirCanonicalUrl() {
        data class Case(
            val provider: ReadingRuntimeProvider,
            val providerId: String,
            val kind: ReadingKind,
            val validUrl: String,
            val nonstandardPortUrl: String
        )

        val cases = listOf(
            Case(
                VoraToonReadingRuntimeProvider { "{}" },
                "voratoon",
                ReadingKind.MANHWA,
                "https://v1.voratoon.com/series/title/chapter/123",
                "https://v1.voratoon.com:8443/series/title/chapter/123"
            ),
            Case(
                ComicasoReadingRuntimeProvider { "{}" },
                "comicaso",
                ReadingKind.MANHWA,
                "https://v3.comicaso.pro/source/manga/chapter?token=x",
                "https://v3.comicaso.pro:8443/source/manga/chapter?token=x"
            ),
            Case(
                AsuraScansReadingRuntimeProvider { org.jsoup.Jsoup.parse("<script>{\"pages\":[]}</script>") },
                "asura-scans",
                ReadingKind.MANHWA,
                "https://asuracomic.net/chapter/title-chapter-1",
                "https://asuracomic.net:8443/chapter/title-chapter-1"
            ),
            Case(
                MangaKReadingRuntimeProvider { org.jsoup.Jsoup.parse("<div></div>") },
                "mangak",
                ReadingKind.MANGA,
                "https://mangak.io/chapter/1",
                "https://mangak.io:8443/chapter/1"
            ),
            Case(
                MangaParkTvReadingRuntimeProvider { org.jsoup.Jsoup.parse("<div></div>") },
                "mangapark-tv",
                ReadingKind.MANHWA,
                "https://mangapark.tv/title/chapter-1",
                "https://mangapark.tv:8443/title/chapter-1"
            ),
            Case(
                MangaTaroReadingRuntimeProvider { "{}" },
                "mangataro",
                ReadingKind.MANGA,
                "https://mangataro.org/title-chapter-1",
                "https://mangataro.org:8443/title-chapter-1"
            )
        )

        for (case in cases) {
            fun chapter(url: String) = ReadingChapterEntry(
                "c",
                "1",
                sources = listOf(ReadingChapterSource("s", case.providerId, url))
            )
            val entry = ReadingCatalogEntry(
                "m",
                case.kind,
                "Fixture",
                "https://catalog.example/title",
                ReadingEntryOrigin.MANUAL
            )

            assertTrue(
                case.provider.supports(entry, chapter(case.validUrl)),
                "${case.providerId} rejected its canonical HTTPS fixture; port assertion would be inconclusive"
            )
            assertFalse(
                case.provider.supports(entry, chapter(case.nonstandardPortUrl)),
                "${case.providerId} accepted a nonstandard HTTPS port"
            )
        }
    }
}
