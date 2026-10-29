package streamix.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import streamix.core.ProviderStream
import streamix.core.StreamixExtractor
import streamix.core.StreamixExtractorRegistry
import streamix.core.StreamixExtractorRequest
import streamix.core.StreamixExtractorResult
import streamix.core.StreamixHttp
import streamix.core.StreamixHttpProbeResponse
import streamix.core.StreamixHttpResponse

class RuntimeProviderFallbackBoundaryTest {
    @Test
    fun providerPageIsNotSentToGlobalHostExtractorBeforeProviderExtraction() = runBlocking {
        val http = FakeHttp()
        val global = StreamixExtractorRegistry(
            listOf(
                FakeExtractor(
                    id = "global-provider-page-trap",
                    domains = setOf("provider.example"),
                    output = "https://cdn.example/incorrect.m3u8"
                ),
                FakeExtractor(
                    id = "global-host",
                    domains = setOf("host.example"),
                    output = "https://cdn.example/correct.m3u8"
                )
            )
        )
        val providerExtractor = RuntimeProviderExtractorRegistry(
            http,
            listOf(
                object : RuntimeProviderExtractor {
                    override val providerId = "fake-provider"
                    override suspend fun extract(pageUrl: String) =
                        listOf(RuntimeProviderCandidate("https://host.example/embed/1", pageUrl))
                }
            )
        )

        val fallback = RuntimeProviderFallback(
            http = http,
            extractors = global,
            sites = listOf(
                object : RuntimeProviderSite {
                    override val id = "fake-provider"
                    override val priority = 1
                    override suspend fun findEpisodePage(title: String, episodeNumber: Int) =
                        "https://provider.example/episode/1"
                }
            ),
            providerExtractors = providerExtractor
        )

        val result = fallback.streams("Test", 1)
        assertEquals("https://cdn.example/correct.m3u8", result.single().url)
    }


    @Test
    fun privateEmbedCandidateIsRejectedBeforeExtractorOrHttpRequest() = runBlocking {
        val http = RecordingHttp()
        var extractorCalled = false
        val providerExtractor = RuntimeProviderExtractorRegistry(
            http,
            listOf(object : RuntimeProviderExtractor {
                override val providerId = "fake-provider"
                override suspend fun extract(pageUrl: String) =
                    listOf(RuntimeProviderCandidate("http://127.0.0.1/admin", pageUrl))
            })
        )
        val global = StreamixExtractorRegistry(
            listOf(object : StreamixExtractor {
                override val id = "local-target"
                override val domains = setOf("127.0.0.1")
                override suspend fun extract(request: StreamixExtractorRequest): StreamixExtractorResult {
                    extractorCalled = true
                    return StreamixExtractorResult(
                        streams = listOf(ProviderStream(id, "https://cdn.example/unsafe.m3u8", type = "m3u8"))
                    )
                }
            })
        )
        val fallback = RuntimeProviderFallback(
            http = http,
            extractors = global,
            sites = listOf(object : RuntimeProviderSite {
                override val id = "fake-provider"
                override val priority = 1
                override suspend fun findEpisodePage(title: String, episodeNumber: Int) =
                    "https://provider.example/episode/1"
            }),
            providerExtractors = providerExtractor
        )

        assertTrue(fallback.streams("Test", 1).isEmpty())
        assertFalse(extractorCalled)
        assertTrue(http.requestedUrls.isEmpty())
    }


    @Test
    fun privateDirectMediaCandidateIsNotReturnedToClient() = runBlocking {
        val http = RecordingHttp()
        val fallback = RuntimeProviderFallback(
            http = http,
            extractors = StreamixExtractorRegistry(emptyList()),
            sites = listOf(object : RuntimeProviderSite {
                override val id = "fake-provider"
                override val priority = 1
                override suspend fun findEpisodePage(title: String, episodeNumber: Int) =
                    "https://provider.example/episode/1"
            }),
            providerExtractors = RuntimeProviderExtractorRegistry(
                http,
                listOf(object : RuntimeProviderExtractor {
                    override val providerId = "fake-provider"
                    override suspend fun extract(pageUrl: String) =
                        listOf(RuntimeProviderCandidate("http://127.0.0.1/video.m3u8", pageUrl))
                })
            )
        )

        assertTrue(fallback.streams("Test", 1).isEmpty())
        assertTrue(http.requestedUrls.isEmpty())
    }

    @Test
    fun privateEpisodePageIsRejectedBeforeProviderExtractorRequest() = runBlocking {
        val http = RecordingHttp()
        var extractorCalled = false
        val fallback = RuntimeProviderFallback(
            http = http,
            extractors = StreamixExtractorRegistry(emptyList()),
            sites = listOf(object : RuntimeProviderSite {
                override val id = "fake-provider"
                override val priority = 1
                override suspend fun findEpisodePage(title: String, episodeNumber: Int) =
                    "http://192.168.1.20/admin"
            }),
            providerExtractors = RuntimeProviderExtractorRegistry(
                http,
                listOf(object : RuntimeProviderExtractor {
                    override val providerId = "fake-provider"
                    override suspend fun extract(pageUrl: String): List<RuntimeProviderCandidate> {
                        extractorCalled = true
                        return emptyList()
                    }
                })
            )
        )

        assertTrue(fallback.streams("Test", 1).isEmpty())
        assertFalse(extractorCalled)
        assertTrue(http.requestedUrls.isEmpty())
    }

    private class FakeExtractor(
        override val id: String,
        override val domains: Set<String>,
        private val output: String
    ) : StreamixExtractor {
        override suspend fun extract(request: StreamixExtractorRequest) =
            StreamixExtractorResult(
                streams = listOf(ProviderStream(id, output, type = "m3u8", referer = request.referer))
            )
    }

    private class RecordingHttp : StreamixHttp {
        val requestedUrls = mutableListOf<String>()
        override suspend fun get(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long): StreamixHttpResponse {
            requestedUrls += url
            return StreamixHttpResponse(200, url, "")
        }
        override suspend fun post(url: String, body: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long) =
            StreamixHttpResponse(200, url, "")
        override suspend fun probe(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long, maxBytes: Long) =
            StreamixHttpProbeResponse(200, url, 0L, true)
    }

    private class FakeHttp : StreamixHttp {
        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            referer: String?,
            cookies: Map<String, String>,
            timeoutMs: Long
        ) = StreamixHttpResponse(200, url, "")

        override suspend fun post(
            url: String,
            body: String,
            headers: Map<String, String>,
            referer: String?,
            cookies: Map<String, String>,
            timeoutMs: Long
        ) = StreamixHttpResponse(200, url, "")

        override suspend fun probe(
            url: String,
            headers: Map<String, String>,
            referer: String?,
            cookies: Map<String, String>,
            timeoutMs: Long,
            maxBytes: Long
        ) = StreamixHttpProbeResponse(200, url, 0L, true)
    }
}
