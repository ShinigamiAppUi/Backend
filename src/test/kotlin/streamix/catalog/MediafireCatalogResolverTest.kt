package streamix.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import streamix.api.StreamRef
import streamix.core.StreamixHttp
import streamix.core.StreamixHttpResponse

class MediafireCatalogResolverTest {
    @Test
    fun resolvesDownloadButtonAndPreservesMetadata() = runBlocking {
        val pageUrl = "https://www.mediafire.com/file/abc123/video"
        val downloadUrl = "https://download.mediafire.com/abc123/video.mp4"
        val html = """
            <div class="dl-btn-label">Anime Episode 1080p</div>
            <a id="downloadButton" href="$downloadUrl">Download</a>
        """.trimIndent()

        val http = FakeHttp(mapOf(pageUrl to StreamixHttpResponse(200, pageUrl, html)))
        val result = MediafireCatalogResolver(http).resolve(
            EpisodeSource(
                "mediafire", 1, 1, pageUrl,
                type = SourceType.RESOLVER,
                language = "id",
                subtitleLanguage = "id",
                headers = mapOf("User-Agent" to "Shinigami"),
                referer = "https://www.mediafire.com/"
            )
        )

        assertEquals(listOf(downloadUrl), result.streams.map(StreamRef::url))
        assertEquals(1080, result.streams.single().quality)
        assertEquals("mediafire-catalog", result.streams.single().providerId)
        assertEquals("Shinigami", result.streams.single().headers["User-Agent"])
        assertEquals("https://www.mediafire.com/", result.streams.single().referer)
    }

    @Test
    fun resolvesRelativeDownloadUrl() = runBlocking {
        val pageUrl = "https://www.mediafire.com/file/abc123/video"
        val html = """<a id="downloadButton" href="/download/file.mp4">Download</a>"""
        val http = FakeHttp(mapOf(pageUrl to StreamixHttpResponse(200, pageUrl, html)))

        val result = MediafireCatalogResolver(http).resolve(
            EpisodeSource("mediafire", 1, 1, pageUrl, type = SourceType.RESOLVER)
        )

        assertEquals(
            "https://www.mediafire.com/download/file.mp4",
            result.streams.single().url
        )
    }

    @Test
    fun failsClosedWhenDownloadButtonIsMissing() = runBlocking {
        val pageUrl = "https://www.mediafire.com/file/abc123/video"
        val http = FakeHttp(
            mapOf(pageUrl to StreamixHttpResponse(200, pageUrl, "<html>no download</html>"))
        )

        val result = MediafireCatalogResolver(http).resolve(
            EpisodeSource("mediafire", 1, 1, pageUrl, type = SourceType.RESOLVER)
        )

        assertTrue(result.streams.isEmpty())
        assertEquals("MediaFire download link not found", result.failureReason)
    }

    @Test
    fun failsClosedOnHttpError() = runBlocking {
        val pageUrl = "https://www.mediafire.com/file/abc123/video"
        val http = FakeHttp(mapOf(pageUrl to StreamixHttpResponse(403, pageUrl, "")))

        val result = MediafireCatalogResolver(http).resolve(
            EpisodeSource("mediafire", 1, 1, pageUrl, type = SourceType.RESOLVER)
        )

        assertTrue(result.streams.isEmpty())
        assertEquals(403, result.httpStatus)
        assertEquals("HTTP 403", result.failureReason)
    }

    @Test
    fun propagatesCancellation() = runBlocking {
        val pageUrl = "https://www.mediafire.com/file/abc123/video"
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
            MediafireCatalogResolver(http).resolve(
                EpisodeSource("mediafire", 1, 1, pageUrl, type = SourceType.RESOLVER)
            )
        }
    }

    private class FakeHttp(
        private val responses: Map<String, StreamixHttpResponse>
    ) : StreamixHttp {
        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            referer: String?,
            cookies: Map<String, String>,
            timeoutMs: Long
        ) = responses[url] ?: error("unexpected GET: $url")

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
}
