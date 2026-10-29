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

class SendvidCatalogResolverTest {
    @Test
    fun resolvesVideoSourceAndPreservesMetadata() = runBlocking {
        val pageUrl = "https://sendvid.com/example"
        val page = """<source id="video_source" src="/videos/example.mp4">"""
        val http = FakeHttp(StreamixHttpResponse(200, pageUrl, page))
        val result = SendvidCatalogResolver(http).resolve(
            EpisodeSource(
                id = "sendvid",
                anilistId = 1,
                episodeNumber = 1,
                sourceUrl = pageUrl,
                type = SourceType.RESOLVER,
                quality = 720,
                language = "id",
                subtitleLanguage = "id",
                referer = "https://example.test/"
            )
        )

        val stream = result.streams.single()
        assertEquals("https://sendvid.com/videos/example.mp4", stream.url)
        assertEquals("sendvid-catalog", stream.providerId)
        assertEquals(720, stream.quality)
        assertEquals("video", stream.type)
        assertEquals("id", stream.language)
        assertEquals("id", stream.subtitleLanguage)
        assertEquals("https://sendvid.com/example", stream.referer)
        assertEquals("https://sendvid.com/example", stream.headers["Referer"])
    }

    @Test
    fun resolvesHlsSource() = runBlocking {
        val pageUrl = "https://sendvid.com/example"
        val page = """<source id="video_source" src="https://cdn.sendvid.com/video/master.m3u8">"""
        val result = SendvidCatalogResolver(FakeHttp(StreamixHttpResponse(200, pageUrl, page))).resolve(
            EpisodeSource("sendvid", 1, 1, pageUrl, type = SourceType.RESOLVER)
        )

        assertEquals("hls", result.streams.single().type)
        assertEquals("https://cdn.sendvid.com/video/master.m3u8", result.streams.single().url)
    }

    @Test
    fun failsClosedWhenVideoSourceIsMissing() = runBlocking {
        val pageUrl = "https://sendvid.com/example"
        val result = SendvidCatalogResolver(FakeHttp(StreamixHttpResponse(200, pageUrl, "<html/>"))).resolve(
            EpisodeSource("sendvid", 1, 1, pageUrl, type = SourceType.RESOLVER)
        )

        assertTrue(result.streams.isEmpty())
        assertEquals("Sendvid video source not found", result.failureReason)
    }

    @Test
    fun failsClosedOnPageHttpError() = runBlocking {
        val pageUrl = "https://sendvid.com/example"
        val result = SendvidCatalogResolver(FakeHttp(StreamixHttpResponse(403, pageUrl, ""))).resolve(
            EpisodeSource("sendvid", 1, 1, pageUrl, type = SourceType.RESOLVER)
        )

        assertTrue(result.streams.isEmpty())
        assertEquals(403, result.httpStatus)
    }

    @Test
    fun rejectsNonHttpMediaUrl() = runBlocking {
        val pageUrl = "https://sendvid.com/example"
        val page = """<source id="video_source" src="javascript:alert(1)">"""
        val result = SendvidCatalogResolver(FakeHttp(StreamixHttpResponse(200, pageUrl, page))).resolve(
            EpisodeSource("sendvid", 1, 1, pageUrl, type = SourceType.RESOLVER)
        )

        assertTrue(result.streams.isEmpty())
        assertEquals("Sendvid media URL is not HTTP(S)", result.failureReason)
    }

    @Test
    fun propagatesCancellation() = runBlocking {
        val pageUrl = "https://sendvid.com/example"
        val http = object : StreamixHttp {
            override suspend fun get(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long) =
                throw CancellationException("cancelled")
            override suspend fun post(url: String, body: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long) =
                error("unexpected POST")
            override suspend fun probe(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long, maxBytes: Long) =
                error("unexpected probe")
        }

        assertFailsWith<CancellationException> {
            SendvidCatalogResolver(http).resolve(
                EpisodeSource("sendvid", 1, 1, pageUrl, type = SourceType.RESOLVER)
            )
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
