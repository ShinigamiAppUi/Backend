package streamix.readingcatalog

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

class ReadingProviderRuntimeRaceContractTest {
    private fun entry(kind: ReadingKind = ReadingKind.MANGA) = ReadingCatalogEntry(
        id = "title",
        kind = kind,
        title = "Title",
        url = "https://catalog.example/title",
        origin = ReadingEntryOrigin.MANUAL
    )

    private fun chapter(vararg providers: String) = ReadingChapterEntry(
        id = "chapter-1",
        number = "1",
        sources = providers.mapIndexed { index, provider ->
            ReadingChapterSource(
                id = "source-$index",
                providerId = provider,
                url = "https://$provider.example/chapter/1"
            )
        }
    )

    @Test
    fun registryRejectsBlankAndDuplicateProviderIdsInsteadOfSilentlyDroppingAdapters() {
        val blank = object : ReadingRuntimeProvider {
            override val id = "  "
            override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = null
        }
        assertFailsWith<IllegalArgumentException> { ReadingProviderRuntimeRace(listOf(blank)) }

        val first = object : ReadingRuntimeProvider {
            override val id = "Example"
            override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = null
        }
        val duplicate = object : ReadingRuntimeProvider {
            override val id = " example "
            override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? = null
        }
        assertFailsWith<IllegalArgumentException> { ReadingProviderRuntimeRace(listOf(first, duplicate)) }
    }
    @Test
    fun sharedImageUrlGuardRejectsUnsafeSchemesCredentialsAndNonstandardPorts() {
        assertEquals(true, isSafeReadingImageUrl("https://cdn.example/page.jpg"))
        assertEquals(true, isSafeReadingImageUrl("https://cdn.example:443/page.jpg"))
        assertEquals(false, isSafeReadingImageUrl("http://cdn.example/page.jpg"))
        assertEquals(false, isSafeReadingImageUrl("https://user@cdn.example/page.jpg"))
        assertEquals(false, isSafeReadingImageUrl("https://cdn.example:8443/page.jpg"))
        assertEquals(false, isSafeReadingImageUrl("https:///page.jpg"))
    }
    @Test
    fun runtimeDoesNotCallProvidersNotReferencedByChapter() = runBlocking {
        var called = 0
        val referenced = object : ReadingRuntimeProvider {
            override val id = "referenced"
            override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) =
                chapter.sources.any { it.providerId == id }
            override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? {
                called++
                return ReadingContent(entry.kind, id, pageUrls = listOf("https://cdn.example/page.jpg"))
            }
        }
        val unrelated = object : ReadingRuntimeProvider {
            override val id = "unrelated"
            override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? {
                called += 100
                return ReadingContent(entry.kind, id, pageUrls = listOf("https://cdn.example/other.jpg"))
            }
        }

        val result = ReadingProviderRuntimeRace(listOf(referenced, unrelated))
            .race(entry(), chapter("referenced"), emptySet())

