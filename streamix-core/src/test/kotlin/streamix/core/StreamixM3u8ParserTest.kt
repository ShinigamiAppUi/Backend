package streamix.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamixM3u8ParserTest {
    @Test
    fun parsesMasterVariantsAndResolvesRelativeUrls() {
        val parser = StreamixM3u8Parser()
        val result = parser.parseMaster(
            text = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=640x360
                360/index.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1280x720
                /video/720/index.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=7000000,RESOLUTION=1920x1080
                https://cdn.example.com/1080/index.m3u8
            """.trimIndent(),
            baseUrl = "https://media.example.com/master.m3u8",
            providerId = "jwplayer",
            referer = "https://media.example.com/embed"
        )

        assertEquals(3, result.size)
        assertEquals(360, result[0].quality)
        assertEquals("https://media.example.com/360/index.m3u8", result[0].url)
        assertEquals(720, result[1].quality)
        assertEquals(1080, result[2].quality)
        assertTrue(result.all { it.type == "M3U8" })
        assertTrue(result.all { it.referer == "https://media.example.com/embed" })
    }

    @Test
    fun followsRfc3986RelativeResolution() {
        val parser = StreamixM3u8Parser()
        val result = parser.parseMaster(
            text = """
                #EXTM3U
                #EXT-X-STREAM-INF:RESOLUTION=1280x720
                ../video/720/index.m3u8
                #EXT-X-STREAM-INF:RESOLUTION=640x360
                //cdn.example.com/360/index.m3u8
            """.trimIndent(),
            baseUrl = "https://media.example.com/hls/master.m3u8",
            providerId = "jwplayer"
        )

        assertEquals(
            "https://media.example.com/video/720/index.m3u8",
            result[0].url
        )
        assertEquals(
            "https://cdn.example.com/360/index.m3u8",
            result[1].url
        )
    }

    @Test
    fun filtersIframeOnlyTrickPlayVariants() {
        val parser = StreamixM3u8Parser()
        val result = parser.parseMaster(
            text = """
                #EXTM3U
                #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=100000,RESOLUTION=1920x1080,URI="thumbs.m3u8"
                #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1280x720
                720/index.m3u8
            """.trimIndent(),
            baseUrl = "https://media.example.com/master.m3u8",
            providerId = "jwplayer"
        )

        assertEquals(1, result.size)
        assertEquals(720, result.single().quality)
    }

    @Test
    fun keepsUnknownQualityWhenResolutionIsMissing() {
        val parser = StreamixM3u8Parser()
        val result = parser.parseMaster(
            text = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=7000000,CODECS="avc1.640028,mp4a.40.2"
                1080/index.m3u8
            """.trimIndent(),
            baseUrl = "https://media.example.com/master.m3u8",
            providerId = "jwplayer"
        )

        assertEquals(1, result.size)
        assertNull(result.single().quality)
    }

    @Test
    fun returnsEmptyForMediaPlaylist() {
        val parser = StreamixM3u8Parser()
        val result = parser.parseMaster(
            "#EXTM3U\n#EXTINF:6,\nsegment.ts",
            "https://media.example.com/video.m3u8",
            "jwplayer"
        )

        assertTrue(result.isEmpty())
    }
}
