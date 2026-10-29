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

class StreamtapeCatalogResolverTest {
    @Test
    fun resolvesObfuscatedGetVideoGateToTapeContent() = runBlocking {
        val pageUrl = "https://streamtape.com/v/example"
        val page = """
            <html>
              <h2>Ao no Hako S2 - 01.1K.mp4</h2>
              <script>
                document.getElementById('robotlink').innerHTML = '/get_video?token=' + ('abc123') + '&expires=999';
              </script>
            </html>
        """.trimIndent()

        val http = FakeHttp(
            pageResponse = StreamixHttpResponse(200, pageUrl, page),
            probeResponse = StreamixHttpProbeResponse(
                code = 200,
                url = "https://cdn.tapecontent.net/radosgw/file.mp4?stream=1",
                bytesRead = 16,
                eof = false
            )
        )

        val result = StreamtapeCatalogResolver(http).resolve(
            EpisodeSource(
                id = "streamtape",
                anilistId = 1,
                episodeNumber = 1,
                sourceUrl = pageUrl,
                type = SourceType.RESOLVER,
                quality = 1000,
                language = "id",
                subtitleLanguage = "id"
            )
        )

        val stream = result.streams.single()
        assertEquals("https://cdn.tapecontent.net/radosgw/file.mp4?stream=1", stream.url)
        assertEquals("streamtape-catalog", stream.providerId)
        assertEquals(1000, stream.quality)
        assertEquals("id", stream.language)
        assertEquals("id", stream.subtitleLanguage)
    }

    @Test
    fun resolvesAbsoluteGetVideoPath() = runBlocking {
        val pageUrl = "https://streamtape.to/v/example"
        val page = """<script>var x = "https://streamtape.to/get_video?token=abc";</script>"""
        val http = FakeHttp(
            StreamixHttpResponse(200, pageUrl, page),
            StreamixHttpProbeResponse(
                200,
                "https://media.tapecontent.net/video.mp4?stream=1",
                16,
                false
            )
        )

        val result = StreamtapeCatalogResolver(http).resolve(
            EpisodeSource("streamtape", 1, 1, pageUrl, type = SourceType.RESOLVER)
        )

        assertEquals("https://media.tapecontent.net/video.mp4?stream=1", result.streams.single().url)
    }

    @Test
    fun failsClosedWhenGateMissing() = runBlocking {
        val pageUrl = "https://streamtape.com/v/example"
        val http = FakeHttp(
            StreamixHttpResponse(200, pageUrl, "<html>empty</html>"),
            StreamixHttpProbeResponse(0, "", 0, true)
        )

        val result = StreamtapeCatalogResolver(http).resolve(
            EpisodeSource("streamtape", 1, 1, pageUrl, type = SourceType.RESOLVER)
        )

        assertTrue(result.streams.isEmpty())
        assertEquals("Streamtape get_video gate not found", result.failureReason)
    }

    @Test
    fun rejectsUnexpectedFinalHost() = runBlocking {
        val pageUrl = "https://streamtape.com/v/example"
        val page = """<script>var x = "/get_video?token=abc";</script>"""
        val http = FakeHttp(
            StreamixHttpResponse(200, pageUrl, page),
            StreamixHttpProbeResponse(
                200,
                "https://evil.example/video.mp4",
                16,
                false
            )
        )

        val result = StreamtapeCatalogResolver(http).resolve(
            EpisodeSource("streamtape", 1, 1, pageUrl, type = SourceType.RESOLVER)
        )

        assertTrue(result.streams.isEmpty())
        assertEquals("Streamtape final URL is not tapecontent.net", result.failureReason)
    }

    @Test
    fun followsValidatedGetVideoRedirectToTapeContent() = runBlocking {
        val pageUrl = "https://example.com/v/example"
        val page = """<script>var x = "/get_video?token=abc";</script>"""
        var probeRequests = 0
        val delegate = object : StreamixHttp {
            override suspend fun get(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long) =
                StreamixHttpResponse(200, pageUrl, page)

            override suspend fun post(url: String, body: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long) =
                error("unexpected POST")

            override suspend fun probe(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long, maxBytes: Long): StreamixHttpProbeResponse {
                probeRequests++
                return if (probeRequests == 1) {
                    StreamixHttpProbeResponse(
                        302, url, 0, true,
                        mapOf("Location" to listOf("https://cdn.tapecontent.net/radosgw/file.mp4?stream=1"))
                    )
                } else {
                    StreamixHttpProbeResponse(206, url, 16, false)
                }
            }
        }

        val safeHttp = CatalogSafeStreamixHttp(delegate, urlValidator = { raw ->
            val uri = java.net.URI(raw)
            require(uri.scheme == "https")
            require(uri.host in setOf("example.com", "cdn.tapecontent.net"))
            uri
        })
        val result = StreamtapeCatalogResolver(safeHttp).resolve(
            EpisodeSource("streamtape", 1, 1, pageUrl, type = SourceType.RESOLVER)
        )

        assertEquals("https://cdn.tapecontent.net/radosgw/file.mp4?stream=1", result.streams.single().url)
        assertEquals(2, probeRequests)
    }

    @Test
    fun failsClosedOnPageHttpError() = runBlocking {
        val pageUrl = "https://streamtape.com/v/example"
        val http = FakeHttp(
            StreamixHttpResponse(403, pageUrl, ""),
            StreamixHttpProbeResponse(0, "", 0, true)
        )

        val result = StreamtapeCatalogResolver(http).resolve(
            EpisodeSource("streamtape", 1, 1, pageUrl, type = SourceType.RESOLVER)
        )

        assertTrue(result.streams.isEmpty())
        assertEquals(403, result.httpStatus)
    }

    @Test
    fun propagatesCancellation() = runBlocking {
        val pageUrl = "https://streamtape.com/v/example"
        val http = object : StreamixHttp {
            override suspend fun get(
                url: String,
                headers: Map<String, String>,
                referer: String?,
                cookies: Map<String, String>,
                timeoutMs: Long
            ): StreamixHttpResponse = throw CancellationException("cancelled")

            override suspend fun post(
                url: String,
                body: String,
                headers: Map<String, String>,
                referer: String?,
                cookies: Map<String, String>,
                timeoutMs: Long
            ) = error("unexpected POST")

            override suspend fun probe(
                url: String,
                headers: Map<String, String>,
                referer: String?,
                cookies: Map<String, String>,
                timeoutMs: Long,
                maxBytes: Long
            ) = error("unexpected probe")
        }

        assertFailsWith<CancellationException> {
            StreamtapeCatalogResolver(http).resolve(
                EpisodeSource("streamtape", 1, 1, pageUrl, type = SourceType.RESOLVER)
            )
        }
    }

    private class FakeHttp(
        private val pageResponse: StreamixHttpResponse,
        private val probeResponse: StreamixHttpProbeResponse
    ) : StreamixHttp {
        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            referer: String?,
            cookies: Map<String, String>,
            timeoutMs: Long
        ) = pageResponse

        override suspend fun post(
            url: String,
            body: String,
            headers: Map<String, String>,
            referer: String?,
            cookies: Map<String, String>,
            timeoutMs: Long
        ) = error("unexpected POST")

        override suspend fun probe(
            url: String,
            headers: Map<String, String>,
            referer: String?,
            cookies: Map<String, String>,
            timeoutMs: Long,
            maxBytes: Long
        ) = probeResponse
    }
}
