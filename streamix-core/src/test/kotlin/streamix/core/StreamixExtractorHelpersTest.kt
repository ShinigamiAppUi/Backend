package streamix.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamixExtractorHelpersTest {
    @Test
    fun universalVideoUrlsAndQualityArePureJvm() {
        val urls = StreamixExtractorPatterns.extractAllVideoUrls(
            """{"a":"https://cdn.test/video.m3u8","b":"https://cdn.test/720p.mp4"}"""
        )
        assertEquals(2, urls.size)
        assertEquals(720, StreamixExtractorPatterns.getQualityFromName("720p"))
    }

    @Test
    fun malformedHlsVariantIsFiltered() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=100000,RESOLUTION=640x360
            low.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=1280x720

            #EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=1920x1080
            high.m3u8
        """.trimIndent()
        val parsed = StreamixM3u8Verifier.parseVariants(master)
        val verdict = StreamixM3u8Verifier.classify("https://cdn.test/master.m3u8", parsed)
        assertTrue(verdict is StreamixM3u8Verifier.Verdict.Valid)
        assertEquals(2, (verdict as StreamixM3u8Verifier.Verdict.Valid).variants.size)
    }
}
