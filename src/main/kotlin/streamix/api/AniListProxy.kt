package streamix.api

import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class AniListProxy(
    private val http: OkHttpClient = OkHttpClient()
) : AniListGateway {
    data class Response(val code: Int, val body: ByteArray, val contentType: String?)

    override fun search(query: String, page: Int): List<ProviderAnime> {
        val safePage = page.coerceAtLeast(1)
        val escaped = query.replace("\\", "\\\\").replace("\"", "\\\"")
        val body = """{"query":"query(${'$'}search:String,${'$'}page:Int){Page(page:${'$'}page,perPage:20){media(search:${'$'}search,type:ANIME){id title{romaji english native userPreferred} coverImage{large} description(asHtml:false) genres}}}","variables":{"search":"$escaped","page":$safePage}}"""
        val response = execute(body.toByteArray(), null)
        return parseMediaList(parseSuccessfulResponse(response), response.code)
    }

    override fun detail(anilistId: Long): ProviderAnime? {
        val body = """{"query":"query(${'$'}id:Int){Media(id:${'$'}id,type:ANIME){id title{romaji english native userPreferred} coverImage{large} description(asHtml:false) genres}}","variables":{"id":$anilistId}}"""
        val response = execute(body.toByteArray(), null)
        val root = parseSuccessfulResponse(response)
        val media = try {
            root.getAsJsonObject("data")?.get("Media")
        } catch (_: RuntimeException) {
            null
        } ?: throw AniListUpstreamException(response.code)
        if (media.isJsonNull) return null
        if (!media.isJsonObject) throw AniListUpstreamException(response.code)
        return try {
            toProviderAnime(media.asJsonObject)
        } catch (_: RuntimeException) {
            throw AniListUpstreamException(response.code)
        }
    }

    fun title(anilistId: Long): String? = detail(anilistId)?.title

    override fun execute(body: ByteArray, authorization: String?): Response {
        val requestBuilder = Request.Builder()
            .url("https://graphql.anilist.co/")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Accept", "application/json")
            .header("Referer", "https://anilist.co/")
            .header("Origin", "https://anilist.co")
        if (!authorization.isNullOrBlank()) requestBuilder.header("Authorization", authorization)
        try {
            http.newCall(requestBuilder.build()).execute().use { response ->
                return Response(response.code, response.body?.bytes() ?: ByteArray(0), response.header("Content-Type"))
            }
        } catch (_: IOException) {
            throw AniListUpstreamException(null)
        }
    }

    private fun parseSuccessfulResponse(response: Response): com.google.gson.JsonObject {
        if (response.code !in 200..299) throw AniListUpstreamException(response.code)
        val root = try {
            com.google.gson.JsonParser.parseString(response.body.toString(Charsets.UTF_8)).asJsonObject
        } catch (_: RuntimeException) {
            throw AniListUpstreamException(response.code)
        }
        val errors = root.get("errors")
        if (errors != null && !errors.isJsonNull &&
            (!errors.isJsonArray || errors.asJsonArray.size() > 0)
        ) {
            throw AniListUpstreamException(response.code)
        }
        return root
    }

    private fun parseMediaList(root: com.google.gson.JsonObject, statusCode: Int): List<ProviderAnime> {
        val media = try {
            root.getAsJsonObject("data")?.getAsJsonObject("Page")?.getAsJsonArray("media")
        } catch (_: RuntimeException) {
            null
        } ?: throw AniListUpstreamException(statusCode)
        return media.mapNotNull { item -> runCatching { toProviderAnime(item.asJsonObject) }.getOrNull() }
    }

    private fun toProviderAnime(obj: com.google.gson.JsonObject): ProviderAnime {
        val id = obj["id"].asLong
        val titles = obj["title"]?.asJsonObject
        val title = listOf("userPreferred", "english", "romaji", "native")
            .asSequence()
            .mapNotNull { key -> titles?.get(key)?.takeIf { !it.isJsonNull }?.asString }
            .firstOrNull { it.isNotBlank() } ?: "Anime $id"
        return ProviderAnime(
            providerId = "anilist",
            id = id.toString(),
            title = title,
            url = "https://anilist.co/anime/$id",
            poster = obj["coverImage"]?.asJsonObject?.get("large")?.asString,
            description = obj["description"]?.asString,
            tags = obj["genres"]?.asJsonArray?.map { it.asString }.orEmpty()
        )
    }
}


/** Sanitized upstream failure for search/detail; never includes the response body. */
class AniListUpstreamException(val upstreamStatusCode: Int?) :
    RuntimeException("AniList upstream request failed")
