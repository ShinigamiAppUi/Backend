package streamix.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import streamix.core.StreamixExtractorRegistry
import streamix.core.StreamixHttp
import streamix.core.StreamixHttpProbeResponse
import streamix.core.StreamixHttpResponse

class RuntimeProviderFallbackDeadlineTest {
    @Test
    fun allProviderFailuresReturnEmptyAndReportSafeOutcomes() = runBlocking {
        val diagnostics = mutableListOf<RuntimeFallbackDiagnostic>()
        val failing = testSite("failing") { error("private exception text must not be exposed") }
        val empty = testSite("empty") { null }

        val result = RuntimeProviderFallback(
            http = NoopHttp,
            extractors = StreamixExtractorRegistry(emptyList()),
            sites = listOf(failing, empty),
            providerExtractors = RuntimeProviderExtractorRegistry(NoopHttp, emptyList()),
            overallTimeoutMs = 500,
            onDiagnostic = { diagnostics.add(it) }
        ).streams("One Piece", 1)

        assertTrue(result.isEmpty())
        assertEquals(setOf("error", "empty"), diagnostics.map { it.outcome }.toSet())
        assertEquals(setOf("failing", "empty"), diagnostics.map { it.providerId }.toSet())
        assertTrue(diagnostics.all { it.elapsedMs >= 0L })
    }

    @Test
    fun overallDeadlineReturnsEmptyAndCancelsProviderWork() = runBlocking {
        val cancelled = CompletableDeferred<Unit>()
        val diagnostics = mutableListOf<RuntimeFallbackDiagnostic>()
        val slow = object : RuntimeProviderSite {
            override val id = "slow-safe-id"
            override val priority = 1
            override suspend fun findEpisodePage(title: String, episodeNumber: Int): String? {
                try {
                    delay(Long.MAX_VALUE)
                    return null
                } finally {
                    cancelled.complete(Unit)
                }
            }
        }

        val result = withTimeout(1_000) {
            RuntimeProviderFallback(
                http = NoopHttp,
                extractors = StreamixExtractorRegistry(emptyList()),
                sites = listOf(slow),
                providerExtractors = RuntimeProviderExtractorRegistry(NoopHttp, emptyList()),
                overallTimeoutMs = 50,
                onDiagnostic = { diagnostics.add(it) }
            ).streams("One Piece", 1)
        }

        assertTrue(result.isEmpty())
        withTimeout(500) { cancelled.await() }
        assertTrue(diagnostics.any {
            it.providerId == "runtime-fallback" && it.outcome == "overall_timeout"
        })
        assertTrue(diagnostics.none { it.providerId.contains("https://") || it.outcome.contains("private") })
    }

    @Test
    fun timeoutCancellationDuringEmbedFetchPropagatesThroughHttpHelper() = runBlocking {
        val cancelled = CompletableDeferred<Unit>()
        val site = testSite("embed-fetch") { "https://provider.example/episode/1" }
        val blockingHttp = object : StreamixHttp {
            override suspend fun get(
                url: String,
                headers: Map<String, String>,
                referer: String?,
                cookies: Map<String, String>,
                timeoutMs: Long
            ): StreamixHttpResponse {
                try {
                    delay(Long.MAX_VALUE)
                    return StreamixHttpResponse(200, url, "")
                } finally {
                    cancelled.complete(Unit)
                }
            }

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

        val result = withTimeout(1_000) {
            RuntimeProviderFallback(
                http = blockingHttp,
                extractors = StreamixExtractorRegistry(emptyList()),
                sites = listOf(site),
                providerExtractors = RuntimeProviderExtractorRegistry(
                    blockingHttp,
                    listOf(object : RuntimeProviderExtractor {
                        override val providerId = "embed-fetch"
                        override suspend fun extract(pageUrl: String) =
                            listOf(RuntimeProviderCandidate("https://unhandled.example/embed", pageUrl))
                    })
                ),
                overallTimeoutMs = 50
            ).streams("One Piece", 1)
        }

        assertTrue(result.isEmpty())
        withTimeout(500) { cancelled.await() }
    }

    private fun testSite(
        id: String,
        find: suspend () -> String?
    ) = object : RuntimeProviderSite {
        override val id = id
        override val priority = 1
        override suspend fun findEpisodePage(title: String, episodeNumber: Int): String? = find()
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
