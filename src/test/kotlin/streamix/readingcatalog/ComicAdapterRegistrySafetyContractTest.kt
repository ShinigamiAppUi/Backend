package streamix.readingcatalog

import kotlinx.coroutines.runBlocking
import streamix.api.createSiteReadingProviders
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ComicAdapterRegistrySafetyContractTest {
    private val novelOnlyProviderIds = setOf(
        "indowebnovel", "mtl-novel-id", "meionovel", "sakuranovel",
        "sekte-novel", "vanovel", "wbnove", "webnovel", "wuxiaworld",
        "baca-light-novel", "novelcool", "novelbookid", "j-novel-club"
    )

    @Test
    fun runtimeFiltersInsecureCatalogChapterUrlsBeforeEveryRegisteredComicAdapterAndKind() = runBlocking {
        val comicProviders = createSiteReadingProviders().filterNot {
            it.id.lowercase(java.util.Locale.ROOT) in novelOnlyProviderIds
        }
        val comicKinds = listOf(ReadingKind.MANGA, ReadingKind.MANHWA, ReadingKind.MANHUA)

        for (provider in comicProviders) {
            for (kind in comicKinds) {
                val entry = ReadingCatalogEntry(
                    id = "contract-entry",
                    kind = kind,
                    title = "Contract fixture",
                    url = "https://catalog.example/title",
                    origin = ReadingEntryOrigin.MANUAL
                )
                val unsafeUrls = listOf(
                    "http://catalog.example/chapter/1",
                    "https://user@catalog.example/chapter/1",
                    "https://catalog.example:8443/chapter/1"
                )
                for (url in unsafeUrls) {
                    var unsafeSourceReachedAdapter = false
                    val observingProvider = object : ReadingRuntimeProvider {
                        override val id: String = provider.id

                        override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): Boolean {
                            unsafeSourceReachedAdapter = chapter.sources.any { it.url == url }
                            return provider.supports(entry, chapter)
                        }

                        override suspend fun chapter(
                            entry: ReadingCatalogEntry,
                            chapter: ReadingChapterEntry
                        ): ReadingContent? = provider.chapter(entry, chapter)
                    }
                    val chapter = ReadingChapterEntry(
                        id = "contract-chapter",
                        number = "1",
                        sources = listOf(
                            ReadingChapterSource(
                                id = "unsafe-source",
                                providerId = provider.id,
                                url = url
                            )
                        )
                    )

                    assertNull(
                        ReadingProviderRuntimeRace(listOf(observingProvider)).race(entry, chapter, emptySet()),
                        "Runtime must not produce content from unsafe chapter URL '$url' for '${provider.id}' / $kind"
                    )
                    assertFalse(
                        unsafeSourceReachedAdapter,
                        "Runtime must remove unsafe chapter URL '$url' before adapter '${provider.id}' sees it for $kind"
                    )
                }
            }
        }
    }
}
