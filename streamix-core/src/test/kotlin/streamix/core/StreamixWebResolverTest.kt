package streamix.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamixWebResolverTest {
    @Test
    fun resolverContractCarriesRequestAndCollectedRequests() = kotlinx.coroutines.test.runTest {
        val resolver = FakeResolver()

        val result = resolver.resolve(
            StreamixWebRequest(
                url = "https://example.com/embed",
                referer = "https://example.com/watch",
                headers = mapOf("X-Test" to "1")
            )
        )

        assertEquals("https://example.com/stream.m3u8", result.intercepted?.url)
        assertEquals("https://example.com/embed/manifest.m3u8", result.additionalRequests.single().url)
        assertEquals("https://example.com/watch", result.intercepted?.referer)
        assertTrue(result.intercepted?.headers?.containsKey("X-Test") == true)
    }

    private class FakeResolver : StreamixWebResolver {
        override suspend fun resolve(
            request: StreamixWebRequest,
            onRequest: suspend (StreamixWebRequest) -> Boolean
        ): StreamixWebResult {
            val intercepted = request.copy(url = "https://example.com/stream.m3u8")
            val additional = request.copy(url = "https://example.com/embed/manifest.m3u8")
            onRequest(additional)
            return StreamixWebResult(
                intercepted = intercepted,
                additionalRequests = listOf(additional)
            )
        }
    }
}
