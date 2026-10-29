package streamix.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import streamix.core.StreamixHttp
import streamix.core.StreamixHttpProbeResponse
import streamix.core.StreamixHttpResponse

class StreamHubCatalogResolverTest {
    @Test
    fun resolvesTerminalHlsUrlAndPreservesMetadata() = runBlocking {
        val pageUrl = "https://streamhub.ink/e/example"
        val page = """<script>var a="urlset|abc123|"; var b="width|cdn7|";</script>"""
        val result = StreamHubCatalogResolver(FakeHttp(StreamixHttpResponse(200, pageUrl, page))).resolve(
            EpisodeSource(
                id = "streamhub",
                anilistId = 1,
                episodeNumber = 1,
                sourceUrl = pageUrl,
                type = SourceType.RESOLVER,
                quality = 1080,
                language = "id",
                subtitleLanguage = "id"
            )
        )

        val stream = result.streams.single()
        assertEquals("https://cdn7.streamhub.ink/hls/,abc123,.urlset/master.m3u8", stream.url)
        assertEquals("streamhub-catalog", stream.providerId)
        assertEquals("hls", stream.type)
        assertEquals(1080, stream.quality)
        assertEquals("id", stream.language)
        assertEquals("id", stream.subtitleLanguage)
        assertEquals(pageUrl, stream.referer)
    }

    @Test
    fun failsClosedWhenMediaIdIsMissing() = runBlocking {
        val pageUrl = "https://streamhub.ink/e/example"
        val result = StreamHubCatalogResolver(FakeHttp(StreamixHttpResponse(200, pageUrl, """width|cdn7|"""))).resolve(
            EpisodeSource("streamhub", 1, 1, pageUrl, type = SourceType.RESOLVER)
        )
        assertTrue(result.streams.isEmpty())
        assertEquals("StreamHub media id not found", result.failureReason)
    }

    @Test
    fun failsClosedWhenSubdomainIsMissing() = runBlocking {
        val pageUrl = "https://streamhub.ink/e/example"
        val result = StreamHubCatalogResolver(FakeHttp(StreamixHttpResponse(200, pageUrl, """urlset|abc123|"""))).resolve(
            EpisodeSource("streamhub", 1, 1, pageUrl, type = SourceType.RESOLVER)
        )
        assertTrue(result.streams.isEmpty())
        assertEquals("StreamHub CDN subdomain not found", result.failureReason)
    }

    @Test
    fun rejectsUnsafeSubdomain() = runBlocking {
        val pageUrl = "https://streamhub.ink/e/example"
        val result = StreamHubCatalogResolver(FakeHttp(StreamixHttpResponse(200, pageUrl, """urlset|abc123| width|cdn7.example.com|"""))).resolve(
            EpisodeSource("streamhub", 1, 1, pageUrl, type = SourceType.RESOLVER)
        )
        assertTrue(result.streams.isEmpty())
        assertEquals("StreamHub media parameters malformed", result.failureReason)
    }

    @Test
    fun failsClosedOnPageHttpError() = runBlocking {
        val pageUrl = "https://streamhub.ink/e/example"
        val result = StreamHubCatalogResolver(FakeHttp(StreamixHttpResponse(403, pageUrl, ""))).resolve(
            EpisodeSource("streamhub", 1, 1, pageUrl, type = SourceType.RESOLVER)
        )
        assertTrue(result.streams.isEmpty())
        assertEquals(403, result.httpStatus)
    }

    @Test
    fun propagatesCancellation() = runBlocking {
        val pageUrl = "https://streamhub.ink/e/example"
        val http = object : StreamixHttp {
            override suspend fun get(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long) =
                throw CancellationException("cancelled")
            override suspend fun post(url: String, body: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long) =
                error("unexpected POST")
            override suspend fun probe(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long, maxBytes: Long) =
                error("unexpected probe")
        }
        assertFailsWith<CancellationException> {
            StreamHubCatalogResolver(http).resolve(EpisodeSource("streamhub", 1, 1, pageUrl, type = SourceType.RESOLVER))
        }
    }

    private class FakeHttp(
        private val response: StreamixHttpResponse
    ) : StreamixHttp {
        override suspend fun get(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long) = response
        override suspend fun post(url: String, body: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long) = error("unexpected POST")
        override suspend fun probe(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long, maxBytes: Long) = error("unexpected probe")
    }
}
