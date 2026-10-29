package streamix.catalog

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import streamix.core.StreamixHttp
import streamix.core.StreamixHttpResponse

class DoodCatalogResolverTest {
    @Test
    fun resolvesPassMd5IntoShortLivedMediaUrl() = runBlocking {
        val pageUrl = "https://dood.to/e/example"
        val passPath = "/pass_md5/abc123"
        val http = FakeHttp(
            mapOf(
                pageUrl to StreamixHttpResponse(200, pageUrl, "<title>720p</title><script>var x='/pass_md5/abc123'</script>"),
                "https://dood.to$passPath" to StreamixHttpResponse(200, "https://cdn.example/video/", "https://cdn.example/video/")
            )
        )

        val result = DoodCatalogResolver(http).resolve(
            EpisodeSource(
                id = "dood",
                anilistId = 1,
                episodeNumber = 1,
                sourceUrl = pageUrl,
                type = SourceType.RESOLVER,
                quality = 720,
                language = "id",
                subtitleLanguage = "id",
                headers = mapOf("User-Agent" to "Shinigami")
            )
        )

        val stream = result.streams.single()
        assertTrue(stream.url.matches(Regex("https://cdn\\.example/video/[A-Za-z0-9]{10}\\?token=abc123&expiry=\\d+")))
        assertEquals("dood-catalog", stream.providerId)
        assertEquals(720, stream.quality)
        assertEquals("https://dood.to/e/example", stream.referer)
        assertEquals("https://dood.to/e/example", stream.headers["Referer"])
        assertEquals("Shinigami", stream.headers["User-Agent"])
    }

    @Test
    fun followsRedirectedFinalPageHost() = runBlocking {
        val sourceUrl = "https://doodstream.com/e/example"
        val finalPage = "https://dood.to/e/example"
        val http = FakeHttp(
            mapOf(
                sourceUrl to StreamixHttpResponse(200, finalPage, "<script>var x='/pass_md5/token'</script>"),
                "https://dood.to/pass_md5/token" to StreamixHttpResponse(200, "https://cdn.example/", "https://cdn.example/")
            )
        )

        val result = DoodCatalogResolver(http).resolve(
            EpisodeSource("dood", 1, 1, sourceUrl, type = SourceType.RESOLVER)
        )

        assertTrue(result.streams.single().url.startsWith("https://cdn.example/"))
        assertEquals(finalPage, result.streams.single().referer)
    }

    @Test
    fun failsClosedWhenPassMd5IsMissing() = runBlocking {
        val pageUrl = "https://dood.to/e/example"
        val http = FakeHttp(mapOf(pageUrl to StreamixHttpResponse(200, pageUrl, "<html>empty</html>")))
        val result = DoodCatalogResolver(http).resolve(EpisodeSource("dood", 1, 1, pageUrl, type = SourceType.RESOLVER))
        assertTrue(result.streams.isEmpty())
        assertEquals("Dood pass_md5 endpoint not found", result.failureReason)
    }

    @Test
    fun failsClosedOnHttpError() = runBlocking {
        val pageUrl = "https://dood.to/e/example"
        val http = FakeHttp(mapOf(pageUrl to StreamixHttpResponse(403, pageUrl, "")))
        val result = DoodCatalogResolver(http).resolve(EpisodeSource("dood", 1, 1, pageUrl, type = SourceType.RESOLVER))
        assertTrue(result.streams.isEmpty())
        assertEquals(403, result.httpStatus)
    }

    @Test
    fun propagatesCancellation() = runBlocking {
        val pageUrl = "https://dood.to/e/example"
        val http = object : StreamixHttp {
            override suspend fun get(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long): StreamixHttpResponse = throw CancellationException("cancelled")
            override suspend fun post(url: String, body: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long) = error("unexpected POST")
            override suspend fun probe(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long, maxBytes: Long) = error("unexpected probe")
        }
        assertFailsWith<CancellationException> {
            DoodCatalogResolver(http).resolve(EpisodeSource("dood", 1, 1, pageUrl, type = SourceType.RESOLVER))
        }
    }

    private class FakeHttp(private val responses: Map<String, StreamixHttpResponse>) : StreamixHttp {
        override suspend fun get(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long) = responses[url] ?: error("unexpected GET: " + url)
        override suspend fun post(url: String, body: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long) = error("unexpected POST")
        override suspend fun probe(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long, maxBytes: Long) = error("unexpected probe")
    }
}