package streamix.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import streamix.core.StreamixHttp
import streamix.core.StreamixHttpProbeResponse
import streamix.core.StreamixHttpResponse

class RuntimeProviderExtractorTest {
    @Test
    fun registryContainsAllApprovedRuntimeProviders() {
        val ids = RuntimeProviderExtractorRegistry(FakeHttp()).all().map { it.providerId }
        assertEquals(
            listOf("animasu", "anisail", "anoboy", "otakudesu", "samehadaku", "oploverz", "animeindo", "gomunime", "sokuja", "nimegami", "alqanime"),
            ids
        )
    }

    @Test
    fun animasuExtractorDecodesMirrorIframe() = runBlocking {
        val media = "https://blogger.com/video.g?token=test"
        val encoded = java.util.Base64.getEncoder().encodeToString(
            """<iframe src="$media"></iframe>""".toByteArray()
        )
        val extractor = AnimasuRuntimeExtractor(
            FakeHttp("""<select class="mirror"><option value="$encoded">720p</option></select>""")
        )

        val result = extractor.extract("https://animasu.love/episode/example-1/")
        assertEquals(listOf(media), result.map { it.url })
    }

    @Test
    fun animasuExtractorReadsExternalMirrorOptionDirectly() = runBlocking {
        val player = "https://www.blogger.com/video.g?token=animasu"
        val extractor = AnimasuRuntimeExtractor(
            FakeHttp("""<select class="mirror"><option value="$player">480p</option></select><div id="video"></div>""")
        )

        val result = extractor.extract("https://animasu.love/nonton/example/episode-1/#video")
        assertEquals(listOf(player), result.map { it.url })
    }

    @Test
    fun animasuExtractorKeepsEveryMirrorResolution() = runBlocking {
        val low = "https://www.blogger.com/video.g?token=360"
        val high = "https://terabox.com/sharing/embed?token=720"
        val extractor = AnimasuRuntimeExtractor(
            FakeHttp("""
                <select class="mirror">
                  <option value="$low">360p</option>
                  <option value="$high">720p</option>
                </select>
            """.trimIndent())
        )

        val result = extractor.extract("https://animasu.love/nonton/example/episode-1/")
        assertEquals(listOf(low, high), result.map { it.url })
    }

    @Test
    fun animasuExtractorDoesNotPromoteOwnVideoSurface() = runBlocking {
        val self = "https://animasu.love/nonton/example/episode-1/#video"
        val extractor = AnimasuRuntimeExtractor(
            FakeHttp("""<select class="mirror"><option value="$self">480p</option></select><iframe src="$self"></iframe>""")
        )

        val result = extractor.extract(self)
        assertTrue(result.none { it.url == self })
    }

    @Test
    fun samehadakuExtractorReadsPlayerAjaxIframe() = runBlocking {
        val embed = "https://blogger.com/video.g?token=samehadaku"
        val body = """
            <div id="server">
              <ul>
                <li><div data-post="123" data-nume="1" data-type="mp4"></div></li>
              </ul>
            </div>
            <iframe src="$embed"></iframe>
        """.trimIndent()
        val extractor = SamehadakuRuntimeExtractor(FakeHttp(body))

        val result = extractor.extract("https://v2.samehadaku.how/one-piece-episode-1/")
        assertEquals(listOf(embed), result.map { it.url })
    }

    @Test
    fun providerExtractorsReturnEmptyOnHttpFailure() = runBlocking {
        val extractor = GomunimeRuntimeExtractor(FakeHttp("", 503))
        assertTrue(extractor.extract("https://gomunime.top/one-piece-episode-1").isEmpty())
    }


