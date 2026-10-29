package streamix.core

import kotlinx.coroutines.test.runTest
import streamix.core.extractors.JwPlayerExtractor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JwPlayerExtractorTest {
    private val extractor = JwPlayerExtractor(FakeHttp)

    @Test
    fun parsesMp4AndSubtitle() = runTest {
        val result = extractor.parseScript(
            script = """jwplayer("vplayer").setup({
                sources: [{"file":"https://cdn.example/video.mp4","label":"720p","type":"video/mp4"}],
                tracks: [{"file":"/sub/en.vtt","kind":"captions","label":"English"}]
            })""",
            baseUrl = "https://player.example/embed/abc",
            request = StreamixExtractorRequest(url = "https://player.example/embed/abc", providerId = "otakudesu")
        )

        assertEquals(1, result.streams.size)
        assertEquals("https://cdn.example/video.mp4", result.streams.single().url)
        assertEquals(720, result.streams.single().quality)
        assertEquals("otakudesu", result.streams.single().providerId)
        assertEquals("English", result.subtitles.single().language)
        assertTrue(result.subtitles.single().url.endsWith("/sub/en.vtt"))
    }

    @Test
    fun parsesSingleFileSetupSource() = runTest {
        val result = extractor.parseScript(
            script = """jwplayer("player").setup({file:"https://cdn.example.test/video.mp4", label:"720p", type:"video/mp4"});""",
            baseUrl = "https://desustream.net/dstream/odcdn/?id=example",
            request = StreamixExtractorRequest(
                url = "https://desustream.net/dstream/odcdn/?id=example",
                providerId = "otakudesu"
            )
        )

        assertEquals(1, result.streams.size)
        assertEquals("https://cdn.example.test/video.mp4", result.streams.single().url)
        assertEquals(720, result.streams.single().quality)
        assertEquals("VIDEO", result.streams.single().type)
    }

    @Test
    fun parsesM3u8Fallback() = runTest {
        val result = extractor.parseScript(
            script = """var links = { "hls": "https://cdn.example/master.m3u8" };""",
            baseUrl = "https://player.example/embed/abc",
            request = StreamixExtractorRequest(url = "https://player.example/embed/abc", providerId = "otakudesu")
        )

        assertEquals("M3U8", result.streams.single().type)
    }

    @Test
    fun parsesSourcesWhenFieldsAreReordered() = runTest {
        val result = extractor.parseScript(
            script = """jwplayer("vplayer").setup({
                sources: [{"type":"video/mp4","label":"1080p","file":"https://cdn.example/video.mp4"}]
            })""",
            baseUrl = "https://player.example/embed/abc",
            request = StreamixExtractorRequest(url = "https://player.example/embed/abc", providerId = "otakudesu")
        )

        assertEquals("https://cdn.example/video.mp4", result.streams.single().url)
        assertEquals(1080, result.streams.single().quality)
    }

    private object FakeHttp : StreamixHttp {
        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            referer: String?,
            cookies: Map<String, String>,
            timeoutMs: Long
        ) = StreamixHttpResponse(200, url, "")

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
