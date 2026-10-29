package streamix.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import streamix.core.StreamixHttp
import streamix.core.StreamixHttpResponse

class YourUploadCatalogResolverTest {
    @Test
    fun resolvesJwplayerFileAndPreservesMetadata() = runBlocking {
        val pageUrl = "https://www.yourupload.com/watch/example"
        val page = """
            <html><script>
            jwplayerOptions = { file: 'https://cdn.example/video.mp4', image: 'cover.jpg' }
            </script></html>
        """.trimIndent()
        val http = FakeHttp(mapOf(pageUrl to StreamixHttpResponse(200, pageUrl, page)))

        val result = YourUploadCatalogResolver(http).resolve(
            EpisodeSource(
                id = "yourupload",
                anilistId = 1,
                episodeNumber = 1,
                sourceUrl = pageUrl,
                type = SourceType.RESOLVER,
                quality = 720,
                language = "id",
                subtitleLanguage = "id",
                headers = mapOf("User-Agent" to "Shinigami"),
                referer = "https://anime.example/"
            )
        )

        assertEquals(1, result.streams.size)
        val stream = result.streams.single()
        assertEquals("https://cdn.example/video.mp4", stream.url)
        assertEquals("yourupload-catalog", stream.providerId)
        assertEquals(720, stream.quality)
        assertEquals("id", stream.language)
        assertEquals("id", stream.subtitleLanguage)
        assertEquals("Shinigami", stream.headers["User-Agent"])
        assertEquals("https://anime.example/", stream.referer)
    }

    @Test
    fun resolvesRelativeMediaUrlAgainstFinalPage() = runBlocking {
        val pageUrl = "https://www.yourupload.com/watch/example"
        val page = "<script>jwplayerOptions = { file: '/media/video.mp4' }</script>"
        val http = FakeHttp(mapOf(pageUrl to StreamixHttpResponse(200, pageUrl, page)))

        val result = YourUploadCatalogResolver(http).resolve(
            EpisodeSource("yourupload", 1, 1, pageUrl, type = SourceType.RESOLVER)
        )

        assertEquals("https://www.yourupload.com/media/video.mp4", result.streams.single().url)
    }

    @Test
    fun resolvesRelativeMediaUrlAgainstRedirectedFinalPage() = runBlocking {
        val sourceUrl = "https://www.yourupload.com/watch/example"
        val finalPageUrl = "https://cdn.yourupload.com/player/example/"
        val page = "<script>jwplayerOptions = { file: 'media/video.mp4' }</script>"
        val http = FakeHttp(
            mapOf(sourceUrl to StreamixHttpResponse(200, finalPageUrl, page))
        )

        val result = YourUploadCatalogResolver(http).resolve(
            EpisodeSource("yourupload", 1, 1, sourceUrl, type = SourceType.RESOLVER)
        )

        assertEquals(
            "https://cdn.yourupload.com/player/example/media/video.mp4",
            result.streams.single().url
        )
        assertEquals(finalPageUrl, result.finalUrl)
    }

    @Test
    fun resolvesProtocolRelativeMediaUrl() = runBlocking {
        val pageUrl = "https://www.yourupload.com/watch/example"
        val page = "<script>jwplayerOptions = { file: '//cdn.example/video.mp4' }</script>"
        val http = FakeHttp(mapOf(pageUrl to StreamixHttpResponse(200, pageUrl, page)))

        val result = YourUploadCatalogResolver(http).resolve(
            EpisodeSource("yourupload", 1, 1, pageUrl, type = SourceType.RESOLVER)
        )

        assertEquals("https://cdn.example/video.mp4", result.streams.single().url)
    }

    @Test
    fun failsClosedWhenMediaUrlIsNotHttp() = runBlocking {
        val pageUrl = "https://www.yourupload.com/watch/example"
        val page = "<script>jwplayerOptions = { file: 'javascript:alert(1)' }</script>"
        val http = FakeHttp(mapOf(pageUrl to StreamixHttpResponse(200, pageUrl, page)))

        val result = YourUploadCatalogResolver(http).resolve(
            EpisodeSource("yourupload", 1, 1, pageUrl, type = SourceType.RESOLVER)
        )

        assertTrue(result.streams.isEmpty())
        assertEquals("YourUpload media URL is not HTTP(S)", result.failureReason)
    }

    @Test
    fun failsClosedWhenPlayerConfigurationIsMissing() = runBlocking {
        val pageUrl = "https://www.yourupload.com/watch/example"
        val http = FakeHttp(mapOf(pageUrl to StreamixHttpResponse(200, pageUrl, "<html>empty</html>")))

        val result = YourUploadCatalogResolver(http).resolve(
            EpisodeSource("yourupload", 1, 1, pageUrl, type = SourceType.RESOLVER)
        )

        assertTrue(result.streams.isEmpty())
        assertEquals("YourUpload jwplayerOptions not found", result.failureReason)
    }

    @Test
    fun failsClosedOnHttpError() = runBlocking {
        val pageUrl = "https://www.yourupload.com/watch/example"
        val http = FakeHttp(mapOf(pageUrl to StreamixHttpResponse(403, pageUrl, "")))

        val result = YourUploadCatalogResolver(http).resolve(
            EpisodeSource("yourupload", 1, 1, pageUrl, type = SourceType.RESOLVER)
        )

        assertTrue(result.streams.isEmpty())
        assertEquals(403, result.httpStatus)
    }

    @Test
    fun propagatesCancellation() = runBlocking {
        val pageUrl = "https://www.yourupload.com/watch/example"
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
            YourUploadCatalogResolver(http).resolve(
                EpisodeSource("yourupload", 1, 1, pageUrl, type = SourceType.RESOLVER)
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
