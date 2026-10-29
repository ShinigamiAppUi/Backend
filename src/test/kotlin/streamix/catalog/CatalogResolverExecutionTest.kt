package streamix.catalog

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import streamix.api.StreamRef
import streamix.core.StreamixExtractorRegistry

class CatalogResolverExecutionTest {
    @Test
    fun resolverCandidatesFallThroughAndPreserveCatalogMetadata() = runBlocking {
        val first = object : CatalogResolver {
            override val id = "first"
            override val domains = setOf("example.com")
            override suspend fun resolve(source: EpisodeSource) =
                CatalogResolverResult(failureReason = "first failed")
        }
        val second = object : CatalogResolver {
            override val id = "second"
            override val domains = setOf("example.com")
            override suspend fun resolve(source: EpisodeSource) =
                CatalogResolverResult(
                    streams = listOf(
                        StreamRef(
                            providerId = "resolver-native",
                            url = "https://example.com/video.m3u8",
                            quality = null,
                            type = "m3u8",
                            language = null,
                            subtitleLanguage = null,
                            subtitles = listOf(
                                streamix.api.SubtitleRef(
                                    url = "http://127.0.0.1:8080/internal/subtitle.vtt",
                                    language = "id"
                                )
                            )
                        )
                    )
                )
        }

        val service = CatalogService(
            repository = FileCatalogRepository(createTempDirectory("resolver-exec").resolve("episodes.json")),
            extractors = StreamixExtractorRegistry(),
            resolvers = CatalogResolverRegistry(listOf(first, second))
        )
        val source = service.upsertSource(
            EpisodeSource(
                id = "resolver-source",
                anilistId = 55,
                episodeNumber = 1,
                sourceUrl = "https://example.com/container/1",
                type = SourceType.RESOLVER,
                quality = 720,
                language = "id",
                subtitleLanguage = "id",
                headers = mapOf("X-Test" to "1"),
                referer = "https://example.com/watch/"
            )
        )

        val streams = service.streams(55, 1)

        assertEquals(1, streams.size)
        assertEquals("catalog:" + source.id, streams.single().providerId)
        assertEquals(720, streams.single().quality)
        assertEquals("id", streams.single().language)
        assertEquals("id", streams.single().subtitleLanguage)
        assertEquals(mapOf("X-Test" to "1"), streams.single().headers)
        assertEquals("https://example.com/watch/", streams.single().referer)
        assertEquals(emptyList(), streams.single().subtitles)
    }

    @Test
    fun resolverRejectsPrivateLocalMediaTargets() = runBlocking {
        val resolver = object : CatalogResolver {
            override val id = "unsafe-output"
            override val domains = setOf("example.com")
            override suspend fun resolve(source: EpisodeSource) =
                CatalogResolverResult(
                    streams = listOf(
                        StreamRef(
                            providerId = "unsafe-output",
                            url = "http://127.0.0.1:8080/internal/video.m3u8",
                            quality = 1080,
                            type = "m3u8",
                            language = null,
                            subtitleLanguage = null
                        )
                    )
                )
        }
        val service = CatalogService(
            repository = FileCatalogRepository(createTempDirectory("resolver-unsafe-output").resolve("episodes.json")),
            extractors = StreamixExtractorRegistry(),
            resolvers = CatalogResolverRegistry(listOf(resolver))
        )
        service.upsertSource(
            EpisodeSource(
                id = "unsafe-output-source",
                anilistId = 58,
                episodeNumber = 1,
                sourceUrl = "https://example.com/container/1",
                type = SourceType.RESOLVER
            )
        )

        assertEquals(emptyList(), service.streams(58, 1))
    }

    @Test
    fun resolverRejectsNat64EmbeddedPrivateMediaTargets() = runBlocking {
        val resolver = object : CatalogResolver {
            override val id = "nat64-output"
            override val domains = setOf("example.com")
            override suspend fun resolve(source: EpisodeSource) =
                CatalogResolverResult(
                    streams = listOf(
                        StreamRef(
                            providerId = "nat64-output",
                            url = "http://[64:ff9b::a00:1]/internal/video.m3u8",
                            quality = 1080,
                            type = "m3u8",
                            language = null,
                            subtitleLanguage = null
                        )
                    )
                )
        }
        val service = CatalogService(
            repository = FileCatalogRepository(createTempDirectory("resolver-nat64-output").resolve("episodes.json")),
            extractors = StreamixExtractorRegistry(),
            resolvers = CatalogResolverRegistry(listOf(resolver))
        )
        service.upsertSource(
            EpisodeSource(
                id = "nat64-output-source",
                anilistId = 59,
                episodeNumber = 1,
                sourceUrl = "https://example.com/container/1",
                type = SourceType.RESOLVER
            )
        )

        assertEquals(emptyList(), service.streams(59, 1))
    }

    @Test
    fun resolverWithoutMatchingImplementationFailsClosed() = runBlocking {
        val service = CatalogService(
            repository = FileCatalogRepository(createTempDirectory("resolver-empty").resolve("episodes.json")),
            extractors = StreamixExtractorRegistry(),
            resolvers = CatalogResolverRegistry()
        )
        service.upsertSource(
            EpisodeSource(
                id = "resolver-source",
                anilistId = 56,
                episodeNumber = 1,
                sourceUrl = "https://krakenfiles.com/container/1",
                type = SourceType.RESOLVER
            )
        )

        assertEquals(emptyList(), service.streams(56, 1))
    }

    @Test
    fun resolverCancellationIsNotSwallowed() = runBlocking {
        val resolver = object : CatalogResolver {
            override val id = "cancel"
            override val domains = setOf("cancel.example")
            override suspend fun resolve(source: EpisodeSource): CatalogResolverResult {
                throw kotlinx.coroutines.CancellationException("cancelled")
            }
        }
        val service = CatalogService(
            repository = FileCatalogRepository(createTempDirectory("resolver-cancel").resolve("episodes.json")),
            extractors = StreamixExtractorRegistry(),
            resolvers = CatalogResolverRegistry(listOf(resolver))
        )
        service.upsertSource(
            EpisodeSource(
                id = "resolver-source",
                anilistId = 57,
                episodeNumber = 1,
                sourceUrl = "https://cancel.example/container/1",
                type = SourceType.RESOLVER
            )
        )

        kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> {
            service.streams(57, 1)
        }
    }
}
