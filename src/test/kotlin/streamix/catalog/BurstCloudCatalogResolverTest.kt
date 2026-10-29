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

class BurstCloudCatalogResolverTest {
    @Test
    fun resolvesCdnUrlAndPreservesMetadata() = runBlocking {
        val pageUrl = "https://www.burstcloud.co/e/example"
        val page = """<div id="player" data-file-id="abc123"></div>"""
        val api = """{"purchase":{"cdnUrl":"https://cdn.burstcloud.co/video/abc123.mp4"}}"""
        val result = BurstCloudCatalogResolver(
            FakeHttp(
                getResponse = StreamixHttpResponse(200, pageUrl, page),
                postResponse = StreamixHttpResponse(200, "https://www.burstcloud.co/file/play-request/", api)
            )
        ).resolve(
            EpisodeSource(
                id = "burstcloud",
                anilistId = 1,
                episodeNumber = 1,
                sourceUrl = pageUrl,
                type = SourceType.RESOLVER,
                quality = 720,
                language = "id",
                subtitleLanguage = "id"
            )
        )

        val stream = result.streams.single()
        assertEquals("https://cdn.burstcloud.co/video/abc123.mp4", stream.url)
        assertEquals("burstcloud-catalog", stream.providerId)
        assertEquals(720, stream.quality)
        assertEquals("id", stream.language)
        assertEquals("id", stream.subtitleLanguage)
        assertEquals(pageUrl, stream.referer)
        assertEquals(pageUrl, stream.headers["Referer"])
    }

    @Test
    fun failsClosedWhenFileIdIsMissing() = runBlocking {
        val pageUrl = "https://www.burstcloud.co/e/example"
        val result = BurstCloudCatalogResolver(
            FakeHttp(StreamixHttpResponse(200, pageUrl, "<div id=\"player\"></div>"))
        ).resolve(EpisodeSource("burstcloud", 1, 1, pageUrl, type = SourceType.RESOLVER))

        assertTrue(result.streams.isEmpty())
        assertEquals("BurstCloud player file id not found", result.failureReason)
    }

    @Test
    fun failsClosedWhenCdnUrlIsMissing() = runBlocking {
        val pageUrl = "https://www.burstcloud.co/e/example"
        val result = BurstCloudCatalogResolver(
            FakeHttp(
                StreamixHttpResponse(200, pageUrl, """<div id="player" data-file-id="abc123"></div>"""),
                StreamixHttpResponse(200, "https://www.burstcloud.co/file/play-request/", """{"purchase":{}}""")
            )
        ).resolve(EpisodeSource("burstcloud", 1, 1, pageUrl, type = SourceType.RESOLVER))

        assertTrue(result.streams.isEmpty())
        assertEquals("BurstCloud CDN URL not found", result.failureReason)
    }

    @Test
    fun rejectsNonHttpCdnUrl() = runBlocking {
        val pageUrl = "https://www.burstcloud.co/e/example"
        val result = BurstCloudCatalogResolver(
            FakeHttp(
                StreamixHttpResponse(200, pageUrl, """<div id="player" data-file-id="abc123"></div>"""),
                StreamixHttpResponse(200, "https://www.burstcloud.co/file/play-request/", """{"purchase":{"cdnUrl":"javascript:alert(1)"}}""")
            )
        ).resolve(EpisodeSource("burstcloud", 1, 1, pageUrl, type = SourceType.RESOLVER))

        assertTrue(result.streams.isEmpty())
        assertEquals("BurstCloud CDN URL is not HTTP(S)", result.failureReason)
    }

    @Test
    fun failsClosedOnApiHttpError() = runBlocking {
        val pageUrl = "https://www.burstcloud.co/e/example"
        val result = BurstCloudCatalogResolver(
            FakeHttp(
                StreamixHttpResponse(200, pageUrl, """<div id="player" data-file-id="abc123"></div>"""),
                StreamixHttpResponse(403, "https://www.burstcloud.co/file/play-request/", "")
            )
        ).resolve(EpisodeSource("burstcloud", 1, 1, pageUrl, type = SourceType.RESOLVER))

        assertTrue(result.streams.isEmpty())
        assertEquals(403, result.httpStatus)
    }

    @Test
    fun propagatesCancellation() = runBlocking {
        val pageUrl = "https://www.burstcloud.co/e/example"
        val http = object : StreamixHttp {
            override suspend fun get(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long) =
                StreamixHttpResponse(200, pageUrl, """<div id="player" data-file-id="abc123"></div>""")
            override suspend fun post(url: String, body: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long) =
                throw CancellationException("cancelled")
            override suspend fun probe(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long, maxBytes: Long) =
                error("unexpected probe")
        }

        assertFailsWith<CancellationException> {
            BurstCloudCatalogResolver(http).resolve(
                EpisodeSource("burstcloud", 1, 1, pageUrl, type = SourceType.RESOLVER)
            )
        }
    }

    private class FakeHttp(
        private val getResponse: StreamixHttpResponse,
        private val postResponse: StreamixHttpResponse = StreamixHttpResponse(0, "", "")
    ) : StreamixHttp {
        override suspend fun get(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long) = getResponse
        override suspend fun post(url: String, body: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long) = postResponse
        override suspend fun probe(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long, maxBytes: Long) = error("unexpected probe")
    }
}