    @Test
    fun sokujaSiteUsesJsonSearchAndEmbeddedEpisodeMetadata() = runBlocking {
        val animeUrl = "https://x6.sokuja.uk/anime/one-piece/"
        val episodePage = "https://x6.sokuja.uk/one-piece-episode-1/?__shinigami_ep=1&__shinigami_episode_id=42"
        val http = RoutingFakeHttp(mapOf(
            "https://x6.sokuja.uk/api/search?q=One+Piece" to StreamixHttpResponse(
                200, "https://x6.sokuja.uk/api/search?q=One+Piece",
                """{"results":[{"slug":"one-piece","title":"One Piece"}]}"""
            ),
            "https://x6.sokuja.uk/?s=One+Piece" to StreamixHttpResponse(
                200, "https://x6.sokuja.uk/?s=One+Piece", ""
            ),
            animeUrl to StreamixHttpResponse(
                200, animeUrl,
                """<script>const episodes = [{"id":42,"slug":"one-piece-episode-1","title":"Episode 1","episodeNumber":1}]</script>"""
            ),
            episodePage to StreamixHttpResponse(200, episodePage, "<html>episode</html>")
        ))
        val site = defaultSites(http).first { it.id == "sokuja" }
        assertEquals(episodePage, site.findEpisodePage("One Piece", 1))
    }

    @Test
    fun sokujaExtractorUsesEpisodeIdAndMirrorApi() = runBlocking {
        val pageUrl = "https://x6.sokuja.uk/one-piece-episode-1/"
        val embed = "https://vidhidepro.com/v/episode"
        val http = RoutingFakeHttp(mapOf(
            pageUrl to StreamixHttpResponse(200, pageUrl, """{"episodeId":42}"""),
            "https://x6.sokuja.uk/api/video-mirrors?e=42" to StreamixHttpResponse(
                200, "https://x6.sokuja.uk/api/video-mirrors?e=42",
                """{"mirrors":[{"embedUrl":"$embed","serverName":"VidHide","quality":"720p"}]}"""
            )
        ))
        assertEquals(listOf(embed), SokujaRuntimeExtractor(http).extract(pageUrl).map { it.url })
    }

    @Test
    fun nimegamiExtractorReturnsKnownExternalDownloadHost() = runBlocking {
        val host = "https://krakenfiles.com/view/example"
        val extractor = NimegamiRuntimeExtractor(FakeHttp("""<div class="download-row"><a href="$host">KrakenFiles</a></div>"""))
        assertEquals(listOf(host), extractor.extract("https://nimegami.id/anime/example/").map { it.url })
    }

    @Test
    fun alqanimeExtractorReturnsProviderMirror() = runBlocking {
        val host = "https://pixeldrain.com/u/example"
        val extractor = AlqanimeRuntimeExtractor(FakeHttp("""<div class="slink"><a href="$host">PixelDrain</a></div>"""))
        assertEquals(listOf(host), extractor.extract("https://alqanime.net/one-piece-episode-1/").map { it.url })
    }

    @Test
    fun alqanimeExtractorScopesMirrorsToRequestedEpisode() = runBlocking {
        val episodeOneHost = "https://pixeldrain.com/u/episode-one"
        val episodeTwoHost = "https://pixeldrain.com/u/episode-two"
        val html = """
            <div class="sorattl collapsible"><h3>Episode 1</h3></div>
            <div class="content"><table><tr><td><div class="slink"><a href="$episodeOneHost">PixelDrain 720p</a></div></td></tr></table></div>
            <div class="sorattl collapsible"><h3>Episode 2</h3></div>
            <div class="content"><table><tr><td><div class="slink"><a href="$episodeTwoHost">PixelDrain 720p</a></div></td></tr></table></div>
        """.trimIndent()
        val extractor = AlqanimeRuntimeExtractor(FakeHttp(html))
        val result = extractor.extract("https://alqanime.net/one-piece/?__shinigami_ep=2").map { it.url }
        assertTrue(episodeTwoHost in result)
        assertTrue(episodeOneHost !in result)
    }

