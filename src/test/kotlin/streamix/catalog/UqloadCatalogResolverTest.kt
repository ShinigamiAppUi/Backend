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

class UqloadCatalogResolverTest {
    @Test
    fun directSource() = runBlocking {
        val url = "https://uqload.is/e/x"
        val page = """<script>var x = sources: ["https://cdn.uqload.is/video/master.m3u8"];</script>"""
        val result = UqloadCatalogResolver(Fake(StreamixHttpResponse(200, url, page))).resolve(
            EpisodeSource("uq", 1, 1, url, type = SourceType.RESOLVER, quality = 1080, language = "id", subtitleLanguage = "id")
        )
        val s = result.streams.single()
        assertEquals("https://cdn.uqload.is/video/master.m3u8", s.url)
        assertEquals("uqload-catalog", s.providerId)
        assertEquals("hls", s.type)
        assertEquals(1080, s.quality)
        assertEquals("id", s.language)
        assertEquals(url, s.referer)
    }

    @Test
    fun evalMarkerDoesNotBlockDirectSource() = runBlocking {
        val url = "https://uqload.is/e/x"
        val page = "<script>eval(function(p,a,c,k,e,r){return p}('x',1,1,'x'.split('|'))); var x = sources: [\"https://cdn.uqload.is/video/file.mp4\"]; </script>"
        val result = UqloadCatalogResolver(Fake(StreamixHttpResponse(200, url, page))).resolve(
            EpisodeSource("uq", 1, 1, url, type = SourceType.RESOLVER)
        )
        assertEquals("https://cdn.uqload.is/video/file.mp4", result.streams.single().url)
    }


    @Test
    fun relativeMediaSourceIsResolvedAgainstFinalPageUrl() = runBlocking {
        val url = "https://uqload.is/e/x"
        val page = """<script>var x = sources: ["/video/master.m3u8"];</script>"""
        val result = UqloadCatalogResolver(Fake(StreamixHttpResponse(200, url, page))).resolve(
            EpisodeSource("uq", 1, 1, url, type = SourceType.RESOLVER)
        )
        assertEquals("https://uqload.is/video/master.m3u8", result.streams.single().url)
        assertEquals("hls", result.streams.single().type)
    }

    @Test
    fun missingFailsClosed() = runBlocking {
        val url = "https://uqload.is/e/x"
        val result = UqloadCatalogResolver(Fake(StreamixHttpResponse(200, url, "<html/>"))).resolve(EpisodeSource("uq", 1, 1, url, type = SourceType.RESOLVER))
        assertTrue(result.streams.isEmpty())
        assertEquals("Uqload media source not found", result.failureReason)
    }

    @Test
    fun httpFailsClosed() = runBlocking {
        val url = "https://uqload.is/e/x"
        val result = UqloadCatalogResolver(Fake(StreamixHttpResponse(403, url, ""))).resolve(EpisodeSource("uq", 1, 1, url, type = SourceType.RESOLVER))
        assertTrue(result.streams.isEmpty())
        assertEquals(403, result.httpStatus)
    }

    @Test
    fun cancellation() = runBlocking {
        val url = "https://uqload.is/e/x"
        val http = object : StreamixHttp {
            override suspend fun get(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long) = throw CancellationException("cancelled")
            override suspend fun post(url: String, body: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long) = error("unexpected")
            override suspend fun probe(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long, maxBytes: Long) = error("unexpected")
        }
        assertFailsWith<CancellationException> {
            UqloadCatalogResolver(http).resolve(EpisodeSource("uq", 1, 1, url, type = SourceType.RESOLVER))
        }
    }

    private class Fake(private val r: StreamixHttpResponse) : StreamixHttp {
        override suspend fun get(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long) = r
        override suspend fun post(url: String, body: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long) = error("unexpected")
        override suspend fun probe(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long, maxBytes: Long) = error("unexpected")
    }
}
