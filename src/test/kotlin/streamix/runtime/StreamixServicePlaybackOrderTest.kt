package streamix.runtime

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import streamix.api.AniListGateway
import streamix.api.AniListProxy
import streamix.api.ProviderAnime
import streamix.api.StreamRef
import streamix.catalog.CatalogService
import streamix.catalog.EpisodeSource
import streamix.catalog.FileCatalogRepository
import streamix.catalog.SourceType
import streamix.core.StreamixExtractorRegistry

class StreamixServicePlaybackOrderTest {
    @Test
    fun playableCatalogWinsWithoutCallingAniListAnimeinOrWebsiteFallback() = runBlocking {
        val temp = Files.createTempDirectory("shinigami-playback-catalog-first")
        try {
            val catalog = catalog(temp)
            catalog.upsertSource(
                EpisodeSource(
                    id = "",
                    anilistId = 21L,
                    episodeNumber = 1,
                    sourceUrl = "https://example.com/catalog-720.m3u8",
                    type = SourceType.DIRECT,
                    quality = 720,
                    language = "id",
                    subtitleLanguage = "en",
                    headers = mapOf("X-Catalog-Test" to "preserved"),
                    referer = "https://catalog.example/watch"
                )
            )
            val calls = mutableListOf<String>()
            val service = StreamixService(
                aniList = fakeAniList { calls += "anilist" },
                catalog = catalog,
                animeinApiProvider = AnimeinStreamProvider { _, _ ->
                    calls += "animein"
                    listOf(StreamRef("animein-api", "https://animein.example/should-not-win.m3u8"))
                },
                runtimeProviderFallback = RuntimeWebsiteFallback { _, _ ->
                    calls += "website"
                    listOf(StreamRef("runtime-provider:test", "https://website.example/should-not-win.m3u8"))
                }
            )

            val streams = service.streams(21L, 1)

            assertEquals(true, streams.single().providerId.startsWith("catalog:"))
            assertEquals("https://example.com/catalog-720.m3u8", streams.single().url)
            assertEquals(720, streams.single().quality)
            assertEquals("id", streams.single().language)
            assertEquals("en", streams.single().subtitleLanguage)
            assertEquals(mapOf("X-Catalog-Test" to "preserved"), streams.single().headers)
            assertEquals("https://catalog.example/watch", streams.single().referer)
            assertEquals(emptyList(), calls)
        } finally {
            deleteTree(temp)
        }
    }

    @Test
    fun animeinWinsBeforeWebsiteFallbackWhenCatalogIsEmpty() = runBlocking {
        val temp = Files.createTempDirectory("shinigami-playback-animein-first")
        try {
            val calls = mutableListOf<String>()
            val service = StreamixService(
                aniList = fakeAniList { calls += "anilist" },
                catalog = catalog(temp),
                animeinApiProvider = AnimeinStreamProvider { title, episode ->
                    assertEquals("One Piece", title)
                    assertEquals(1, episode)
                    calls += "animein"
                    listOf(StreamRef("animein-api", "https://cdn.animein.example/episode-1.m3u8", 1080, "m3u8"))
                },
                runtimeProviderFallback = RuntimeWebsiteFallback { _, _ ->
                    calls += "website"
                    listOf(StreamRef("runtime-provider:test", "https://website.example/episode-1.m3u8"))
                }
            )

            val streams = service.streams(21L, 1)

            assertEquals(listOf("anilist", "animein"), calls)
            assertEquals("animein-api", streams.single().providerId)
            assertEquals(1080, streams.single().quality)
        } finally {
            deleteTree(temp)
        }
    }

    @Test
    fun prefixedCatalogProviderIdIsNormalizedBeforeRetryingCatalog() = runBlocking {
        val temp = Files.createTempDirectory("shinigami-playback-failed-catalog-source")
        try {
            val catalog = catalog(temp)
            val brokenSource = catalog.upsertSource(
                EpisodeSource(
                    id = "",
                    anilistId = 21L,
                    episodeNumber = 1,
                    sourceUrl = "https://example.com/broken.m3u8",
                    type = SourceType.DIRECT,
                    quality = 720
                )
            )
            val calls = mutableListOf<String>()
            val service = StreamixService(
                aniList = fakeAniList { calls += "anilist" },
                catalog = catalog,
                animeinApiProvider = AnimeinStreamProvider { _, _ ->
                    calls += "animein"
                    listOf(StreamRef("animein-api", "https://cdn.animein.example/episode-1.m3u8"))
                },
                runtimeProviderFallback = RuntimeWebsiteFallback { _, _ ->
                    calls += "website"
                    emptyList()
                }
            )

            val streams = service.streams(21L, 1, setOf("catalog:${brokenSource.id}"))

            assertEquals(listOf("anilist", "animein"), calls)
            assertEquals("animein-api", streams.single().providerId)
        } finally {
            deleteTree(temp)
        }
    }