        assertEquals(listOf("https://cdn.example/page.jpg"), result?.pageUrls)
        assertEquals(1, called)
    }

    @Test
    fun failedProviderIdsAreComparedCaseInsensitively() = runBlocking {
        var called = false
        val provider = object : ReadingRuntimeProvider {
            override val id = "webnovel"
            override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? {
                called = true
                return ReadingContent(entry.kind, id, text = "sample text")
            }
        }

        val result = ReadingProviderRuntimeRace(listOf(provider))
            .race(entry(ReadingKind.NOVEL), chapter("WEBNOVEL"), setOf(" WEBNOVEL "))

        assertNull(result)
        assertEquals(false, called)
    }

    @Test
    fun failedSourceIdRemovesOnlyThatSourceBeforeAdapterSelection() = runBlocking {
        var seenSources: List<String> = emptyList()
        val provider = object : ReadingRuntimeProvider {
            override val id = "mangadex"
            override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry) =
                chapter.sources.any { it.providerId == id }
            override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? {
                seenSources = chapter.sources.map { it.id }
                return ReadingContent(entry.kind, id, pageUrls = listOf("https://cdn.example/backup.jpg"))
            }
        }
        val sources = ReadingChapterEntry(
            id = "chapter-1",
            number = "1",
            sources = listOf(
                ReadingChapterSource("failed-source", "mangadex", "https://mangadex.org/chapter/failed"),
                ReadingChapterSource("backup-source", "mangadex", "https://mangadex.org/chapter/backup")
            )
        )

        val result = ReadingProviderRuntimeRace(listOf(provider))
            .race(entry(), sources, setOf("FAILED-SOURCE"))

        assertEquals(listOf("https://cdn.example/backup.jpg"), result?.pageUrls)
        assertEquals(listOf("backup-source"), seenSources)
    }

    @Test
    fun runtimeRejectsContentWhoseKindDoesNotMatchCatalogEntry() = runBlocking {
        val provider = object : ReadingRuntimeProvider {
            override val id = "mangadex"
            override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent =
                ReadingContent(ReadingKind.NOVEL, id, text = "wrong content kind")
        }

        val result = ReadingProviderRuntimeRace(listOf(provider))
            .race(entry(ReadingKind.MANGA), chapter("mangadex"), emptySet())

        assertNull(result)
    }
    
    @Test
    fun providerSupportExceptionDoesNotAbortOtherEligibleProviders() = runBlocking {
        val broken = object : ReadingRuntimeProvider {
            override val id = "broken"
            override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): Boolean =
                error("broken adapter predicate")
            override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? =
                error("should never be called")
        }
        val healthy = object : ReadingRuntimeProvider {
            override val id = "healthy"
            override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent =
                ReadingContent(entry.kind, id, pageUrls = listOf("https://cdn.example/page.jpg"))
        }

        val result = ReadingProviderRuntimeRace(listOf(broken, healthy))
            .race(entry(), chapter("broken", "healthy"), emptySet())

        assertEquals(listOf("https://cdn.example/page.jpg"), result?.pageUrls)
    }

    @Test
    fun novelRuntimeRejectsImageOnlyContent() = runBlocking {
        val provider = object : ReadingRuntimeProvider {
            override val id = "webnovel"
            override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent =
                ReadingContent(ReadingKind.NOVEL, id, pageUrls = listOf("https://cdn.example/page.jpg"))
        }

        val result = ReadingProviderRuntimeRace(listOf(provider))
            .race(entry(ReadingKind.NOVEL), chapter("webnovel"), emptySet())

        assertNull(result)
    }

    @Test
    fun sharedChapterUrlGuardRejectsCredentialsPortsAndUnapprovedHosts() {
        val hosts = setOf("mangakatana.com")
        val path: (String) -> Boolean = { it.contains("/manga/", true) }
        assertEquals(true, isSafeReadingChapterUrl("https://mangakatana.com/manga/title/chapter-1", hosts, path))
        assertEquals(false, isSafeReadingChapterUrl("http://mangakatana.com/manga/title/chapter-1", hosts, path))
        assertEquals(false, isSafeReadingChapterUrl("https://user@mangakatana.com/manga/title/chapter-1", hosts, path))
        assertEquals(false, isSafeReadingChapterUrl("https://mangakatana.com:8443/manga/title/chapter-1", hosts, path))
        assertEquals(false, isSafeReadingChapterUrl("https://mangakatana.com.attacker.example/manga/title/chapter-1", hosts, path))
        assertEquals(false, isSafeReadingChapterUrl("https://mangakatana.com/title/no-chapter", hosts, path))
    }

    @Test
    fun discoveryAwareProviderCanResolveChapterWithoutCatalogSourceUrls() = runBlocking {
        var called = false
        val discoveryProvider = object : ReadingRuntimeProvider {
            override val id = "discovery-provider"
            override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): Boolean =
                entry.url == "https://provider.example/title" && chapter.number == "12"
            override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? {
                called = true
                return ReadingContent(entry.kind, id, pageUrls = listOf("https://cdn.example/chapter-12/1.jpg"))
            }
        }

        val result = ReadingProviderRuntimeRace(listOf(discoveryProvider)).race(
            entry().copy(url = "https://provider.example/title"),
            ReadingChapterEntry(id = "chapter-12", number = "12"),
            emptySet()
        )

        assertEquals(true, called)
        assertEquals(listOf("https://cdn.example/chapter-12/1.jpg"), result?.pageUrls)
    }

    @Test
    fun defaultProviderRoutingStillRejectsChapterWithoutCatalogSourceUrls() = runBlocking {
        var called = false
        val conservativeProvider = object : ReadingRuntimeProvider {
            override val id = "conservative"
            override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? {
                called = true
                return ReadingContent(entry.kind, id, pageUrls = listOf("https://cdn.example/page.jpg"))
            }
        }

        val result = ReadingProviderRuntimeRace(listOf(conservativeProvider))
            .race(entry(), ReadingChapterEntry(id = "chapter-1", number = "1"), emptySet())

        assertNull(result)
        assertEquals(false, called)
    }



    @Test
    fun runtimeFiltersUnsafeComicChapterSourcesBeforeAnyAdapterCanConsumeThem() = runBlocking {
        var observedSources: List<String> = emptyList()
        var called = false
        val provider = object : ReadingRuntimeProvider {
            override val id = "mangadex"
            override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): Boolean {
                observedSources = chapter.sources.map { it.url }
                return chapter.sources.any { it.providerId == id }
            }
            override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? {
                called = true
                return ReadingContent(entry.kind, id, pageUrls = listOf("https://cdn.example/page.jpg"))
            }
        }
        val input = ReadingChapterEntry(
            "chapter-1",
            "1",
            sources = listOf(
                ReadingChapterSource("insecure", "mangadex", "http://mangadex.org/chapter/1"),
                ReadingChapterSource("credentialed", "mangadex", "https://user@mangadex.org/chapter/2"),
                ReadingChapterSource("port", "mangadex", "https://mangadex.org:8443/chapter/3"),
                ReadingChapterSource("valid", "mangadex", "https://mangadex.org/chapter/4")
            )
        )

        val result = ReadingProviderRuntimeRace(listOf(provider)).race(entry(), input, emptySet())

        assertEquals(listOf("https://mangadex.org/chapter/4"), observedSources)
        assertEquals(true, called)
        assertEquals(listOf("https://cdn.example/page.jpg"), result?.pageUrls)
    }

    @Test
    fun runtimeRejectsComicPageListsContainingUnsafeImageUrls() = runBlocking {
        val provider = object : ReadingRuntimeProvider {
            override val id = "unsafe-pages"
            override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent =
                ReadingContent(
                    entry.kind,
                    id,
                    pageUrls = listOf(
                        "https://cdn.example/valid.jpg",
                        "http://cdn.example/insecure.jpg"
                    )
                )
        }

        val result = ReadingProviderRuntimeRace(listOf(provider))
            .race(entry(), chapter("unsafe-pages"), emptySet())

        assertNull(result)
    }

    @Test
    fun runtimeAcceptsComicPageListsWithHttpsUrlsAndStandardPorts() = runBlocking {
        val provider = object : ReadingRuntimeProvider {
            override val id = "safe-pages"
            override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent =
                ReadingContent(
                    entry.kind,
                    id,
                    pageUrls = listOf(
                        "https://cdn.example/one.jpg",
                        "https://cdn.example:443/two.jpg"
                    )
                )
        }

        val result = ReadingProviderRuntimeRace(listOf(provider))
            .race(entry(), chapter("safe-pages"), emptySet())

        assertEquals(
            listOf("https://cdn.example/one.jpg", "https://cdn.example:443/two.jpg"),
            result?.pageUrls
        )
    }


    @Test
    fun runtimeRejectsUnsafeComicContentUrlWhenPageListIsAbsent() = runBlocking {
        val provider = object : ReadingRuntimeProvider {
            override val id = "unsafe-content-url"
            override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent =
                ReadingContent(entry.kind, id, contentUrl = "http://internal.example/reader")
        }
        val result = ReadingProviderRuntimeRace(listOf(provider))
            .race(entry(), chapter("unsafe-content-url"), emptySet())
        assertNull(result)
    }

    @Test
    fun runtimeAcceptsHttpsComicContentUrlWhenPageListIsAbsent() = runBlocking {
        val provider = object : ReadingRuntimeProvider {
            override val id = "safe-content-url"
            override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent =
                ReadingContent(entry.kind, id, contentUrl = "https://reader.example/chapter/1")
        }
        val result = ReadingProviderRuntimeRace(listOf(provider))
            .race(entry(), chapter("safe-content-url"), emptySet())
        assertEquals("https://reader.example/chapter/1", result?.contentUrl)
    }

}
