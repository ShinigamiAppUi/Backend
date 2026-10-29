package streamix.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import streamix.api.StreamRef

class CatalogResolverContractTest {
    @Test
    fun registryReturnsAllMatchingResolversInRegistrationOrder() {
        val first = FakeResolver("first", setOf("example.com"))
        val second = FakeResolver("second", setOf("example.com"))
        val registry = CatalogResolverRegistry(listOf(first, second))

        assertEquals(
            listOf("first", "second"),
            registry.resolveAll("https://www.example.com/files/123").map { it.id }
        )
    }

    @Test
    fun resolverResultSeparatesResolutionSuccessFromPlaybackUsability() = runBlocking {
        val result = CatalogResolverResult(
            streams = listOf(
                StreamRef(
                    providerId = "resolver:test",
                    url = "https://cdn.example.com/video.m3u8",
                    quality = 720,
                    type = "m3u8",
                    headers = mapOf("User-Agent" to "ShinigamiTest"),
                    referer = "https://example.com/files/123"
                )
            ),
            httpStatus = 200
        )

        assertEquals(true, result.succeeded)
        assertEquals("https://cdn.example.com/video.m3u8", result.streams.single().url)
        assertEquals(200, result.httpStatus)
    }

    @Test
    fun failedResolverResultDoesNotPretendToHaveResolvedAStream() {
        val result = CatalogResolverResult(
            httpStatus = 403,
            failureReason = "HTTP 403"
        )

        assertEquals(false, result.succeeded)
        assertEquals(403, result.httpStatus)
        assertEquals("HTTP 403", result.failureReason)
    }

    private class FakeResolver(
        override val id: String,
        override val domains: Set<String>
    ) : CatalogResolver {
        override suspend fun resolve(source: EpisodeSource): CatalogResolverResult =
            CatalogResolverResult()
    }
}
