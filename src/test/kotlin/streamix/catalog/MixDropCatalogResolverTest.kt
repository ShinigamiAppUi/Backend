package streamix.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import streamix.core.StreamixHttp
import streamix.core.StreamixHttpResponse

class MixDropCatalogResolverTest {
    @Test
    fun resolvesPackedWurlAndSubtitle() = runBlocking {
        val pageUrl = "https://mixdrop.co/e/example"
        val packed = """eval(function(p,a,c,k,e,r){return p}('0="1";2="3"',4,4,'Core.wurl|https://cdn.example/video.mp4|Core.remotesub|https%3A%2F%2Fcdn.example%2Fsub.vtt'.split('|')))"""
        val http = FakeHttp(
            mapOf(pageUrl to StreamixHttpResponse(
                200,
                pageUrl,
                """<script>MDCore eval(function(p,a,c,k,e,r){return p}('0="1";2="3"',4,4,'Core.wurl|https://cdn.example/video.mp4|Core.remotesub|https%3A%2F%2Fcdn.example%2Fsub.vtt'.split('|')))</script>"""
            ))
        )

        val result = MixDropCatalogResolver(http).resolve(
            EpisodeSource(
                id = "mixdrop",
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
        assertEquals("https://cdn.example/video.mp4", stream.url)
        assertEquals("mixdrop-catalog", stream.providerId)
        assertEquals("id", stream.language)
        assertEquals("id", stream.subtitleLanguage)
        assertEquals(
            "https://cdn.example/sub.vtt",
            stream.subtitles.single().url
        )
        assertEquals("id", stream.subtitles.single().language)
    }

    @Test
    fun resolvesProtocolRelativeMediaUrl() = runBlocking {
        val pageUrl = "https://mixdrop.co/e/example"
        val page = """<script>MDCore eval(function(p,a,c,k,e,r){return p}('0',2,1,'Core.wurl'.split('|')))</script>"""
        val http = FakeHttp(mapOf(pageUrl to StreamixHttpResponse(200, pageUrl, page)))

        val result = MixDropCatalogResolver(http).resolve(
            EpisodeSource("mixdrop", 1, 1, pageUrl, type = SourceType.RESOLVER)
        )

        assertTrue(result.streams.isEmpty())
        assertEquals("MixDrop media URL not found", result.failureReason)
    }

    @Test
    fun failsClosedWhenPackedScriptMissing() = runBlocking {
        val pageUrl = "https://mixdrop.co/e/example"
        val http = FakeHttp(mapOf(pageUrl to StreamixHttpResponse(200, pageUrl, "<html>empty</html>")))

        val result = MixDropCatalogResolver(http).resolve(
            EpisodeSource("mixdrop", 1, 1, pageUrl, type = SourceType.RESOLVER)
        )

        assertTrue(result.streams.isEmpty())
        assertEquals("MixDrop packed media script not found", result.failureReason)
    }

    @Test
    fun failsClosedOnHttpError() = runBlocking {
        val pageUrl = "https://mixdrop.co/e/example"
        val http = FakeHttp(mapOf(pageUrl to StreamixHttpResponse(403, pageUrl, "")))

        val result = MixDropCatalogResolver(http).resolve(
            EpisodeSource("mixdrop", 1, 1, pageUrl, type = SourceType.RESOLVER)
        )

        assertTrue(result.streams.isEmpty())
        assertEquals(403, result.httpStatus)
    }

    @Test
    fun failsClosedWhenMediaUrlIsNotHttp() = runBlocking {
        val pageUrl = "https://mixdrop.co/e/example"
        val page = """<script>MDCore eval(function(p,a,c,k,e,r){return p}('0',2,1,'javascript:alert(1)'.split('|')))</script>"""
        val http = FakeHttp(mapOf(pageUrl to StreamixHttpResponse(200, pageUrl, page)))

        val result = MixDropCatalogResolver(http).resolve(
            EpisodeSource("mixdrop", 1, 1, pageUrl, type = SourceType.RESOLVER)
        )

        assertTrue(result.streams.isEmpty())
        assertEquals("MixDrop media URL not found", result.failureReason)
    }

    @Test
    fun propagatesCancellation() = runBlocking {
        val pageUrl = "https://mixdrop.co/e/example"
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
            MixDropCatalogResolver(http).resolve(
                EpisodeSource("mixdrop", 1, 1, pageUrl, type = SourceType.RESOLVER)
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
