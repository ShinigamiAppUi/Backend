package streamix.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import streamix.core.ProviderStream
import streamix.core.StreamixExtractor
import streamix.core.StreamixExtractorRegistry
import streamix.core.StreamixExtractorRequest
import streamix.core.StreamixExtractorResult

class NativeExtractorCatalogResolverTest {
    @Test
    fun nativeExtractorIsExposedAsCatalogResolver() = runBlocking {
        var scopedTransport: streamix.core.StreamixHttp? = null
        val extractor = object : StreamixExtractor {
            override val id = "fake-native"
            override val domains = setOf("example.com")
            override suspend fun extract(request: StreamixExtractorRequest): StreamixExtractorResult {
                scopedTransport = request.scopedHttp
                return StreamixExtractorResult(
                    streams = listOf(
                        ProviderStream(
                            providerId = "fake-native",
                            url = "https://cdn.example.com/video.m3u8",
                            quality = 1080,
                            type = "m3u8",
                            headers = mapOf("user-agent" to "ExtractorAgent", "X-Test" to "ok"),
                            referer = request.referer
                        )
                    )
                )
            }
        }

        val resolver = NativeExtractorCatalogResolver(
            StreamixExtractorRegistry(listOf(extractor)),
            "fake-native",
            "fake-native-catalog"
        )

        assertEquals(setOf("example.com"), resolver.domains)

        val result = resolver.resolve(
            EpisodeSource(
                id = "source-1",
                anilistId = 1,
                episodeNumber = 1,
                sourceUrl = "https://example.com/embed/1",
                type = SourceType.RESOLVER,
                language = "id",
                subtitleLanguage = "id",
                headers = mapOf("User-Agent" to "Shinigami"),
                referer = "https://example.com/"
            )
        )

        assertTrue(result.succeeded)
        assertNotNull(scopedTransport, "Catalog native resolver must always inject a scoped HTTP transport")
        assertEquals("https://cdn.example.com/video.m3u8", result.streams.single().url)
        assertEquals("catalog:source-1", result.streams.single().providerId)
        assertEquals("m3u8", result.streams.single().type)
        assertEquals("ExtractorAgent", result.streams.single().headers["user-agent"])
        assertEquals(1, result.streams.single().headers.keys.count { it.equals("user-agent", ignoreCase = true) })
        assertEquals("https://example.com/", result.streams.single().referer)
    }

    @Test
    fun duplicateSourceHeaderNamesAreCollapsedCaseInsensitively() = runBlocking {
        val extractor = object : StreamixExtractor {
            override val id = "header-native"
            override val domains = setOf("example.com")
            override suspend fun extract(request: StreamixExtractorRequest) =
                StreamixExtractorResult(
                    streams = listOf(
                        ProviderStream(
                            providerId = "header-native",
                            url = "https://cdn.example.com/video.m3u8",
                            headers = mapOf("X-Test" to "ok")
                        )
                    )
                )
        }
        val resolver = NativeExtractorCatalogResolver(
            StreamixExtractorRegistry(listOf(extractor)),
            "header-native",
            "header-native-catalog"
        )

        val result = resolver.resolve(
            EpisodeSource(
                id = "source-headers",
                anilistId = 1,
                episodeNumber = 1,
                sourceUrl = "https://example.com/embed/1",
                type = SourceType.RESOLVER,
                headers = linkedMapOf("User-Agent" to "first", "user-agent" to "last")
            )
        )

        val headers = result.streams.single().headers
        assertEquals(1, headers.keys.count { it.equals("user-agent", ignoreCase = true) })
        assertEquals("last", headers.entries.single { it.key.equals("user-agent", ignoreCase = true) }.value)
    }

    @Test
    fun cancellationIsPreserved() = runBlocking {
        val extractor = object : StreamixExtractor {
            override val id = "cancel-native"
            override val domains = setOf("example.com")
            override suspend fun extract(request: StreamixExtractorRequest): StreamixExtractorResult {
                throw kotlinx.coroutines.CancellationException("cancelled")
            }
        }
        val resolver = NativeExtractorCatalogResolver(
            StreamixExtractorRegistry(listOf(extractor)),
            "cancel-native",
            "cancel-native-catalog"
        )

        kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> {
            resolver.resolve(
                EpisodeSource("source", 1, 1, "https://example.com/embed", type = SourceType.RESOLVER)
            )
        }
    }
}