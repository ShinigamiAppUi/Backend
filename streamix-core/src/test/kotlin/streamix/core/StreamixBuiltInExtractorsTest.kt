package streamix.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StreamixBuiltInExtractorsTest {
    @Test
    fun jwPlayerRoutesKnownCustomDomains() {
        val registry = StreamixBuiltInExtractors.create(FakeHttp())
        val router = StreamixExtractorRouter(registry)

        assertEquals("jwplayer", router.resolve("https://desustream.me/moedesu/abc")?.id)
        assertEquals("jwplayer", router.resolve("https://desustream.info/dstream/updesu/abc")?.id)
        assertEquals("jwplayer", router.resolve("https://desustream.net/dstream/odcdn/?id=abc")?.id)
    }

    @Test
    fun catalogPageExtractorRoutesOtakudesuAndAnimasu() {
        val registry = StreamixBuiltInExtractors.create(FakeHttp())
        val router = StreamixExtractorRouter(registry)

        assertEquals("otaku-animasu-page", router.resolve("https://otakudesu.blog/episode/example-episode-1-sub-indo/")?.id)
        assertEquals("otaku-animasu-page", router.resolve("https://animasu.love/episode/example-1/")?.id)
    }

    @Test
    fun animasuMirrorOptionCanYieldPlayableMedia() = runBlocking {
        val media = "https://cdn.example.test/anime/episode-1-720p.mp4"
        val encoded = java.util.Base64.getEncoder().encodeToString(
            "<iframe src=\"$media\"></iframe>".toByteArray()
        )
        val http = FakeHttp(
            getText = """
                <html><select class="mirror"><option value="$encoded">720p</option></select></html>
            """.trimIndent()
        )
        val registry = StreamixBuiltInExtractors.create(http)
        val result = registry.get("otaku-animasu-page")!!.extract(
            StreamixExtractorRequest("https://animasu.love/episode/example-1/")
        )

        assertEquals(listOf(media), result.streams.map { it.url })
    }

    @Test
    fun jwPlayerDoesNotBecomeGlobalHostnameFallback() {
        val registry = StreamixBuiltInExtractors.create(FakeHttp())
        val router = StreamixExtractorRouter(registry)

        assertNull(router.resolve("https://example.com/video"))
    }

    private class FakeHttp(
        private val getText: String = "",
    ) : StreamixHttp {
        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            referer: String?,
            cookies: Map<String, String>,
            timeoutMs: Long
        ) = StreamixHttpResponse(200, url, getText)

        override suspend fun post(
            url: String,
            body: String,
            headers: Map<String, String>,
            referer: String?,
            cookies: Map<String, String>,
            timeoutMs: Long
        ) = StreamixHttpResponse(200, url, "")

        override suspend fun probe(
            url: String,
            headers: Map<String, String>,
            referer: String?,
            cookies: Map<String, String>,
            timeoutMs: Long,
            maxBytes: Long
        ) = StreamixHttpProbeResponse(200, url, 0L, true)
    }
}
