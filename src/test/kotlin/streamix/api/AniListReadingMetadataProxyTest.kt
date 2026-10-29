package streamix.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

class AniListReadingMetadataProxyTest {
    @Test
    fun searchUsesMangaMediaAndFiltersManhwaByCountry() {
        var requestBody = ""
        val response = metadataResponse(
            """{"data":{"Page":{"media":[{"id":123,"idMal":321,"synonyms":["Fixture Alias"],"title":{"english":"Fixture Manhwa"},"coverImage":{"large":"https://cdn.example/cover.jpg"},"bannerImage":"https://cdn.example/banner.jpg","description":"fixture","genres":["Action","Drama"],"status":"RELEASING","format":"MANGA","countryOfOrigin":"KR","startDate":{"year":2024}}]}}}"""
        ) { body -> requestBody = body }
        val result = response.search("Fixture", ReadingMetadataKind.MANHWA, 2)

        assertEquals(1, result.size)
        assertEquals(123L, result.single().id)
        assertEquals(321, result.single().malId)
        assertEquals(listOf("Fixture Alias"), result.single().synonyms)
        assertEquals(ReadingMetadataKind.MANHWA, result.single().kind)
        assertEquals("Fixture Manhwa", result.single().title)
        assertEquals("https://anilist.co/manga/123", result.single().url)
        assertEquals(2024, result.single().year)
        assertEquals(listOf("Action", "Drama"), result.single().genres)
        assertTrue(requestBody.contains("type:MANGA"))
        assertTrue(requestBody.contains("countryOfOrigin"))
        assertTrue(requestBody.contains("KR"))
        assertTrue(requestBody.contains("format_in: [MANGA, ONE_SHOT]"))
        assertFalse(requestBody.contains("format: NOVEL"))
    }

    @Test
    fun novelSearchUsesAniListNovelFormatFilter() {
        var requestBody = ""
        val gateway = metadataResponse(
            """{"data":{"Page":{"media":[{"id":456,"title":{"userPreferred":"Fixture Light Novel"},"format":"NOVEL","countryOfOrigin":"JP"}]}}}"""
        ) { body -> requestBody = body }

        val result = gateway.search("Fixture Light Novel", ReadingMetadataKind.NOVEL, 1)

        assertEquals(ReadingMetadataKind.NOVEL, result.single().kind)
        assertTrue(requestBody.contains("format: NOVEL"))
        assertFalse(requestBody.contains("countryOfOrigin"))
    }

    @Test
    fun detailInfersManhuaFromCountryOfOrigin() {
        val gateway = metadataResponse(
            """{"data":{"Media":{"id":789,"idMal":987,"synonyms":["Example Comic"],"title":{"native":"示例漫画"},"format":"MANGA","countryOfOrigin":"CN"}}}"""
        )
        val result = gateway.detail(789L)

        assertEquals(ReadingMetadataKind.MANHUA, result?.kind)
        assertEquals(987, result?.malId)
        assertEquals(listOf("Example Comic"), result?.synonyms)
        assertEquals("https://anilist.co/manga/789", result?.url)
    }

    @Test
    fun validMissingDetailReturnsNull() {
        assertNull(metadataResponse("""{"data":{"Media":null}}""").detail(999999L))
    }

    @Test
    fun graphqlErrorsAreSanitized() {
        val error = assertFailsWith<AniListReadingMetadataException> {
            metadataResponse("""{"errors":[{"message":"sensitive upstream details"}],"data":{"Page":{"media":[]}}}""")
                .search("Fixture", ReadingMetadataKind.MANGA)
        }
        assertEquals(200, error.upstreamStatusCode)
        assertFalse(error.message.orEmpty().contains("sensitive upstream details"))
    }

    @Test
    fun non2xxResponsesDoNotLookLikeEmptySearches() {
        val error = assertFailsWith<AniListReadingMetadataException> {
            metadataResponse("{}", status = 429).search("Fixture", ReadingMetadataKind.MANGA)
        }
        assertEquals(429, error.upstreamStatusCode)
    }

    @Test
    fun oversizedUpstreamResponseIsRejectedAndSanitized() {
        val oversized = """{"data":{"Page":{"media":[]}}}""" + " ".repeat(4 * 1024 * 1024)
        val error = assertFailsWith<AniListReadingMetadataException> {
            metadataResponse(oversized).search("Fixture", ReadingMetadataKind.MANGA)
        }
        assertNull(error.upstreamStatusCode)
        assertFalse(error.message.orEmpty().contains("configured size limit"))
    }

    private fun metadataResponse(
        body: String,
        status: Int = 200,
        onRequest: (String) -> Unit = {}
    ): AniListReadingMetadataProxy {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val buffer = Buffer()
                chain.request().body?.writeTo(buffer)
                onRequest(buffer.readUtf8())
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(status)
                    .message("fixture")
                    .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            }
            .build()
        return AniListReadingMetadataProxy(http = client)
    }
}
