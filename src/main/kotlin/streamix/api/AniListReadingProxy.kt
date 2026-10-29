package streamix.api

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.util.Locale
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import streamix.readingcatalog.ReadingKind

/**
 * Reading-specific AniList client. Uses MediaType.MANGA, which covers manga,
 * manhwa, manhua, and novel/light-novel formats represented by AniList.
 * Anime endpoints remain owned by AniListProxy.
 */
class AniListReadingProxy(
    private val http: OkHttpClient = OkHttpClient()
) : AniListReadingGateway {
    override fun search(query: String, page: Int): List<AniListReadingMedia> {
        val safePage = page.coerceAtLeast(1)
        val escaped = query.replace("\\", "\\\\").replace("\"", "\\\"")
        val body = """{"query":"query($search:String,$page:Int,$type:MediaType!){Page(page:$page,perPage:20){media(search:$search,type:$type){id title{romaji english native userPreferred} coverImage{large} bannerImage description(asHtml:false) genres format countryOfOrigin status averageScore}}}","variables":{"search":"$escaped","page":$safePage,"type":"MANGA"}}"""
        val response = execute(body)
        val root = parseSuccessfulResponse(response)
        val media = try {
            root.getAsJsonObject("data")?.getAsJsonObject("Page")?.getAsJsonArray("media")
        } catch (_: RuntimeException) { null } ?: throw AniListUpstreamException(response.code)
        return media.mapNotNull { item ->
            try { toReadingMedia(item.asJsonObject) } catch (_: RuntimeException) { null }
        }
    }

    override fun detail(anilistId: Long): AniListReadingMedia? {
        if (anilistId <= 0L) return null
        val body = """{"query":"query($id:Int!,$type:MediaType!){Media(id:$id,type:$type){id title{romaji english native userPreferred} coverImage{large} bannerImage description(asHtml:false) genres format countryOfOrigin status averageScore}}","variables":{"id":$anilistId,"type":"MANGA"}}"""
        val response = execute(body)
        val root = parseSuccessfulResponse(response)
        val media = try { root.getAsJsonObject("data")?.get("Media") } catch (_: RuntimeException) { null }
            ?: throw AniListUpstreamException(response.code)
        if (media.isJsonNull) return null
        if (!media.isJsonObject) throw AniListUpstreamException(response.code)
        return try { toReadingMedia(media.asJsonObject) } catch (_: RuntimeException) {
            throw AniListUpstreamException(response.code)
        }
    }

    private fun toReadingMedia(obj: JsonObject): AniListReadingMedia {
        val id = obj["id"].asLong
        val titles = obj["title"]?.takeIf { it.isJsonObject }?.asJsonObject
        val title = listOf("userPreferred", "english", "romaji", "native")
            .asSequence()
            .mapNotNull { key -> titles?.get(key)?.takeIf { !it.isJsonNull }?.asString }
            .firstOrNull { it.isNotBlank() } ?: "Reading $id"
        val format = obj["format"]?.takeUnless { it.isJsonNull }?.asString
        val origin = obj["countryOfOrigin"]?.takeUnless { it.isJsonNull }?.asString
        val kind = when {
            format.equals("NOVEL", ignoreCase = true) || format.equals("LIGHT_NOVEL", ignoreCase = true) ->
                ReadingKind.NOVEL
            origin.equals("KR", ignoreCase = true) -> ReadingKind.MANHWA
            origin.equals("CN", ignoreCase = true) -> ReadingKind.MANHUA
            else -> ReadingKind.MANGA
        }
        return AniListReadingMedia(
            anilistId = id,
            kind = kind,
            title = title,
            url = "https://anilist.co/manga/$id",
            coverImage = nestedString(obj, "coverImage", "large"),
            bannerImage = obj["bannerImage"]?.takeUnless { it.isJsonNull }?.asString,
            description = obj["description"]?.takeUnless { it.isJsonNull }?.asString,
            genres = obj["genres"]?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull {
                if (it.isJsonNull) null else it.asString
            }.orEmpty(),
            format = format,
            countryOfOrigin = origin,
            status = obj["status"]?.takeUnless { it.isJsonNull }?.asString,
            averageScore = obj["averageScore"]?.takeUnless { it.isJsonNull }?.asInt
        )
    }

    private fun nestedString(obj: JsonObject, parent: String, child: String): String? =
        try { obj.getAsJsonObject(parent)?.get(child)?.takeUnless { it.isJsonNull }?.asString }
        catch (_: RuntimeException) { null }

    private fun execute(body: String): AniListProxy.Response {
        val request = Request.Builder()
            .url("https://graphql.anilist.co/")
            .post(body.toByteArray().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Accept", "application/json")
            .header("Referer", "https://anilist.co/")
            .header("Origin", "https://anilist.co")
            .build()
        try {
            http.newCall(request).execute().use { response ->
                return AniListProxy.Response(response.code, response.body?.bytes() ?: ByteArray(0), response.header("Content-Type"))
            }
        } catch (e: IOException) {
            throw AniListUpstreamException(null)
        }
    }

    private fun parseSuccessfulResponse(response: AniListProxy.Response): JsonObject {
        if (response.code !in 200..299) throw AniListUpstreamException(response.code)
        val root = try { JsonParser.parseString(response.body.toString(Charsets.UTF_8)).asJsonObject }
        catch (_: RuntimeException) { throw AniListUpstreamException(response.code) }
        val errors = root.get("errors")
        if (errors != null && !errors.isJsonNull && (!errors.isJsonArray || errors.asJsonArray.size() > 0)) {
            throw AniListUpstreamException(response.code)
        }
        return root
    }
}
