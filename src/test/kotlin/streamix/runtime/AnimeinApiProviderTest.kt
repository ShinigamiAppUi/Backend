package streamix.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import streamix.core.ProviderStream
import streamix.core.StreamixExtractor
import streamix.core.StreamixExtractorRegistry
import streamix.core.StreamixExtractorRequest
import streamix.core.StreamixExtractorResult
import streamix.core.StreamixHttp
import streamix.core.StreamixHttpProbeResponse
import streamix.core.StreamixHttpResponse

class AnimeinApiProviderTest {
    @Test
    fun mapsExactEpisodeReturnsDirectResolutionsAndResolvesEmbedHosts() = runBlocking {
        val http = AnimeinFixtureHttp()
        val embedExtractor = object : StreamixExtractor {
            override val id = "fake-animein-embed"
            override val domains = setOf("embed.example")

            override suspend fun extract(request: StreamixExtractorRequest) =
                StreamixExtractorResult(
                    streams = listOf(
                        ProviderStream(
                            providerId = id,
                            url = "https://cdn.example/one-piece-480.m3u8",
                            quality = 480,
                            type = "m3u8"
                        )
                    )
                )
        }
        val provider = AnimeinApiProvider(http, StreamixExtractorRegistry(listOf(embedExtractor)))

        val streams = provider.streams("One Piece", 1)

        assertEquals(3, streams.size)
        assertEquals(
            setOf(
                "https://cdn.example/one-piece-1080.m3u8",
                "https://cdn.example/one-piece-720.mp4",
                "https://cdn.example/one-piece-480.m3u8"
            ),
            streams.map { it.url }.toSet()
        )
        assertEquals(setOf(1080, 720, 480), streams.mapNotNull { it.quality }.toSet())
        assertTrue(streams.all { it.providerId == "animein-api" })
        assertTrue(streams.any { it.type == "m3u8" })
        assertTrue(streams.any { it.type == "mp4" })
        assertFalse(streams.any { it.url.contains("embed.example") })
        assertTrue(http.requestedUrls.any { it.contains("3/2/movie/episode/21") })
        assertTrue(http.requestedUrls.any { it.contains("3/2/episode/streamnew/ep-1") })
    }

    @Test
    fun findsOlderEpisodeByEstimatedPageAndUsesCanonicalDatabaseId() = runBlocking {
        val http = AnimeinFixtureHttp(olderEpisodeLookup = true)
        val provider = AnimeinApiProvider(http, StreamixExtractorRegistry(emptyList()))

        val streams = provider.streams("One Piece", 299)

        assertEquals(setOf("https://cdn.example/one-piece-299.m3u8"), streams.map { it.url }.toSet())
        assertTrue(http.requestedUrls.any { it.contains("3/2/movie/episode/21") && it.contains("page=29") })
        assertTrue(http.requestedUrls.any { it.contains("3/2/episode/streamnew/db-299") })
    }

    @Test
    fun findsSingleDigitEpisodeThreeNotEpisodesWhoseNumberStartsWithThree() = runBlocking {
        val http = AnimeinFixtureHttp(singleDigitEpisodeLookup = true)
        val provider = AnimeinApiProvider(http, StreamixExtractorRegistry(emptyList()))

        val streams = provider.streams("One Piece", 3)

        assertEquals(setOf("https://cdn.example/one-piece-3.m3u8"), streams.map { it.url }.toSet())
        assertTrue(http.requestedUrls.any { it.contains("3/2/movie/episode/21") && it.contains("page=39") })
        assertTrue(http.requestedUrls.any { it.contains("3/2/episode/streamnew/db-3") })
        assertFalse(http.requestedUrls.any { it.contains("3/2/episode/streamnew/db-399") })
    }

    @Test
    fun resolvesNestedEmbedToHlsStreamRefWithoutPlayback() = runBlocking {
        val provider = AnimeinApiProvider(
            AnimeinFixtureHttp(nestedEmbed = true),
            StreamixExtractorRegistry(emptyList())
        )

        val streams = provider.streams("One Piece", 1)

        assertTrue(streams.any {
            it.url == "https://cdn.example/one-piece-480.m3u8" &&
                it.type == "m3u8" &&
                it.providerId == "animein-api"
        })
    }

    @Test
    fun doesNotFetchLocalEmbedDiscoveredFromRemotePage() = runBlocking {
        val http = AnimeinFixtureHttp(nestedEmbed = true, localNestedEmbed = true)
        val provider = AnimeinApiProvider(http, StreamixExtractorRegistry(emptyList()))
        provider.streams("One Piece", 1)
        assertFalse(http.requestedUrls.any { it.contains("localhost") || it.contains("127.0.0.1") || it.contains("192.168.1.20") })
    }

