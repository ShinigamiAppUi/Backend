package streamix.catalog

import kotlinx.coroutines.runBlocking
import streamix.core.StreamixHttp
import streamix.core.StreamixHttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KrakenFilesCatalogResolverTest {
    private class FakeHttp(
        private val response: StreamixHttpResponse
    ) : StreamixHttp {
        override suspend fun get(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long) = response
        override suspend fun post(url: String, body: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long) = error("unused")
        override suspend fun probe(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long, maxBytes: Long) = error("unused")
    }

    @Test
    fun resolvesMediaSourceAndPreservesMetadata() = runBlocking {
        val http = FakeHttp(
            StreamixHttpResponse(
                code = 200,
                url = "https://krakenfiles.com/embed-video/abc123",
                text = """<html><body><video><source src="//cdn.krakenfiles.com/video/abc123.mkv"></video></body></html>"""
            )
        )
        val resolver = KrakenFilesCatalogResolver(http)
        val result = resolver.resolve(
            EpisodeSource(
                id = "kf-1",
                anilistId = 1,
                episodeNumber = 1,
                sourceUrl = "https://krakenfiles.com/view/abc123/file.html",
                type = SourceType.RESOLVER,
                quality = 1080,
                language = "id",
                subtitleLanguage = "id",
                headers = mapOf("X-Test" to "ok"),
                referer = "https://watch.example/"
            )
        )

        assertTrue(result.succeeded)
        assertEquals("https://cdn.krakenfiles.com/video/abc123.mkv", result.streams.single().url)
        assertEquals(1080, result.streams.single().quality)
        assertEquals("id", result.streams.single().language)
        assertEquals(mapOf("X-Test" to "ok"), result.streams.single().headers)
        assertEquals("https://watch.example/", result.streams.single().referer)
    }

    @Test
    fun nonSuccessHttpFailsClosed() = runBlocking {
        val resolver = KrakenFilesCatalogResolver(
            FakeHttp(
                StreamixHttpResponse(
                    code = 403,
                    url = "https://krakenfiles.com/embed-video/abc123",
                    text = ""
                )
            )
        )
        val result = resolver.resolve(
            EpisodeSource(
                id = "kf-2",
                anilistId = 1,
                episodeNumber = 1,
                sourceUrl = "https://krakenfiles.com/view/abc123/file.html",
                type = SourceType.RESOLVER
            )
        )
        assertEquals(403, result.httpStatus)
        assertTrue(result.streams.isEmpty())
    }
}
