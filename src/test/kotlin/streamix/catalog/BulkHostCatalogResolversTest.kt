package streamix.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import streamix.core.StreamixHttp
import streamix.core.StreamixHttpResponse

class BulkHostCatalogResolversTest {
    private fun source(url: String, id: String = "x") = EpisodeSource(id, 1L, 1, url, type = SourceType.RESOLVER, quality = 1080, language = "id", subtitleLanguage = "id")

    @Test
    fun registryContainsAllBulkHosts() {
        val ids = BuiltInCatalogResolvers.create(
            streamix.core.StreamixExtractorRegistry(com.baseprovider.streamix.ProviderExtractorsNative.all())
        ).all().map { it.id }.toSet()
        assertTrue(setOf(
            "streamwish-catalog","filelions-catalog","vidhide-catalog","vidland-catalog","vidmoly-catalog",
            "vido-catalog","vudeo-catalog","vk-catalog","streamdav-catalog","streamsilk-catalog",
            "streamup-catalog","streamlare-catalog","streamplay-catalog","kwik-catalog","kodik-catalog"
        ).all(ids::contains))
    }

    @Test
    fun pageResolversProduceTerminalStreams() = runBlocking {
        val cases = listOf(
            Triple(StreamWishCatalogResolver::class, "https://streamwish.com/e/x", "<script>https://cdn.example/x.m3u8</script>"),
            Triple(FileLionsCatalogResolver::class, "https://filelions.to/e/x", "<script>https://cdn.example/x.m3u8</script>"),
            Triple(VidHideCatalogResolver::class, "https://vidhide.com/e/x", "<script>https://cdn.example/x.m3u8</script>"),
            Triple(VidLandCatalogResolver::class, "https://vidland.net/e/x", "<script>var x={hls3:\"https://cdn.example/x.m3u8\"}</script>"),
            Triple(VidMolyCatalogResolver::class, "https://vidmoly.biz/e/x", "<script>sources:[{file:\"https://cdn.example/x.m3u8\"}]</script>"),
            Triple(VidoCatalogResolver::class, "https://vido.lol/e/x", "<script>master|abc123|</script>"),
            Triple(VudeoCatalogResolver::class, "https://vudeo.net/e/x", "<script>sources: [\"https://cdn.example/x.mp4\"]</script>"),
            Triple(StreamDavCatalogResolver::class, "https://streamdav.com/e/x", "<video><source src=\"https://cdn.example/x.mp4\" label=\"1080p\"></video>"),
            Triple(StreamSilkCatalogResolver::class, "https://streamsilk.com/e/x", "<script>var urlPlay = \"https://cdn.example/x.m3u8\"</script>"),
            Triple(StreamUpCatalogResolver::class, "https://streamup.ws/e/x", "<script>var x='https://cdn.example/x.m3u8'</script>"),
            Triple(KwikCatalogResolver::class, "https://kwik.cx/e/x", "<script>https://cdn.example/x.m3u8</script>"),
            Triple(KodikCatalogResolver::class, "https://kodikplayer.com/e/x", "<script>https://cdn.example/x.m3u8</script>")
        )
        for ((type, url, body) in cases) {
            val fake = Fake(StreamixHttpResponse(200, url, body))
            val resolver = when (type) {
                StreamWishCatalogResolver::class -> StreamWishCatalogResolver(fake)
                FileLionsCatalogResolver::class -> FileLionsCatalogResolver(fake)
                VidHideCatalogResolver::class -> VidHideCatalogResolver(fake)
                VidLandCatalogResolver::class -> VidLandCatalogResolver(fake)
                VidMolyCatalogResolver::class -> VidMolyCatalogResolver(fake)
                VidoCatalogResolver::class -> VidoCatalogResolver(fake)
                VudeoCatalogResolver::class -> VudeoCatalogResolver(fake)
                StreamDavCatalogResolver::class -> StreamDavCatalogResolver(fake)
                StreamSilkCatalogResolver::class -> StreamSilkCatalogResolver(fake)
                StreamUpCatalogResolver::class -> StreamUpCatalogResolver(fake)
                StreamWishCatalogResolver::class -> StreamWishCatalogResolver(fake)
                StreamPlayCatalogResolver::class -> StreamPlayCatalogResolver(fake)
                StreamLareCatalogResolver::class -> StreamLareCatalogResolver(fake)
                KwikCatalogResolver::class -> KwikCatalogResolver(fake)
                KodikCatalogResolver::class -> KodikCatalogResolver(fake)
                else -> error("unhandled resolver: $type")
            }
            val result = resolver.resolve(source(url))
            assertEquals(1, result.streams.size, type.toString())
        }
    }

