package streamix.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Reading metadata comes from AniList and is deliberately separate from Reading Catalog. */
enum class ReadingMetadataKind {
    MANGA,
    MANHWA,
    MANHUA,
    NOVEL
}

data class ReadingMetadata(
    val id: Long,
    val kind: ReadingMetadataKind,
    val title: String,
    val url: String,
    val coverUrl: String? = null,
    val bannerUrl: String? = null,
    val description: String? = null,
    val year: Int? = null,
    val status: String? = null,
    val genres: List<String> = emptyList(),
    val format: String? = null,
    val countryOfOrigin: String? = null
)

interface ReadingMetadataGateway {
    fun search(query: String, kind: ReadingMetadataKind, page: Int = 1): List<ReadingMetadata>
    fun detail(anilistId: Long): ReadingMetadata?
}

/** AniList GraphQL adapter for manga/manhwa/manhua/novel metadata. */
class AniListReadingMetadataProxy(
    private val http: OkHttpClient = OkHttpClient(),
    private val gson: Gson = Gson()
) : ReadingMetadataGateway {
    private data class Response(val code: Int, val body: ByteArray)

    override fun search(query: String, kind: ReadingMetadataKind, page: Int): List<ReadingMetadata> {
        val mediaFilter = when (kind) {
            ReadingMetadataKind.MANGA -> ", countryOfOrigin: \"JP\""
            ReadingMetadataKind.MANHWA -> ", countryOfOrigin: \"KR\""
            ReadingMetadataKind.MANHUA -> ", countryOfOrigin: \"CN\""
            ReadingMetadataKind.NOVEL -> ", format: NOVEL"
        }
        val graphQl = """
            query($search:String, $page:Int) {
              Page(page:$page, perPage:20) {
                media(search:$search, type:MANGA$mediaFilter) {
                  id title { romaji english native userPreferred }
                  coverImage { large } bannerImage description(asHtml:false)
                  genres status format countryOfOrigin startDate { year }
                }
              }
            }
        """.trimIndent()
        val response = execute(
            mapOf(
                "query" to graphQl,
                "variables" to mapOf(
                    "search" to query,
                    "page" to page.coerceAtLeast(1)
                )
            )
        )
        val root = parseSuccessfulResponse(response)
        val media = try {
            root.getAsJsonObject("data")?.getAsJsonObject("Page")?.getAsJsonArray("media")
        } catch (_: RuntimeException) {
            null
        } ?: throw AniListReadingMetadataException(response.code)
        return media.mapNotNull { item ->
            try {
                toReadingMetadata(item.asJsonObject)
            } catch (_: RuntimeException) {
                null
            }
        }
    }

    override fun detail(anilistId: Long): ReadingMetadata? {
        val graphQl = """
            query($id:Int) {
              Media(id:$id, type:MANGA) {
                id title { romaji english native userPreferred }
                coverImage { large } bannerImage description(asHtml:false)
                genres status format countryOfOrigin startDate { year }
              }
            }
        """.trimIndent()
        val response = execute(mapOf("query" to graphQl, "variables" to mapOf("id" to anilistId)))
        val root = parseSuccessfulResponse(response)
        val media = try {
            root.getAsJsonObject("data")?.get("Media")
        } catch (_: RuntimeException) {
            null
        } ?: throw AniListReadingMetadataException(response.code)
        if (media.isJsonNull) return null
        if (!media.isJsonObject) throw AniListReadingMetadataException(response.code)
        return try {
            toReadingMetadata(media.asJsonObject)
        } catch (_: RuntimeException) {
            throw AniListReadingMetadataException(response.code)
        }
    }

    private fun execute(payload: Any): Response {
        val request = Request.Builder()
            .url("https://graphql.anilist.co/")
            .post(gson.toJson(payload).toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Accept", "application/json")
            .header("Referer", "https://anilist.co/")
            .header("Origin", "https://anilist.co")
            .build()
        try {
            http.newCall(request).execute().use { response ->
                return Response(response.code, response.body?.bytes() ?: ByteArray(0))
            }
        } catch (_: IOException) {
            throw AniListReadingMetadataException(null)
        }
    }

    private fun parseSuccessfulResponse(response: Response): JsonObject {
        if (response.code !in 200..299) throw AniListReadingMetadataException(response.code)
        val root = try {
            JsonParser.parseString(response.body.toString(Charsets.UTF_8)).asJsonObject
        } catch (_: RuntimeException) {
            throw AniListReadingMetadataException(response.code)
        }
        val errors = root.get("errors")
        if (errors != null && !errors.isJsonNull && (!errors.isJsonArray || errors.asJsonArray.size() > 0)) {
            throw AniListReadingMetadataException(response.code)
        }
        return root
    }

    private fun toReadingMetadata(obj: JsonObject): ReadingMetadata {
        val id = obj["id"].asLong
        val titles = obj["title"]?.takeIf { it.isJsonObject }?.asJsonObject
        val title = listOf("userPreferred", "english", "romaji", "native")
            .asSequence()
            .mapNotNull { key -> titles?.get(key)?.takeIf { !it.isJsonNull }?.asString }
            .firstOrNull { it.isNotBlank() } ?: "Reading $id"
        val format = obj["format"]?.takeIf { !it.isJsonNull }?.asString
        val country = obj["countryOfOrigin"]?.takeIf { !it.isJsonNull }?.asString
        val kind = when {
            format == "NOVEL" -> ReadingMetadataKind.NOVEL
            country == "KR" -> ReadingMetadataKind.MANHWA
            country == "CN" -> ReadingMetadataKind.MANHUA
            else -> ReadingMetadataKind.MANGA
        }
        val cover = obj["coverImage"]?.takeIf { it.isJsonObject }?.asJsonObject?.get("large")
            ?.takeIf { !it.isJsonNull }?.asString
        val banner = obj["bannerImage"]?.takeIf { !it.isJsonNull }?.asString
        val year = obj["startDate"]?.takeIf { it.isJsonObject }?.asJsonObject?.get("year")
            ?.takeIf { !it.isJsonNull }?.asInt
        val description = obj["description"]?.takeIf { !it.isJsonNull }?.asString
        val status = obj["status"]?.takeIf { !it.isJsonNull }?.asString
        val genres = obj["genres"]?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull {
            it.takeIf { value -> !value.isJsonNull }?.asString
        }.orEmpty()
        return ReadingMetadata(
            id = id,
            kind = kind,
            title = title,
            url = "https://anilist.co/manga/$id",
            coverUrl = cover,
            bannerUrl = banner,
            description = description,
            year = year,
            status = status,
            genres = genres,
            format = format,
            countryOfOrigin = country
        )
    }
}

/** Sanitized upstream failure; the AniList response body is never exposed. */
class AniListReadingMetadataException(val upstreamStatusCode: Int?) :
    RuntimeException("AniList reading metadata request failed")
