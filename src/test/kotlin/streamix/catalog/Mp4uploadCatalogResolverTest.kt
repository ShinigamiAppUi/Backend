package streamix.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import streamix.core.StreamixHttp
import streamix.core.StreamixHttpResponse

class Mp4uploadCatalogResolverTest {
    @Test
    fun resolvesPackedScriptAndPreservesMetadata() = runBlocking {
        val pageUrl = "https://mp4upload.com/abc123"
        val packed = "eval(function(p,a,c,k,e,r){return p}('0:\"1\";HEIGHT=1080',2,2,'src|https://cdn.example/video.mp4'.split('|')))"
        val http = FakeHttp(mapOf(pageUrl to StreamixHttpResponse(200, pageUrl, "<script>$packed</script>")))

        val result = Mp4uploadCatalogResolver(http).resolve(
            EpisodeSource(
                id = "mp4upload",
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

        val stream = result.streams.single()
        assertEquals("https://cdn.example/video.mp4", stream.url)
        assertEquals("mp4upload-catalog", stream.providerId)
        assertEquals(1080, stream.quality)
        assertEquals("id", stream.language)
        assertEquals("id", stream.subtitleLanguage)
        assertEquals("Shinigami", stream.headers["User-Agent"])
        assertEquals("https://anime.example/", stream.referer)
    }

    @Test
    fun fallsBackToPlayerSrc() = runBlocking {
        val pageUrl = "https://mp4upload.com/abc123"
        val page = """<script>player.src("https://cdn.example/video.mp4");</script>"""
        val http = FakeHttp(mapOf(pageUrl to StreamixHttpResponse(200, pageUrl, page)))

        val result = Mp4uploadCatalogResolver(http).resolve(
            EpisodeSource("mp4upload", 1, 1, pageUrl, type = SourceType.RESOLVER)
        )

        assertEquals("https://cdn.example/video.mp4", result.streams.single().url)
    }

    @Test
    fun resolvesRelativeMediaUrlAgainstFinalPage() = runBlocking {
        val sourceUrl = "https://mp4upload.com/abc123"
        val finalPageUrl = "https://cdn.mp4upload.com/player/example/"
        val page = """<script>player.src("media/video.mp4");</script>"""
        val http = FakeHttp(mapOf(sourceUrl to StreamixHttpResponse(200, finalPageUrl, page)))

        val result = Mp4uploadCatalogResolver(http).resolve(
            EpisodeSource("mp4upload", 1, 1, sourceUrl, type = SourceType.RESOLVER)
        )

        assertEquals(
            "https://cdn.mp4upload.com/player/example/media/video.mp4",
            result.streams.single().url
        )
        assertEquals(finalPageUrl, result.finalUrl)
    }

    @Test
    fun failsClosedWhenMediaScriptIsMissing() = runBlocking {
        val pageUrl = "https://mp4upload.com/abc123"
        val http = FakeHttp(mapOf(pageUrl to StreamixHttpResponse(200, pageUrl, "<html>empty</html>")))

        val result = Mp4uploadCatalogResolver(http).resolve(
            EpisodeSource("mp4upload", 1, 1, pageUrl, type = SourceType.RESOLVER)
        )

        assertTrue(result.streams.isEmpty())
        assertEquals("MP4Upload media script not found", result.failureReason)
    }

    @Test
    fun failsClosedOnHttpError() = runBlocking {
        val pageUrl = "https://mp4upload.com/abc123"
        val http = FakeHttp(mapOf(pageUrl to StreamixHttpResponse(403, pageUrl, "")))

        val result = Mp4uploadCatalogResolver(http).resolve(
            EpisodeSource("mp4upload", 1, 1, pageUrl, type = SourceType.RESOLVER)
        )

        assertTrue(result.streams.isEmpty())
        assertEquals(403, result.httpStatus)
    }

    @Test
    fun failsClosedWhenMediaUrlIsNotHttp() = runBlocking {
        val pageUrl = "https://mp4upload.com/abc123"
        val page = """<script>player.src("javascript:alert(1)");</script>"""
        val http = FakeHttp(mapOf(pageUrl to StreamixHttpResponse(200, pageUrl, page)))

        val result = Mp4uploadCatalogResolver(http).resolve(
            EpisodeSource("mp4upload", 1, 1, pageUrl, type = SourceType.RESOLVER)
        )

        assertTrue(result.streams.isEmpty())
        assertEquals("MP4Upload media URL is not HTTP(S)", result.failureReason)
    }

    @Test
    fun propagatesCancellation() = runBlocking {
        val pageUrl = "https://mp4upload.com/abc123"
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
            Mp4uploadCatalogResolver(http).resolve(
                EpisodeSource("mp4upload", 1, 1, pageUrl, type = SourceType.RESOLVER)
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
        ) = responses[url] ?: error("unexpected GET: " + url)

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
