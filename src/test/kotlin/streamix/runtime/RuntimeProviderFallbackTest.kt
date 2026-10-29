package streamix.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import streamix.api.StreamRef
import streamix.core.ProviderStream
import streamix.core.StreamixExtractor
import streamix.core.StreamixExtractorRegistry
import streamix.core.StreamixExtractorRequest
import streamix.core.StreamixExtractorResult
import streamix.core.StreamixHttp
import streamix.core.StreamixHttpProbeResponse
import streamix.core.StreamixHttpResponse

class RuntimeProviderFallbackTest {
    @Test
    fun providerSiteIsUsedOnlyThroughItsEpisodePage() = runBlocking {
        val extractor = fakeExtractor("provider.example", "https://cdn.example/episode-1.m3u8")
        val site = object : RuntimeProviderSite {
            override val id = "fake-site"
            override val priority = 1

            override suspend fun findEpisodePage(title: String, episodeNumber: Int): String? {
                assertEquals("One Piece", title)
                assertEquals(1, episodeNumber)
                return "https://provider.example/series/one-piece/episode/1"
            }
        }

        val streams = RuntimeProviderFallback(
            http = NoopHttp,
            extractors = StreamixExtractorRegistry(listOf(extractor)),
            sites = listOf(site),
            providerExtractors = RuntimeProviderExtractorRegistry(
                NoopHttp,
                listOf(fakeProviderExtractor("fake-site", "https://provider.example/series/one-piece/episode/1"))
            )
        ).streams("One Piece", 1)

        assertEquals(
            listOf(
                StreamRef(
                    providerId = "runtime-provider:fake-site",
                    url = "https://cdn.example/episode-1.m3u8",
                    quality = 720,
                    type = "m3u8",
                    referer = "https://provider.example/series/one-piece/episode/1"
                )
            ),
            streams
        )
    }

    @Test
    fun providersRaceAndFirstPlayableResultWins() = runBlocking {
        val slowStarted = CompletableDeferred<Unit>()
        val slowCancelled = CompletableDeferred<Unit>()

        val slow = object : RuntimeProviderSite {
            override val id = "slow"
            override val priority = 1

            override suspend fun findEpisodePage(title: String, episodeNumber: Int): String? {
                slowStarted.complete(Unit)
                try {
                    delay(5_000)
                    return "https://slow.example/episode/1"
                } finally {
                    slowCancelled.complete(Unit)
                }
            }
        }

        val fast = object : RuntimeProviderSite {
            override val id = "fast"
            override val priority = 2

            override suspend fun findEpisodePage(title: String, episodeNumber: Int): String? {
                slowStarted.await()
                return "https://fast.example/episode/1"
            }
        }

        val streams = withTimeout(2_000) {
            RuntimeProviderFallback(
                http = NoopHttp,
                extractors = StreamixExtractorRegistry(
                    listOf(fakeExtractor("fast.example", "https://cdn.example/fast.m3u8"))
                ),
                sites = listOf(slow, fast),
                providerExtractors = RuntimeProviderExtractorRegistry(
                    NoopHttp,
                    listOf(
                        fakeProviderExtractor("slow", "https://slow.example/episode/1"),
                        fakeProviderExtractor("fast", "https://fast.example/episode/1")
                    )
                )
            ).streams("One Piece", 1)
        }

        assertEquals("runtime-provider:fast", streams.single().providerId)
        assertEquals("https://cdn.example/fast.m3u8", streams.single().url)
        withTimeout(1_000) { slowCancelled.await() }
    }

    @Test
    fun failedRuntimeProviderIsSkippedAndNextProviderWins() = runBlocking {
        val failed = object : RuntimeProviderSite {
            override val id = "failed"
            override val priority = 1

            override suspend fun findEpisodePage(title: String, episodeNumber: Int): String? {
                error("a failed provider must not be called again")
            }
        }
        val working = object : RuntimeProviderSite {
            override val id = "working"
            override val priority = 2

            override suspend fun findEpisodePage(title: String, episodeNumber: Int): String? =
                "https://working.example/episode/1"
        }

        val streams = RuntimeProviderFallback(
            http = NoopHttp,
            extractors = StreamixExtractorRegistry(
                listOf(fakeExtractor("working.example", "https://cdn.example/working.m3u8"))
            ),
            sites = listOf(failed, working),
            providerExtractors = RuntimeProviderExtractorRegistry(
                NoopHttp,
                listOf(fakeProviderExtractor("working", "https://working.example/episode/1"))
            )
        ).streams("One Piece", 1, setOf("runtime-provider:failed"))

        assertEquals("runtime-provider:working", streams.single().providerId)
        assertEquals("https://cdn.example/working.m3u8", streams.single().url)
    }

    @Test
    fun failedProviderDoesNotBlockOtherProviders() = runBlocking {
        val failing = object : RuntimeProviderSite {
            override val id = "failing"
            override val priority = 1
            override suspend fun findEpisodePage(title: String, episodeNumber: Int): String? {
                error("provider failure")
            }
        }

        val working = object : RuntimeProviderSite {
            override val id = "working"
            override val priority = 2
            override suspend fun findEpisodePage(title: String, episodeNumber: Int): String? =
                "https://working.example/episode/1"
        }

        val streams = RuntimeProviderFallback(
            http = NoopHttp,
            extractors = StreamixExtractorRegistry(
                listOf(fakeExtractor("working.example", "https://cdn.example/working.m3u8"))
            ),
            sites = listOf(failing, working),
            providerExtractors = RuntimeProviderExtractorRegistry(
                NoopHttp,
                listOf(fakeProviderExtractor("working", "https://working.example/episode/1"))
            )
        ).streams("One Piece", 1)

        assertEquals("runtime-provider:working", streams.single().providerId)
    }

    private fun fakeProviderExtractor(
        providerId: String,
        candidateUrl: String
    ) = object : RuntimeProviderExtractor {
        override val providerId = providerId
        override suspend fun extract(pageUrl: String) =
            listOf(RuntimeProviderCandidate(candidateUrl, pageUrl))
    }

    private fun fakeExtractor(domain: String, streamUrl: String) = object : StreamixExtractor {
        override val id = "fake-$domain"
        override val domains = setOf(domain)

        override suspend fun extract(request: StreamixExtractorRequest) =
            StreamixExtractorResult(
                streams = listOf(
                    ProviderStream(
                        providerId = id,
                        url = streamUrl,
                        quality = 720,
                        type = "m3u8"
                    )
                )
            )
    }

    private object NoopHttp : StreamixHttp {
        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            referer: String?,
            cookies: Map<String, String>,
            timeoutMs: Long
        ) = StreamixHttpResponse(404, url, "")

        override suspend fun post(
            url: String,
            body: String,
            headers: Map<String, String>,
            referer: String?,
            cookies: Map<String, String>,
            timeoutMs: Long
        ) = StreamixHttpResponse(404, url, "")

        override suspend fun probe(
            url: String,
            headers: Map<String, String>,
            referer: String?,
            cookies: Map<String, String>,
            timeoutMs: Long,
            maxBytes: Long
        ) = StreamixHttpProbeResponse(404, url, 0, true)
    }
}
