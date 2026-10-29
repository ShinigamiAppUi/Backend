package streamix.catalog

import com.baseprovider.streamix.StreamixExtractorApi
import com.baseprovider.streamix.StreamixExtractorLink
import com.baseprovider.streamix.StreamixExtractorLinkType
import com.baseprovider.streamix.StreamixRuntime
import com.baseprovider.streamix.StreamixSubtitleFile
import com.baseprovider.streamix.app
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import streamix.core.StreamixExtractorRequest
import streamix.core.StreamixHttp
import streamix.core.StreamixHttpContext
import streamix.core.StreamixHttpProbeResponse
import streamix.core.StreamixHttpResponse

class ScopedExtractorHttpTest {
    @Test
    fun catalogScopedConfigWebviewFailsClosedBeforeLookingUpGlobalResolver() = runBlocking {
        var resolverLookups = 0
        val extractor = com.baseprovider.extractor.ConfigDrivenStreamixExtractor(
            config = com.baseprovider.config.ExtractorConfig(
                id = "catalog-webview-scope-test",
                mainUrl = "https://example.test",
                steps = listOf(com.baseprovider.config.ExtractorStep.Webview())
            ),
            registry = object : com.baseprovider.streamix.StreamixExtractorRegistry {
                override fun getMatchingExtractors(url: String) = emptyList<com.baseprovider.streamix.StreamixExtractor>()
                override fun hasMatchingExtractor(url: String) = false
                override fun all() = emptyList<com.baseprovider.streamix.StreamixExtractor>()
                override suspend fun resolve(
                    url: String,
                    referer: String?,
                    subtitleCallback: (com.baseprovider.streamix.StreamixSubtitle) -> Unit,
                    callback: (com.baseprovider.streamix.StreamixStream) -> Unit,
                    callChain: Set<String>
                ) = false
            },
            webResolver = {
                resolverLookups++
                object : streamix.core.StreamixWebResolver {
                    override suspend fun resolve(
                        request: streamix.core.StreamixWebRequest,
                        onRequest: suspend (streamix.core.StreamixWebRequest) -> Boolean
                    ) = streamix.core.StreamixWebResult(
                        intercepted = streamix.core.StreamixWebRequest("https://example.test/should-not-run.m3u8")
                    )
                }
            }
        )

        val result = extractor.extract(
            StreamixExtractorRequest(
                url = "https://example.test/embed",
                scopedHttp = RecordingHttp("catalog")
            )
        )

        assertEquals(0, resolverLookups, "Catalog-scoped WebView steps must fail closed before accessing the global resolver")
        assertEquals(0, result.streams.size, "A blocked WebView step must not return a stream")
    }

