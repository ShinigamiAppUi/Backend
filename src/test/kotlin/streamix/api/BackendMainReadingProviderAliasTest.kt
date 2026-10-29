package streamix.api

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import streamix.readingcatalog.MangaDexReadingRuntimeProvider
import streamix.readingcatalog.ReadingCatalogEntry
import streamix.readingcatalog.ReadingChapterEntry
import streamix.readingcatalog.ReadingChapterSource
import streamix.readingcatalog.ReadingEntryOrigin
import streamix.readingcatalog.ReadingKind

class BackendMainReadingProviderAliasTest {
    @Test
    fun legacyProviderAliasesResolveToRegisteredAdapters() {
        assertEquals("mangadex", canonicalReadingProviderId("mangadex-manhwa"))
        assertEquals("mangadex", canonicalReadingProviderId("mangadex-manhua"))
        assertEquals("weebcentral", canonicalReadingProviderId("weebcentral-manhwa"))
        assertEquals("comick", canonicalReadingProviderId("comick-unoriginal"))
        assertEquals("bato", canonicalReadingProviderId("bbato"))
        assertEquals("my-provider", canonicalReadingProviderId("my-provider"))
    }

    @Test
    fun mangaDexLegacyKindAliasReachesAdapterWithNormalizedSourceId() = runBlocking {
        val chapterId = "12345678-1234-1234-1234-123456789abc"
        val requests = mutableListOf<String>()
        val provider = MangaDexReadingRuntimeProvider(jsonFetcher = { url ->
            requests += url
            JsonParser.parseString(
                """{"baseUrl":"https://uploads.mangadex.org","chapter":{"hash":"0123456789abcdef0123456789abcdef","data":["1.jpg"]}}"""
            ).asJsonObject
        })
        val entry = ReadingCatalogEntry(
            id = "m", kind = ReadingKind.MANHWA, title = "Title",
            url = "https://catalog.example/title", origin = ReadingEntryOrigin.MANUAL
        )
        val source = ReadingChapterSource(
            id = "source", providerId = "mangadex-manhwa",
            url = "https://mangadex.org/chapter/$chapterId"
        )
        val chapter = ReadingChapterEntry("c", "1", sources = listOf(source))

        // The source handler normalizes the dispatch alias in the source as well as
        // selecting the canonical provider instance.
        val normalized = normalizeReadingSourceAlias(chapter, source, "mangadex")
        val content = provider.chapter(entry, normalized)

        assertNotNull(content)
        assertEquals(listOf("https://api.mangadex.org/at-home/server/$chapterId"), requests)
        assertEquals("mangadex", normalized.sources.single().providerId)
    }

    @Test
    fun normalizingOneAliasLeavesOtherChapterSourcesUntouched() {
        val selected = ReadingChapterSource("selected", "weebcentral-manhwa", "https://weebcentral.com/chapter/1")
        val backup = ReadingChapterSource("backup", "other", "https://other.example/chapter/1")
        val chapter = ReadingChapterEntry("c", "1", sources = listOf(selected, backup))

        val normalized = normalizeReadingSourceAlias(chapter, selected, "weebcentral")

        assertEquals("weebcentral", normalized.sources[0].providerId)
        assertEquals(backup, normalized.sources[1])
    }
}