    @Test
    fun rejectsUnsafeReplacementApiDomainsFromSetupEndpoint() = runBlocking {
        val rejectedDomains = listOf(
            "http://replacement.example",
            "https://user:pass@replacement.example",
            "https://localhost",
            "https://127.0.0.1",
            "https://192.168.1.20",
            "https://replacement.local"
        )

        for (domain in rejectedDomains) {
            val http = AnimeinFixtureHttp(gateReplacement = domain)
            AnimeinApiProvider(http, StreamixExtractorRegistry(emptyList()))
                .streams("Not In Fixture", 1)
            assertFalse(http.requestedUrls.any { it.startsWith(domain) }, "unexpected request to $domain")
        }
    }

    private class AnimeinFixtureHttp(
        private val gateReplacement: String? = null,
        private val nestedEmbed: Boolean = false,
        private val localNestedEmbed: Boolean = false,
        private val olderEpisodeLookup: Boolean = false,
        private val singleDigitEpisodeLookup: Boolean = false
    ) : StreamixHttp {
        val requestedUrls = java.util.Collections.synchronizedList(mutableListOf<String>())

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            referer: String?,
            cookies: Map<String, String>,
            timeoutMs: Long
        ): StreamixHttpResponse {
            requestedUrls += url
            val body = when {
                url.startsWith("https://gate.nextanimelist.com/data/setup/data") && gateReplacement != null ->
                    """{"data":{"domain_api":{"value":"$gateReplacement"}}}"""
                nestedEmbed && url == "https://generic.example/watch/ep-1" && localNestedEmbed ->
                    """<html><body><iframe src="http://127.0.0.1/admin"></iframe></body></html>"""
                nestedEmbed && url == "https://generic.example/watch/ep-1" ->
                    """<html><body><iframe src="https://cdn.example/one-piece-480.m3u8"></iframe></body></html>"""
                url.contains("data/movie/find") &&
                    (url.contains("query=One+Piece") || url.contains("title=One+Piece")) ->
                    """{"data":{"movie":[{"id":"21","title":"One Piece"}]}}"""
                singleDigitEpisodeLookup && url.contains("3/2/movie/episode/21") && url.contains("page=39") ->
                    """{"data":{"episode":[{"id":"db-399","index":399,"title":"Episode 399"},{"id":"db-39","index":39,"title":"Episode 39"},{"id":"db-3","index":3,"title":"Episode 3"}]}}"""
                olderEpisodeLookup && url.contains("3/2/movie/episode/21") && url.contains("page=29") ->
                    """{"data":{"episode":[{"id":"db-299","index":299,"title":"Episode 299"}]}}"""
                (olderEpisodeLookup || singleDigitEpisodeLookup) && url.contains("3/2/movie/episode/21") ->
                    """{"data":{"episode":[{"id":"db-1180","index":1180,"title":"Episode 1180"}]}}"""
                url.contains("3/2/movie/episode/21") ->
                    """{"data":{"episode":[{"id":"ep-1","index":1,"title":"Episode 1"}]}}"""
                singleDigitEpisodeLookup && url.contains("3/2/episode/streamnew/db-3") ->
                    """{"data":{"server":[{"id":"s3","name":"HLS 480p","quality":"480p","type":"direct","link":"https://cdn.example/one-piece-3.m3u8"}]}}"""
                olderEpisodeLookup && url.contains("3/2/episode/streamnew/db-299") ->
                    """{"data":{"server":[{"id":"s299","name":"HLS 480p","quality":"480p","type":"direct","link":"https://cdn.example/one-piece-299.m3u8"}]}}"""
                url.contains("3/2/episode/streamnew/ep-1") ->
                    """{"data":{"server":[
                      {"id":"s1","name":"HLS 1080p","quality":"1080p","type":"direct","link":"https://cdn.example/one-piece-1080.m3u8"},
                      {"id":"s2","name":"MP4 720p","quality":"720p","type":"direct","link":"https://cdn.example/one-piece-720.mp4"},
                      {"id":"s3","name":"Embed 480p","quality":"480p","type":"iframe","link":"${if (nestedEmbed) "https://generic.example/watch/ep-1" else "https://embed.example/watch/ep-1"}"}
                    ]}}"""
                else -> """{"data":{"movie":[]}}"""
            }
            return StreamixHttpResponse(200, url, body)
        }

        override suspend fun post(
            url: String,
            body: String,
            headers: Map<String, String>,
            referer: String?,
            cookies: Map<String, String>,
            timeoutMs: Long
        ): StreamixHttpResponse = StreamixHttpResponse(404, url, "")

        override suspend fun probe(
            url: String,
            headers: Map<String, String>,
            referer: String?,
            cookies: Map<String, String>,
            timeoutMs: Long,
            maxBytes: Long
        ): StreamixHttpProbeResponse = StreamixHttpProbeResponse(404, url, 0, true)
    }
}