    @Test
    fun pageResolverDoesNotParseRedirectOrNotModifiedResponseAsMediaPage() = runBlocking {
        val url = "https://streamwish.com/e/redirect"
        val fake = Fake(StreamixHttpResponse(302, url, """<script>https://cdn.example/unsafe-fallback.m3u8</script>"""))
        val result = StreamWishCatalogResolver(fake).resolve(source(url))
        assertTrue(result.streams.isEmpty())
        assertEquals(302, result.httpStatus)
        assertEquals("HTTP 302", result.failureReason)
    }

    @Test
    fun apiResolversProduceTerminalStreams() = runBlocking {
        val vkUrl = "https://vk.com/video-123_456"
        val vk = VkCatalogResolver(
            RoutingFake(
                getResponse = StreamixHttpResponse(200, vkUrl, ""),
                postResponse = StreamixHttpResponse(200, "https://vk.com/al_video.php", """{"url_1080":"https://cdn.example/vk1080.mp4","mp4_720":"https://cdn.example/vk720.mp4"}""")
            )
        )
        assertEquals(2, vk.resolve(source(vkUrl)).streams.size)

        val lUrl = "https://streamlare.com/e/abc"
        val l = StreamLareCatalogResolver(
            RoutingFake(
                getResponse = StreamixHttpResponse(200, lUrl, ""),
                postResponse = StreamixHttpResponse(200, "https://slwatch.co/api/video/stream/get", """{"file":"https://cdn.example/lare.m3u8"}""")
            )
        )
        assertEquals(1, l.resolve(source(lUrl)).streams.size)

        val pUrl = "https://streamplay.co.in/e/abc"
        val p = StreamPlayCatalogResolver(
            RoutingFake(
                getResponse = StreamixHttpResponse(200, pUrl, """<script>window.kaken = "token";</script>"""),
                postResponse = StreamixHttpResponse(200, "https://play.streamplay.co.in/api/", """{"sources":[{"file":"https://cdn.example/master.txt","label":"1080p","type":"hls"}]}""")
            )
        )
        val pResult = p.resolve(source(pUrl))
        assertEquals(1, pResult.streams.size)
        assertTrue(pResult.streams.single().url.endsWith("master.m3u8"))

        val kUrl = "https://kodikplayer.com/seria/1/abc/720p"
        val k = KodikCatalogResolver(
            KodikRoutingFake(
                page = StreamixHttpResponse(
                    200,
                    kUrl,
                    """<script>var urlParams='{"d":"d","d_sign":"ds","pd":"","pd_sign":"","ref":"","ref_sign":""}'; var v={type:"seria",id:"1",hash:"abc"};</script><script src="https://kodikplayer.com/app.js"></script>"""
                ),
                post = StreamixHttpResponse(
                    200,
                    "https://kodikplayer.com/ftor",
                    """{"links":{"720":[{"src":"aHR0cHM6Ly9jZG4uZXhhbXBsZS94Lm1wNA=="}]}}"""
                ),
                script = StreamixHttpResponse(200, "https://kodikplayer.com/app.js", "atob(t)")
            )
        )
        val kResult = k.resolve(source(kUrl))
        assertEquals(1, kResult.streams.size, kResult.failureReason ?: "kodik failure")
        assertEquals("https://cdn.example/x.mp4", kResult.streams.single().url)
    }

    private class RoutingFake(
        private val getResponse: StreamixHttpResponse,
        private val postResponse: StreamixHttpResponse
    ) : StreamixHttp {
        override suspend fun get(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long) = getResponse
        override suspend fun post(url: String, body: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long) = postResponse
        override suspend fun probe(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long, maxBytes: Long) = error("unexpected probe")
    }

    private class KodikRoutingFake(
        private val page: StreamixHttpResponse,
        private val post: StreamixHttpResponse,
        private val script: StreamixHttpResponse
    ) : StreamixHttp {
        override suspend fun get(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long) =
            when {
                url.endsWith("/app.js") -> script
                else -> page
            }
        override suspend fun post(url: String, body: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long) = post
        override suspend fun probe(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long, maxBytes: Long) = error("unexpected probe")
    }

    private class Fake(private val response: StreamixHttpResponse) : StreamixHttp {
        override suspend fun get(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long) = response
        override suspend fun post(url: String, body: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long) = error("unexpected POST")
        override suspend fun probe(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long, maxBytes: Long) = error("unexpected probe")
    }
}
