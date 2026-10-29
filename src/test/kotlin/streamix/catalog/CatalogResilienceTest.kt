package streamix.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import streamix.core.ProviderStream
import streamix.core.StreamixExtractor
import streamix.core.StreamixExtractorRegistry
import streamix.core.StreamixExtractorRequest
import streamix.core.StreamixExtractorResult
import java.nio.file.Files

class CatalogResilienceTest {
    @Test
    fun returnsFirstDirectMirrorAndLeavesNextMirrorForFailover() = runBlocking {
        val file = Files.createTempFile("catalog", ".json")
        val repository = FileCatalogRepository(file)
        val service = CatalogService(repository, StreamixExtractorRegistry())

        service.upsertSource(EpisodeSource("otaku", 21, 1, "https://example.com/otaku.m3u8", type = SourceType.DIRECT, priority = 100))
        service.upsertSource(EpisodeSource("same", 21, 1, "https://example.com/same.m3u8", type = SourceType.DIRECT, priority = 90))
        service.upsertSource(EpisodeSource("kuro", 21, 1, "https://example.com/kuro.m3u8", type = SourceType.DIRECT, priority = 80))

        val streams = service.streams(21, 1)
        assertEquals(
            listOf("https://example.com/otaku.m3u8"),
            streams.map { it.url }
        )
    }

    @Test
    fun triesNextMatchingExtractorWhenFirstExtractorReturnsNothing() = runBlocking {
        val file = Files.createTempFile("catalog", ".json")
        val repository = FileCatalogRepository(file)
        val first = FakeExtractor("first", setOf("example.com"), emptyList())
        val second = FakeExtractor(
            "second",
            setOf("example.com"),
            listOf(ProviderStream("second", "https://example.com/live.m3u8", 720, "m3u8"))
        )
        val service = CatalogService(repository, StreamixExtractorRegistry(listOf(first, second)))

        service.upsertSource(EpisodeSource("page", 21, 1, "https://example.com/watch/1", type = SourceType.EXTRACTOR))
        val streams = service.streams(21, 1)

        assertEquals(listOf("https://example.com/live.m3u8"), streams.map { it.url })
        assertEquals(SourceHealth.UNKNOWN, repository.getEpisode(21, 1)!!.sources.single().health)
    }

    @Test
    fun rejectsPrivateLocalExtractorMediaTargets() = runBlocking {
        val file = Files.createTempFile("catalog-unsafe-extractor", ".json")
        val repository = FileCatalogRepository(file)
        val extractor = FakeExtractor(
            "unsafe",
            setOf("example.com"),
            listOf(ProviderStream("unsafe", "http://127.0.0.1:8080/internal/video.m3u8", 1080, "m3u8"))
        )
        val service = CatalogService(repository, StreamixExtractorRegistry(listOf(extractor)))

        service.upsertSource(
            EpisodeSource("page", 22, 1, "https://example.com/watch/1", type = SourceType.EXTRACTOR)
        )

        assertEquals(emptyList(), service.streams(22, 1))
    }
    @Test
    fun healthCanBeCheckedAndManuallyDisabled() = runBlocking {
        val file = Files.createTempFile("catalog", ".json")
        val repository = FileCatalogRepository(file)
        val service = CatalogService(repository, StreamixExtractorRegistry())

        val saved = service.upsertSource(EpisodeSource("direct", 21, 1, "https://example.com/video.m3u8", type = SourceType.DIRECT))
        val persisted = repository.updateSourceHealth(21, 1, saved.id, SourceHealth.EXPIRED, 1234L, 403, "HTTP 403")
        assertNotNull(persisted)
        assertEquals(SourceHealth.EXPIRED, persisted.health)
        assertEquals(403, persisted.lastHttpStatus)
        assertEquals("HTTP 403", persisted.lastFailureReason)

        val disabled = service.setSourceEnabled(21, 1, saved.id, false)
        assertEquals(false, disabled?.enabled)
    }

    private class FakeExtractor(
        override val id: String,
        override val domains: Set<String>,
        private val streams: List<ProviderStream>
    ) : StreamixExtractor {
        override suspend fun extract(request: StreamixExtractorRequest): StreamixExtractorResult =
            StreamixExtractorResult(streams = streams)
    }
}
