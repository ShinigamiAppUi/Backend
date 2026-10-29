package streamix.core

import kotlinx.coroutines.test.runTest
import streamix.core.extractors.FiledonExtractor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FiledonExtractorTest {
    @Test
    fun extractsDirectMp4FromDataPage() = runTest {
        val http = FakeStreamixHttp(
            """<div data-page="{&quot;download_url&quot;:&quot;https://cdn.example/video.mp4&quot;,&quot;quality&quot;:&quot;720p&quot;}"></div>"""
        )

        val result = FiledonExtractor(http).extract(
            StreamixExtractorRequest("https://filedon.co/view/abc")
        )

        assertEquals(1, result.streams.size)
        assertEquals("https://cdn.example/video.mp4", result.streams.single().url)
        assertEquals("VIDEO", result.streams.single().type)
        assertEquals("https://filedon.co/embed/abc", result.streams.single().referer)
    }

    @Test
    fun extractsM3u8AndPreservesRequestedQuality() = runTest {
        val http = FakeStreamixHttp(
            """<div data-page="{&quot;stream_url&quot;:&quot;https://cdn.example/master.m3u8&quot;}"></div>"""
        )

        val result = FiledonExtractor(http).extract(
            StreamixExtractorRequest(
                url = "https://filedon.co/embed/xyz",
                quality = 1080
            )
        )

        assertEquals(1, result.streams.size)
        assertEquals("M3U8", result.streams.single().type)
        assertEquals(1080, result.streams.single().quality)
        assertTrue(result.streams.single().url.endsWith(".m3u8"))
    }

    private class FakeStreamixHttp(
        private val body: String
    ) : StreamixHttp {
        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            referer: String?,
            cookies: Map<String, String>,
            timeoutMs: Long
        ) = StreamixHttpResponse(200, url, body)

        override suspend fun post(
            url: String,
            body: String,
            headers: Map<String, String>,
            referer: String?,
            cookies: Map<String, String>,
            timeoutMs: Long
        ) = StreamixHttpResponse(200, url, body)

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
