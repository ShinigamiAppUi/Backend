package streamix.catalog

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue
import com.baseprovider.streamix.ProviderExtractorsNative
import kotlinx.coroutines.runBlocking
import streamix.core.StreamixExtractor
import streamix.core.StreamixExtractorRegistry
import streamix.core.StreamixExtractorRequest
import streamix.core.StreamixExtractorResult
import streamix.core.StreamixHttp
import streamix.core.StreamixHttpProbeResponse
import streamix.core.StreamixHttpResponse

class CatalogExtractorTransportTest {
    @Test
    fun extractorSourcesReceiveCatalogScopedTransport() = runBlocking {
        val transport = UnusedHttp()
        var observed: StreamixHttp? = null
        val extractor = object : StreamixExtractor {
            override val id = "scoped-transport-test"
            override val domains = setOf("example.com")
            override suspend fun extract(request: StreamixExtractorRequest): StreamixExtractorResult {
                observed = request.scopedHttp
                return StreamixExtractorResult()
            }
        }
        val service = CatalogService(
            repository = FileCatalogRepository(createTempDirectory("catalog-scoped-extractor").resolve("episodes.json")),
            extractors = StreamixExtractorRegistry(listOf(extractor)),
            catalogHttp = transport
        )
        service.upsertSource(
            EpisodeSource(
                id = "scoped-source",
                anilistId = 987654,
                episodeNumber = 1,
                sourceUrl = "https://example.com/watch",
                type = SourceType.EXTRACTOR
            )
        )

        service.streams(987654, 1)

        assertSame(transport, observed)
    }

    @Test
    fun builtInNativeResolverReceivesCatalogScopedTransport() = runBlocking {
        val transport = UnusedHttp()
        var observed: StreamixHttp? = null
        val jwplayer = object : StreamixExtractor {
            override val id = "jwplayer"
            override val domains = setOf("desustream.me")
            override suspend fun extract(request: StreamixExtractorRequest): StreamixExtractorResult {
                observed = request.scopedHttp
                return StreamixExtractorResult()
            }
        }
        val registry = BuiltInCatalogResolvers.create(
            extractors = StreamixExtractorRegistry((listOf(jwplayer) + ProviderExtractorsNative.all()).distinctBy { it.id.lowercase() }),
            catalogHttp = transport
        )

        val resolver = registry.resolveAll("https://desustream.me/moedesu/example").single()
        val result = resolver.resolve(
            EpisodeSource(
                id = "desustream-source",
                anilistId = 21,
                episodeNumber = 1,
                sourceUrl = "https://desustream.me/moedesu/example",
                type = SourceType.RESOLVER
            )
        )

        assertTrue(result.succeeded.not())
        assertSame(transport, observed)
    }

    private class UnusedHttp : StreamixHttp {
        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            referer: String?,
            cookies: Map<String, String>,
            timeoutMs: Long
        ): StreamixHttpResponse = error("unused test transport")

        override suspend fun post(
            url: String,
            body: String,
            headers: Map<String, String>,
            referer: String?,
            cookies: Map<String, String>,
            timeoutMs: Long
        ): StreamixHttpResponse = error("unused test transport")

        override suspend fun probe(
            url: String,
            headers: Map<String, String>,
            referer: String?,
            cookies: Map<String, String>,
            timeoutMs: Long,
            maxBytes: Long
        ): StreamixHttpProbeResponse = error("unused test transport")
    }
}
