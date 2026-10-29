package streamix.readingcatalog

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ReadingProviderRuntimeRaceAliasTest {
    @Test
    fun canonicalProviderAdapterReceivesNormalizedAliasDuringRuntimeRace() = runBlocking {
        var observedProviderId: String? = null
        val provider = object : ReadingRuntimeProvider {
            override val id: String = "mangadex"
            override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): Boolean {
                observedProviderId = chapter.sources.singleOrNull()?.providerId
                return observedProviderId == "mangadex"
            }
            override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? =
                ReadingContent(kind = entry.kind, sourceId = "mangadex", pageUrls = listOf("https://uploads.mangadex.org/data/hash/1.jpg"))
        }
        val entry = ReadingCatalogEntry(
            id = "title",
            kind = ReadingKind.MANHWA,
            title = "Title",
            url = "https://catalog.example/title",
            origin = ReadingEntryOrigin.MANUAL
        )
        val chapter = ReadingChapterEntry(
            id = "chapter-1",
            number = "1",
            sources = listOf(
                ReadingChapterSource(
                    id = "source-1",
                    providerId = "mangadex-manhwa",
                    url = "https://mangadex.org/chapter/12345678-1234-1234-1234-123456789abc"
                )
            )
        )

        val result = ReadingProviderRuntimeRace(listOf(provider)).race(entry, chapter, emptySet())

        assertEquals("mangadex", observedProviderId)
        assertNotNull(result)
    }

    @Test
    fun failedCanonicalProviderAlsoFiltersLegacyAliasSource() = runBlocking {
        var observedSourceCount = -1
        val provider = object : ReadingRuntimeProvider {
            override val id: String = "mangadex"
            override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): Boolean {
                observedSourceCount = chapter.sources.size
                return chapter.sources.isNotEmpty()
            }
            override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? =
                ReadingContent(kind = entry.kind, sourceId = "mangadex", pageUrls = listOf("https://uploads.mangadex.org/data/hash/1.jpg"))
        }
        val entry = ReadingCatalogEntry(
            id = "title",
            kind = ReadingKind.MANHWA,
            title = "Title",
            url = "https://catalog.example/title",
            origin = ReadingEntryOrigin.MANUAL
        )
        val chapter = ReadingChapterEntry(
            id = "chapter-1",
            number = "1",
            sources = listOf(ReadingChapterSource("source-1", "mangadex-manhwa", "https://mangadex.org/chapter/12345678-1234-1234-1234-123456789abc"))
        )

        val result = ReadingProviderRuntimeRace(listOf(provider)).race(entry, chapter, setOf("mangadex"))

        assertEquals(-1, observedSourceCount)
        assertEquals(null, result)
    }
}
