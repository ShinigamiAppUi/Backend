package com.baseprovider.core

import com.baseprovider.network.shouldUseGlobalCloudflareResolver
import com.baseprovider.network.shouldUseGlobalProviderState
import com.baseprovider.network.shouldUseProviderDocumentCache
import com.baseprovider.network.fetchDocument
import com.baseprovider.network.HostCookieJar
import com.baseprovider.cache.ExpiringCache
import com.baseprovider.config.ProviderConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import streamix.core.StreamixHttp
import streamix.core.StreamixHttpContext
import streamix.core.StreamixHttpProbeResponse
import streamix.core.StreamixHttpResponse

class ScopedCloudflarePolicyTest {
    @Test
    fun catalogScopedRequestsCannotUseGlobalWebViewCloudflareResolver() = runBlocking {
        assertTrue(shouldUseGlobalCloudflareResolver(), "Ordinary provider calls retain global resolver compatibility")
        assertTrue(shouldUseGlobalProviderState(), "Ordinary provider calls retain provider-global cookie state")
        assertTrue(shouldUseProviderDocumentCache(), "Ordinary provider calls retain document cache behavior")

        withContext(StreamixHttpContext(RecordingHttp())) {
            assertFalse(
                shouldUseGlobalCloudflareResolver(),
                "Catalog-scoped requests must not escape the pinned HTTP boundary through the global WebView resolver"
            )
            assertFalse(
                shouldUseGlobalProviderState(),
                "Catalog-scoped requests must not import or mutate provider-global cookies"
            )
            assertFalse(
                shouldUseProviderDocumentCache(),
                "Catalog-scoped requests must not read from or write to a provider-global document cache"
            )
        }
    }

    @Test
    fun catalogScopedFetchDocumentDoesNotShareProviderCookiesOrHtmlCache() = runBlocking {
        val url = "https://catalog-scope.test/episode"
        HostCookieJar.clear()
        try {
            HostCookieJar.update(url, mapOf("provider-session" to "secret"))
            val providerCache = ExpiringCache<Document>(60_000)
            providerCache.put(url, Jsoup.parse("<p>provider-cache</p>", url))
            val scopedHttp = RecordingHttp(
                body = "<p>catalog-response</p>",
                responseCookies = mapOf("catalog-session" to "isolated")
            )

            val document = withContext(StreamixHttpContext(scopedHttp)) {
                fetchDocument(
                    url = url,
                    config = ProviderConfig(id = "scoped-test"),
                    htmlCache = providerCache
                )
            }

            assertEquals("catalog-response", document.selectFirst("p")?.text())
            assertEquals(emptyMap(), scopedHttp.lastCookies)
            assertEquals(mapOf("provider-session" to "secret"), HostCookieJar.getFor(url))
            assertEquals("provider-cache", providerCache.get(url)?.selectFirst("p")?.text())
        } finally {
            // Keep the process-global cookie jar clean even if an assertion fails.
            HostCookieJar.clear()
        }
    }

    private class RecordingHttp(
        private val body: String = "",
        private val responseCookies: Map<String, String> = emptyMap()
    ) : StreamixHttp {
        var lastCookies: Map<String, String> = emptyMap()
            private set

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            referer: String?,
            cookies: Map<String, String>,
            timeoutMs: Long
        ): StreamixHttpResponse {
            lastCookies = cookies
            return StreamixHttpResponse(200, url, body, cookies = responseCookies)
        }

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
        ) = StreamixHttpProbeResponse(200, url, 0, true)
    }
}