    @Test
    fun alqanimeResolvesOnlyRequestedEpisodeFromPixelDrainFolder() = runBlocking {
        val pageUrl = "https://alqanime.net/one-piece/"
        val listUrl = "https://pixeldrain.com/api/list/folder42"
        val html = """
            <div class="sorattl collapsible"><h3>Episode 1</h3></div>
            <div class="content"><a href="https://pixeldrain.com/l/folder42">PixelDrain</a></div>
        """.trimIndent()
        val http = RoutingFakeHttp(mapOf(
            pageUrl to StreamixHttpResponse(200, pageUrl, html),
            listUrl to StreamixHttpResponse(
                200, listUrl,
                """{"files":[
                    {"id":"file1","name":"One_Piece_001_360p.mp4","mime_type":"video/mp4"},
                    {"id":"file2","name":"One_Piece_002_720p.mp4","mime_type":"video/mp4"}
                ]}"""
            )
        ))
        val result = AlqanimeRuntimeExtractor(http)
            .extract(pageUrl + "?__shinigami_ep=1").map { it.url }
        assertTrue("https://pixeldrain.com/api/file/file1" in result)
        assertTrue("https://pixeldrain.com/api/file/file2" !in result)
    }


    @Test
    fun nimegamiSiteUsesSourcePinnedDomainAndSearchRoute() = runBlocking {
        val searchUrl = "https://nimegami.id/anime/?q=One+Piece"
        val animeUrl = "https://nimegami.id/anime/one-piece/"
        val episodeUrl = "https://nimegami.id/anime/one-piece/?__shinigami_ep=1"
        val http = RoutingFakeHttp(mapOf(
            searchUrl to StreamixHttpResponse(
                200, searchUrl,
                """<article class="anime-card"><a href="$animeUrl">One Piece</a></article>"""
            ),
            animeUrl to StreamixHttpResponse(200, animeUrl, "<h1>One Piece</h1>"),
            episodeUrl to StreamixHttpResponse(200, episodeUrl, "<h1>One Piece Episode 1</h1>")
        ))

        val site = defaultSites(http).first { it.id == "nimegami" }
        assertEquals(episodeUrl, site.findEpisodePage("One Piece", 1))
    }

    @Test
    fun alqanimeSiteUsesSourcePinnedDomainAndSearchRoute() = runBlocking {
        val searchUrl = "https://alqanime.net/?s=One+Piece"
        val animeUrl = "https://alqanime.net/one-piece/"
        val episodeUrl = "https://alqanime.net/one-piece/?__shinigami_ep=1"
        val http = RoutingFakeHttp(mapOf(
            searchUrl to StreamixHttpResponse(
                200, searchUrl,
                """<article class="bs"><a href="$animeUrl"><h2>One Piece</h2></a></article>"""
            ),
            animeUrl to StreamixHttpResponse(200, animeUrl, "<h1>One Piece</h1>"),
            episodeUrl to StreamixHttpResponse(200, episodeUrl, "<h1>One Piece Episode 1</h1>")
        ))

        val site = defaultSites(http).first { it.id == "alqanime" }
        assertEquals(episodeUrl, site.findEpisodePage("One Piece", 1))
    }

    private class RoutingFakeHttp(
        private val responses: Map<String, StreamixHttpResponse>
    ) : StreamixHttp {
        override suspend fun get(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long) =
            responses[url] ?: StreamixHttpResponse(404, url, "")
        override suspend fun post(url: String, body: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long) =
            StreamixHttpResponse(404, url, "")
        override suspend fun probe(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long, maxBytes: Long) =
            StreamixHttpProbeResponse(404, url, 0L, true)
    }

    private class FakeHttp(
        private val body: String = "",
        private val status: Int = 200
    ) : StreamixHttp {
        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            referer: String?,
            cookies: Map<String, String>,
            timeoutMs: Long
        ) = StreamixHttpResponse(status, url, body)

        override suspend fun post(
            url: String,
            body: String,
            headers: Map<String, String>,
            referer: String?,
            cookies: Map<String, String>,
            timeoutMs: Long
        ) = StreamixHttpResponse(status, url, this.body)

        override suspend fun probe(
            url: String,
            headers: Map<String, String>,
            referer: String?,
            cookies: Map<String, String>,
            timeoutMs: Long,
            maxBytes: Long
        ) = StreamixHttpProbeResponse(status, url, 0L, true)
    }
}