    @Test
    fun failedAnimeinProviderIsSkippedBeforeWebsiteFallback() = runBlocking {
        val temp = Files.createTempDirectory("shinigami-playback-failed-animein")
        try {
            val calls = mutableListOf<String>()
            val service = StreamixService(
                aniList = fakeAniList { calls += "anilist" },
                catalog = catalog(temp),
                animeinApiProvider = AnimeinStreamProvider { _, _ ->
                    calls += "animein"
                    listOf(StreamRef("animein-api", "https://cdn.animein.example/failed.m3u8"))
                },
                runtimeProviderFallback = RuntimeWebsiteFallback { _, _ ->
                    calls += "website"
                    listOf(StreamRef("runtime-provider:test", "https://website.example/recovered.m3u8"))
                }
            )

            val streams = service.streams(21L, 1, setOf("animein-api"))

            assertEquals(listOf("anilist", "website"), calls)
            assertEquals("runtime-provider:test", streams.single().providerId)
        } finally {
            deleteTree(temp)
        }
    }

    @Test
    fun websiteFallbackRunsOnlyAfterAnimeinReturnsNoPlayableStreams() = runBlocking {
        val temp = Files.createTempDirectory("shinigami-playback-website-fallback")
        try {
            val calls = mutableListOf<String>()
            val service = StreamixService(
                aniList = fakeAniList { calls += "anilist" },
                catalog = catalog(temp),
                animeinApiProvider = AnimeinStreamProvider { _, _ ->
                    calls += "animein"
                    emptyList()
                },
                runtimeProviderFallback = RuntimeWebsiteFallback { title, episode ->
                    assertEquals("One Piece", title)
                    assertEquals(1, episode)
                    calls += "website"
                    listOf(StreamRef("runtime-provider:test", "https://website.example/episode-1.m3u8", 720, "m3u8"))
                }
            )

            val streams = service.streams(21L, 1)

            assertEquals(listOf("anilist", "animein", "website"), calls)
            assertEquals("runtime-provider:test", streams.single().providerId)
        } finally {
            deleteTree(temp)
        }
    }

    @Test
    fun animeinExceptionFallsThroughToWebsiteFallback() = runBlocking {
        val temp = Files.createTempDirectory("shinigami-playback-animein-error")
        try {
            val calls = mutableListOf<String>()
            val service = StreamixService(
                aniList = fakeAniList { calls += "anilist" },
                catalog = catalog(temp),
                animeinApiProvider = AnimeinStreamProvider { _, _ ->
                    calls += "animein"
                    error("simulated Animein outage")
                },
                runtimeProviderFallback = RuntimeWebsiteFallback { _, _ ->
                    calls += "website"
                    listOf(StreamRef("runtime-provider:test", "https://website.example/recovered.m3u8", 720, "m3u8"))
                }
            )

            val streams = service.streams(21L, 1)

            assertEquals(listOf("anilist", "animein", "website"), calls)
            assertEquals("runtime-provider:test", streams.single().providerId)
            assertEquals("https://website.example/recovered.m3u8", streams.single().url)
        } finally {
            deleteTree(temp)
        }
    }

    private fun catalog(temp: java.nio.file.Path): CatalogService =
        CatalogService(
            repository = FileCatalogRepository(temp.resolve("catalog/episodes.json")),
            extractors = StreamixExtractorRegistry(emptyList())
        )

    private fun fakeAniList(onDetail: () -> Unit): AniListGateway = object : AniListGateway {
        override fun search(query: String, page: Int): List<ProviderAnime> = emptyList()

        override fun detail(anilistId: Long): ProviderAnime? {
            onDetail()
            return if (anilistId == 21L) ProviderAnime(
                providerId = "anilist",
                id = "21",
                title = "One Piece",
                url = "https://anilist.co/anime/21"
            ) else null
        }

        override fun execute(body: ByteArray, authorization: String?): AniListProxy.Response =
            AniListProxy.Response(200, """{"data":{"ok":true}}""".toByteArray(), "application/json")
    }

    private fun deleteTree(path: java.nio.file.Path) {
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
