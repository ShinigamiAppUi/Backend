package streamix.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import streamix.core.StreamixHttp
import streamix.core.StreamixHttpProbeResponse
import streamix.core.StreamixHttpResponse

class CatalogSafeStreamixHttpTest {
    @Test
    fun rejectsLocalDomainSuffixBeforeDnsLookupOrRequest() = runBlocking {
        val fake = RedirectHttp()
        val http = CatalogSafeStreamixHttp(fake)

        assertFailsWith<IllegalArgumentException> {
            http.get("http://printer.local/admin")
        }
        assertEquals(0, fake.requests)
    }

    @Test
    fun rejectsNat64EmbeddedPrivateIpv4BeforeMakingRequest() = runBlocking {
        val fake = RedirectHttp()
        val http = CatalogSafeStreamixHttp(fake)

        assertFailsWith<IllegalArgumentException> {
            http.get("http://[64:ff9b::a00:1]/admin")
        }
        assertEquals(0, fake.requests)
    }

    @Test
    fun rejectsPrivateRedirectBeforeMakingSecondRequest() = runBlocking {
        val fake = RedirectHttp()
        val http = CatalogSafeStreamixHttp(fake)

        assertFailsWith<IllegalArgumentException> {
            http.get("https://example.com/start")
        }
        assertEquals(1, fake.requests)
    }


    @Test
    fun probeRejectsPrivateRedirectBeforeMakingSecondRequest() = runBlocking {
        val fake = PrivateProbeRedirectHttp()
        val http = CatalogSafeStreamixHttp(fake)

        assertFailsWith<IllegalArgumentException> {
            http.probe("https://example.com/gate", maxBytes = 16)
        }
        assertEquals(1, fake.probeRequests)
    }

    private class PrivateProbeRedirectHttp : StreamixHttp {
        var probeRequests = 0

        override suspend fun get(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long) =
            StreamixHttpResponse(200, url, "")

        override suspend fun post(url: String, body: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long) =
            StreamixHttpResponse(200, url, "")

        override suspend fun probe(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long, maxBytes: Long): StreamixHttpProbeResponse {
            probeRequests++
            return StreamixHttpProbeResponse(302, url, 0, true, mapOf("Location" to listOf("http://127.0.0.1/internal")))
        }
    }

    @Test
    fun probeFollowsRedirectOnlyAfterValidatingDestination() = runBlocking {
        val fake = ProbeRedirectHttp()
        val http = CatalogSafeStreamixHttp(fake)

        val response = http.probe("https://example.com/gate", maxBytes = 16)

        assertEquals(206, response.code)
        assertEquals("https://example.org/media.mp4", response.url)
        assertEquals(2, fake.probeRequests)
    }

    private class ProbeRedirectHttp : StreamixHttp {
        var probeRequests = 0

        override suspend fun get(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long) =
            StreamixHttpResponse(200, url, "")

        override suspend fun post(url: String, body: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long) =
            StreamixHttpResponse(200, url, "")

        override suspend fun probe(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long, maxBytes: Long): StreamixHttpProbeResponse {
            probeRequests++
            return if (probeRequests == 1) {
                StreamixHttpProbeResponse(302, url, 0, true, mapOf("Location" to listOf("https://example.org/media.mp4")))
            } else {
                StreamixHttpProbeResponse(206, url, 16, false)
            }
        }
    }

    private class RedirectHttp : StreamixHttp {
        var requests = 0

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            referer: String?,
            cookies: Map<String, String>,
            timeoutMs: Long
        ): StreamixHttpResponse {
            requests++
            return StreamixHttpResponse(
                code = 302,
                url = url,
                text = "",
                headers = mapOf("Location" to listOf("http://127.0.0.1:8080/internal"))
            )
        }

        override suspend fun post(
            url: String,
            body: String,
            headers: Map<String, String>,
            referer: String?,
            cookies: Map<String, String>,
            timeoutMs: Long
        ) = get(url, headers, referer, cookies, timeoutMs)

        override suspend fun probe(
            url: String,
            headers: Map<String, String>,
            referer: String?,
            cookies: Map<String, String>,
            timeoutMs: Long,
            maxBytes: Long
        ) = StreamixHttpProbeResponse(302, url, 0, true)
    }

    @Test
    fun stripsCredentialsWhenFollowingCrossOriginRedirect() = runBlocking {
        val fake = CrossOriginRedirectHttp()
        val http = CatalogSafeStreamixHttp(
            delegate = fake,
            urlValidator = { java.net.URI(it) }
        )

        val response = http.get(
            "https://example.com/start",
            headers = mapOf("Authorization" to "secret", "X-Public" to "keep", "Cookie" to "secret=1"),
            referer = "https://example.com/page",
            cookies = mapOf("session" to "secret")
        )

        assertEquals(200, response.code)
        assertEquals(2, fake.calls.size)
        assertEquals(mapOf("X-Public" to "keep"), fake.calls[1].headers)
        assertEquals(null, fake.calls[1].referer)
        assertEquals(emptyMap(), fake.calls[1].cookies)
    }

    @Test
    fun rejectsHttpsDowngradeRedirectBeforeSecondRequest() = runBlocking {
        val fake = CrossOriginRedirectHttp(redirectLocation = "http://example.org/final")
        val http = CatalogSafeStreamixHttp(delegate = fake, urlValidator = { java.net.URI(it) })
        assertFailsWith<IllegalArgumentException> {
            http.get("https://example.com/start")
        }
        assertEquals(1, fake.calls.size)
    }

    @Test
    fun rejectsCrossOriginPostPreservingRedirectWithoutSendingSecondRequest() = runBlocking {
        val fake = CrossOriginRedirectHttp(postRedirectCode = 307)
        val http = CatalogSafeStreamixHttp(
            delegate = fake,
            urlValidator = { java.net.URI(it) }
        )

        assertFailsWith<IllegalArgumentException> {
            http.post("https://example.com/start", "payload", headers = mapOf("Authorization" to "secret"))
        }
        assertEquals(1, fake.calls.size)
    }

    private class CrossOriginRedirectHttp(
        private val postRedirectCode: Int = 302,
        private val redirectLocation: String = "https://example.org/final"
    ) : StreamixHttp {
        data class Call(val url: String, val headers: Map<String, String>, val referer: String?, val cookies: Map<String, String>)
        val calls = mutableListOf<Call>()

        override suspend fun get(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long): StreamixHttpResponse {
            calls += Call(url, headers, referer, cookies)
            return if (calls.size == 1) StreamixHttpResponse(
                302, url, "", mapOf("Location" to listOf(redirectLocation))
            ) else StreamixHttpResponse(200, url, "ok")
        }

        override suspend fun post(url: String, body: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long): StreamixHttpResponse {
            calls += Call(url, headers, referer, cookies)
            return StreamixHttpResponse(postRedirectCode, url, "", mapOf("Location" to listOf(redirectLocation)))
        }

        override suspend fun probe(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long, maxBytes: Long) =
            StreamixHttpProbeResponse(200, url, 0, true)
    }

}
