package streamix.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import streamix.api.StreamRef
import streamix.core.StreamixHttp
import streamix.core.StreamixHttpResponse

class CatalogHostResolverTest {
    @Test
    fun gofile_resolves_multiple_files_and_preserves_metadata() = runBlocking {
        val responseUrl = "https://api.gofile.io/contents/abc123?wt=4fd6sg89d7s6&cache=true"
        val http = FakeHttp(mapOf(responseUrl to StreamixHttpResponse(
            200, responseUrl,
            """{"status":"ok","data":{"children":{"one":{"type":"file","name":"One 360p.mp4","link":"https://store1.gofile.io/download/one"},"two":{"type":"file","name":"Two 720p.mkv","directLink":"https://store1.gofile.io/download/two"}}}}"""
        )))
        val source = EpisodeSource(
            "gofile", 1, 1, "https://gofile.io/d/abc123",
            type = SourceType.RESOLVER,
            quality = 720,
            language = "id",
            subtitleLanguage = "id",
            headers = mapOf("User-Agent" to "Shinigami"),
            referer = "https://gofile.io"
        )
        val result = GofileCatalogResolver(http).resolve(source)
        assertEquals(2, result.streams.size)
        assertEquals(
            listOf("https://store1.gofile.io/download/one", "https://store1.gofile.io/download/two"),
            result.streams.map(StreamRef::url)
        )
        assertTrue(result.streams.all { it.providerId == "gofile-catalog" })
        assertTrue(result.streams.all { it.headers["User-Agent"] == "Shinigami" })
        assertTrue(result.streams.all { it.referer == "https://gofile.io" })
    }

    @Test
    fun gofile_fails_closed_on_http_error() = runBlocking {
        val responseUrl = "https://api.gofile.io/contents/abc123?wt=4fd6sg89d7s6&cache=true"
        val http = FakeHttp(mapOf(responseUrl to StreamixHttpResponse(403, responseUrl, "")))
        val result = GofileCatalogResolver(http).resolve(
            EpisodeSource("gofile", 1, 1, "https://gofile.io/d/abc123", type = SourceType.RESOLVER)
        )
        assertTrue(result.streams.isEmpty())
        assertEquals(403, result.httpStatus)
    }

    @Test
    fun gofile_rejects_redirect_body_even_when_it_contains_parseable_media() = runBlocking {
        val responseUrl = "https://api.gofile.io/contents/abc123?wt=4fd6sg89d7s6&cache=true"
        val body = """{"status":"ok","data":{"children":{"one":{"type":"file","name":"Episode 720p.mp4","link":"https://store1.gofile.io/download/one"}}}}"""
        val http = FakeHttp(mapOf(responseUrl to StreamixHttpResponse(302, responseUrl, body)))
        val result = GofileCatalogResolver(http).resolve(
            EpisodeSource("gofile", 1, 1, "https://gofile.io/d/abc123", type = SourceType.RESOLVER)
        )
        assertTrue(result.streams.isEmpty())
        assertEquals(302, result.httpStatus)
    }

    @Test
    fun buzzheavier_ignores_non_media_links_and_preserves_signed_query() = runBlocking {
        val pageUrl = "https://buzzheavier.com/abc123"
        val page = """
            <a href="https://buzzheavier.com/download">advertisement</a>
            <a href="https://ts.buzzheavier.com/d/abc123?v=signed-token">download</a>
            <a href="https://example.com/not-a-media-file.txt">text</a>
        """.trimIndent()
        val http = FakeHttp(mapOf(pageUrl to StreamixHttpResponse(200, pageUrl, page)))
        val result = BuzzHeavierCatalogResolver(http).resolve(
            EpisodeSource("buzz", 1, 1, pageUrl, type = SourceType.RESOLVER)
        )
        assertEquals(listOf("https://ts.buzzheavier.com/d/abc123?v=signed-token"), result.streams.map(StreamRef::url))
    }

    @Test
    fun buzzheavier_fails_closed_on_http_error() = runBlocking {
        val pageUrl = "https://buzzheavier.com/abc123"
        val http = FakeHttp(mapOf(pageUrl to StreamixHttpResponse(403, pageUrl, "")))
        val result = BuzzHeavierCatalogResolver(http).resolve(
            EpisodeSource("buzz", 1, 1, pageUrl, type = SourceType.RESOLVER)
        )
        assertTrue(result.streams.isEmpty())
        assertEquals(403, result.httpStatus)
    }

    @Test
    fun buzzheavier_resolves_page_and_known_downloads() = runBlocking {
        val pageUrl = "https://buzzheavier.com/abc123"
        val page = """<a href="https://ts.buzzheavier.com/d/abc123?v=signed-token">download</a><video src="https://cdn.buzzheavier.com/file.mp4"></video>"""
        val http = FakeHttp(mapOf(pageUrl to StreamixHttpResponse(200, pageUrl, page)))
        val result = BuzzHeavierCatalogResolver(http).resolve(
            EpisodeSource("buzz", 1, 1, pageUrl, type = SourceType.RESOLVER, quality = 1080, referer = "https://buzzheavier.com/")
        ).streams
        assertEquals(
            listOf(
                "https://cdn.buzzheavier.com/file.mp4",
                "https://ts.buzzheavier.com/d/abc123?v=signed-token"
            ),
            result.map(StreamRef::url)
        )
    }

    private class FakeHttp(private val responses: Map<String, StreamixHttpResponse>) : StreamixHttp {
        override suspend fun get(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long) =
            responses[url] ?: error("unexpected GET: " + url)
        override suspend fun post(url: String, body: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long) =
            error("unexpected POST")
        override suspend fun probe(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long, maxBytes: Long) =
            error("unexpected probe")
    }
}