    @Test
    fun scopedTransportIsUsedByLegacyHelpersWithoutChangingGlobalRuntime() = runBlocking {
        val global = RecordingHttp("global")
        val scoped = RecordingHttp("scoped")
        val previous = runCatching { StreamixRuntime.http }.getOrNull()
        // Install is intentionally not needed: a scoped call must not consult the global transport.
        val extractor = object : StreamixExtractorApi() {
            override val name = "ScopedTest"
            override val mainUrl = "https://example.test"

            override suspend fun getUrl(
                url: String,
                referer: String?,
                subtitleCallback: (StreamixSubtitleFile) -> Unit,
                callback: (StreamixExtractorLink) -> Unit
            ) {
                val response = app.get(url)
                app.post("$url/post", data = mapOf("scope" to "checked"))
                app.probe("$url/probe", maxBytes = 128)
                val scoped = app.hasScopedHttpRequest()
                callback(StreamixExtractorLink(name, name, "${response.text}:scoped=$scoped", StreamixExtractorLinkType.VIDEO))
            }
        }

        // Two simultaneous requests must each retain their own transport.
        val first = async { extractor.extract(StreamixExtractorRequest("https://first.test", scopedHttp = scoped)) }
        val second = async { extractor.extract(StreamixExtractorRequest("https://second.test", scopedHttp = global)) }
        assertEquals("scoped:https://first.test:scoped=true", first.await().streams.single().url)
        assertEquals("global:https://second.test:scoped=true", second.await().streams.single().url)
        assertEquals(listOf("https://first.test"), scoped.requested)
        assertEquals(listOf("https://second.test"), global.requested)
        assertEquals(listOf("https://first.test/post"), scoped.posted)
        assertEquals(listOf("https://second.test/post"), global.posted)
        assertEquals(listOf("https://first.test/probe"), scoped.probed)
        assertEquals(listOf("https://second.test/probe"), global.probed)

        val ordinary = object : StreamixExtractorApi() {
            override val name = "OrdinaryTest"
            override val mainUrl = "https://example.test"
            override suspend fun getUrl(
                url: String, referer: String?,
                subtitleCallback: (StreamixSubtitleFile) -> Unit,
                callback: (StreamixExtractorLink) -> Unit
            ) {
                callback(StreamixExtractorLink(name, name, "scoped=${app.hasScopedHttpRequest()}", StreamixExtractorLinkType.VIDEO))
            }
        }
        assertEquals("scoped=false", ordinary.extract(StreamixExtractorRequest("https://ordinary.test")).streams.single().url)
        if (previous != null) assertSame(previous, StreamixRuntime.http)
    }

    @Test
    fun nativeCatalogResolverPassesScopedTransportIntoExtractorInvocation() = runBlocking {
        val scoped = RecordingHttp("catalog")
        val extractor = object : StreamixExtractorApi() {
            override val name = "NestedCatalogTest"
            override val mainUrl = "https://example.test"

            override suspend fun getUrl(
                url: String,
                referer: String?,
                subtitleCallback: (StreamixSubtitleFile) -> Unit,
                callback: (StreamixExtractorLink) -> Unit
            ) {
                app.get(url, referer = referer)
                callback(
                    StreamixExtractorLink(
                        name, name, "https://cdn.example.test/video.mp4",
                        StreamixExtractorLinkType.VIDEO
                    )
                )
            }
        }
        val registry = streamix.core.StreamixExtractorRegistry(listOf(extractor))
        val resolver = NativeExtractorCatalogResolver(registry, "NestedCatalogTest", "nested-catalog-test", scoped)
        val source = EpisodeSource(
            id = "nested-catalog-source",
            anilistId = 21L,
            episodeNumber = 1,
            sourceUrl = "https://example.test/embed",
            type = SourceType.RESOLVER
        )

        val result = resolver.resolve(source)

        assertEquals(listOf("https://example.test/embed"), scoped.requested)
        assertEquals("https://cdn.example.test/video.mp4", result.streams.single().url)
        assertEquals("catalog:nested-catalog-source", result.streams.single().providerId)
    }

    private class RecordingHttp(private val label: String) : StreamixHttp {
        val requested = mutableListOf<String>()
        val posted = mutableListOf<String>()
        val probed = mutableListOf<String>()

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            referer: String?,
            cookies: Map<String, String>,
            timeoutMs: Long
        ): StreamixHttpResponse {
            requested += url
            return StreamixHttpResponse(200, url, "$label:$url")
        }

        override suspend fun post(
            url: String,
            body: String,
            headers: Map<String, String>,
            referer: String?,
            cookies: Map<String, String>,
            timeoutMs: Long
        ): StreamixHttpResponse {
            posted += url
            return StreamixHttpResponse(200, url, "$label:$url")
        }

        override suspend fun probe(
            url: String,
            headers: Map<String, String>,
            referer: String?,
            cookies: Map<String, String>,
            timeoutMs: Long,
            maxBytes: Long
        ): StreamixHttpProbeResponse {
            probed += url
            return StreamixHttpProbeResponse(200, url, 0, true)
        }
    }
}
