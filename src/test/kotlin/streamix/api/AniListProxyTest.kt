package streamix.api

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

class AniListProxyTest {
    @Test
    fun non2xxSearchIsNotReportedAsAValidEmptyResult() {
        val error = assertFailsWith<AniListUpstreamException> {
            proxy(429, """{"errors":[{"message":"rate limited"}]}""").search("One Piece", 1)
        }
        assertEquals(429, error.upstreamStatusCode)
    }

    @Test
    fun non2xxDetailIsNotReportedAsNotFound() {
        val error = assertFailsWith<AniListUpstreamException> {
            proxy(503, """{"error":"upstream unavailable"}""").detail(21L)
        }
        assertEquals(503, error.upstreamStatusCode)
    }

    @Test
    fun graphqlErrorsInsideHttp200AreNotReportedAsEmptyResults() {
        val error = assertFailsWith<AniListUpstreamException> {
            proxy(
                200,
                """{"errors":[{"message":"sensitive upstream detail"}],"data":{"Page":{"media":[]}}}"""
            ).search("One Piece", 1)
        }
        assertEquals(200, error.upstreamStatusCode)
        assertFalse(error.message.orEmpty().contains("sensitive upstream detail"))
    }

    @Test
    fun malformedJsonIsNotReportedAsEmptyResults() {
        assertFailsWith<AniListUpstreamException> {
            proxy(200, "not-json").search("One Piece", 1)
        }
    }

    @Test
    fun validEmptySearchRemainsAnEmptyResult() {
        assertTrue(proxy(200, """{"data":{"Page":{"media":[]}}}""").search("no such anime", 1).isEmpty())
    }

    @Test
    fun validMissingDetailRemainsNull() {
        assertNull(proxy(200, """{"data":{"Media":null}}""").detail(999999L))
    }

    @Test
    fun networkFailureIsSanitized() {
        val client = OkHttpClient.Builder()
            .addInterceptor { throw IOException("sensitive network detail") }
            .build()
        val error = assertFailsWith<AniListUpstreamException> {
            AniListProxy(client).search("One Piece", 1)
        }
        assertNull(error.upstreamStatusCode)
        assertFalse(error.message.orEmpty().contains("sensitive network detail"))
    }

    private fun proxy(status: Int, body: String): AniListProxy {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(status)
                    .message("fixture")
                    .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            }
            .build()
        return AniListProxy(client)
    }
}
