package streamix.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

class AniListReadingProxyTest {
    @Test
    fun searchUsesMangaMediaTypeAndMapsManhwaMetadata() {
        var requestBody = ""
        val client = fixtureClient { request ->
            requestBody = request.body!!.let { body ->
                val buffer = okio.Buffer()
                body.writeTo(buffer)
                buffer.readUtf8()
            }
            """{"data":{"Page":{"media":[{"id":101,"title":{"english":"Example Webtoon"},"coverImage":{"large":"https://img.example/cover.jpg"},"format":"WEBTOON","countryOfOrigin":"KR","genres":["Action"],"averageScore":84}]}}}"""
        }
        val result = AniListReadingProxy(client).search("Example", 0)
        assertTrue(requestBody.contains("\"type\":\"MANGA\""))
        assertTrue(requestBody.contains("type:$type"))
        assertEquals(1, result.size)
        assertEquals(101L, result.first().anilistId)
        assertEquals(streamix.readingcatalog.ReadingKind.MANHWA, result.first().kind)
        assertEquals("https://anilist.co/manga/101", result.first().url)
        assertEquals("Example Webtoon", result.first().title)
    }

    @Test
    fun novelFormatMapsToNovelKind() {
        val client = fixtureClient {
            """{"data":{"Media":{"id":202,"title":{"romaji":"Novel Example"},"format":"LIGHT_NOVEL","countryOfOrigin":"JP"}}}"""
        }
        val result = AniListReadingProxy(client).detail(202L)
        assertEquals(streamix.readingcatalog.ReadingKind.NOVEL, result?.kind)
        assertEquals("LIGHT_NOVEL", result?.format)
    }

    @Test
    fun missingReadingDetailRemainsNull() {
        val client = fixtureClient { """{"data":{"Media":null}}""" }
        assertNull(AniListReadingProxy(client).detail(999L))
    }

    private fun fixtureClient(body: (okhttp3.Request) -> String): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            val payload = body(chain.request())
            okhttp3.Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("fixture")
                .body(payload.toResponseBody("application/json; charset=utf-8".toMediaType()))
                .build()
        }.build()
}
