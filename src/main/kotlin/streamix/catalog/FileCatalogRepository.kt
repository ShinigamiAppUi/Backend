package streamix.catalog

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private fun catalogGson(): Gson = GsonBuilder()
    .registerTypeAdapter(EpisodeSource::class.java, JsonDeserializer<EpisodeSource> { json, _, _ ->
        val objectJson = json.asJsonObject
        val sourceUrl = objectJson.get("sourceUrl")?.asString.orEmpty()
        val rawType = objectJson.get("type")?.takeUnless { it.isJsonNull }?.asString
        EpisodeSource(
            id = objectJson.get("id")?.asString.orEmpty(),
            anilistId = objectJson.get("anilistId")?.asLong ?: 0L,
            episodeNumber = objectJson.get("episodeNumber")?.asInt ?: 0,
            sourceUrl = sourceUrl,
            origin = objectJson.get("origin")?.asString?.let { runCatching { SourceOrigin.valueOf(it) }.getOrNull() } ?: SourceOrigin.ADMIN,
            type = legacySourceType(rawType?.uppercase(Locale.ROOT), sourceUrl),
            provider = objectJson.get("provider")?.takeUnless { it.isJsonNull }?.asString,
            role = objectJson.get("role")?.takeUnless { it.isJsonNull }?.asString,
            resolution = objectJson.get("resolution")?.takeUnless { it.isJsonNull }?.asString,
            label = objectJson.get("label")?.takeUnless { it.isJsonNull }?.asString,
            sourcePage = objectJson.get("sourcePage")?.takeUnless { it.isJsonNull }?.asString,
            source = objectJson.get("source")?.takeUnless { it.isJsonNull }?.asString,
            quality = objectJson.get("quality")?.takeUnless { it.isJsonNull }?.asInt,
            language = objectJson.get("language")?.takeUnless { it.isJsonNull }?.asString,
            subtitleLanguage = objectJson.get("subtitleLanguage")?.takeUnless { it.isJsonNull }?.asString,
            headers = objectJson.get("headers")?.takeUnless { it.isJsonNull }?.asJsonObject?.let(::jsonObjectToStringMap) ?: emptyMap(),
            referer = objectJson.get("referer")?.takeUnless { it.isJsonNull }?.asString,
            priority = objectJson.get("priority")?.asInt ?: 0,
            enabled = objectJson.get("enabled")?.asBoolean ?: true,
            health = objectJson.get("health")?.asString?.let { runCatching { SourceHealth.valueOf(it) }.getOrNull() } ?: SourceHealth.UNKNOWN,
            lastCheckedAt = objectJson.get("lastCheckedAt")?.takeUnless { it.isJsonNull }?.asLong,
            lastHttpStatus = objectJson.get("lastHttpStatus")?.takeUnless { it.isJsonNull }?.asInt,
            lastFailureReason = objectJson.get("lastFailureReason")?.takeUnless { it.isJsonNull }?.asString
        )
    })
    .create()

private fun jsonObjectToStringMap(value: JsonObject): Map<String, String> =
    value.entrySet().associate { it.key to it.value.asString }

class FileCatalogRepository(
    file: Path,
    private val gson: Gson = catalogGson()
) : CatalogRepository {
    private val file = file.toAbsolutePath().normalize()
    // Separate repository instances in the same JVM must share the read-modify-write lock.
    private val lock = locks.computeIfAbsent(this.file) { Any() }

    private val type = object : TypeToken<List<EpisodeSource>>() {}.type

    override fun listEpisodes(anilistId: Long): List<CatalogEpisode> = synchronized(lock) {
        read().filter { it.anilistId == anilistId }
            .groupBy { it.episodeNumber }
            .map { (number, sources) ->
                CatalogEpisode(
                    anilistId,
                    number,
                    sources = sources.sortedWith(
                        compareByDescending<EpisodeSource> { it.priority }.thenBy { it.id }
                    )
                )
            }
            .sortedBy { it.number }
    }

    override fun getEpisode(anilistId: Long, number: Int): CatalogEpisode? =
        listEpisodes(anilistId).firstOrNull { it.number == number }

    override fun upsertSource(source: EpisodeSource): EpisodeSource = upsertSources(listOf(source)).single()

    override fun upsertSources(sources: List<EpisodeSource>): List<EpisodeSource> = synchronized(lock) {
        if (sources.isEmpty()) return emptyList()
        val ids = sources.map { it.id }
        require(ids.size == ids.toSet().size) { "duplicate source ids in bulk import" }
        val importedIds = ids.toSet()
        val current = read()
        require(current.filter { it.id in importedIds }.all { existing ->
            sources.any { incoming ->
                incoming.id == existing.id &&
                    incoming.anilistId == existing.anilistId &&
                    incoming.episodeNumber == existing.episodeNumber
            }
        }) { "source id already belongs to a different anime or episode" }
        val retained = current.filterNot { it.id in importedIds }
        write(retained + sources)
        sources
    }

    override fun deleteSource(anilistId: Long, episodeNumber: Int, sourceId: String): Boolean = synchronized(lock) {
        val current = read()
        val next = current.filterNot {
            it.anilistId == anilistId &&
                it.episodeNumber == episodeNumber &&
                it.id == sourceId
        }
        if (next.size == current.size) false else {
            write(next)
            true
        }
    }

    override fun updateSourceHealth(
        anilistId: Long,
        episodeNumber: Int,
        sourceId: String,
        health: SourceHealth,
        checkedAt: Long,
        httpStatus: Int?,
        failureReason: String?
    ): EpisodeSource? = synchronized(lock) {
        val current = read()
        var updated: EpisodeSource? = null
        val next = current.map { source ->
            if (source.anilistId == anilistId &&
                source.episodeNumber == episodeNumber &&
                source.id == sourceId
            ) {
                source.copy(
                    health = health,
                    lastCheckedAt = checkedAt,
                    lastHttpStatus = httpStatus,
                    lastFailureReason = failureReason
                ).also { updated = it }
            } else source
        }
        if (updated != null) write(next)
        updated
    }

    private fun read(): List<EpisodeSource> {
        if (!Files.exists(file)) return emptyList()
        val json = Files.readString(file)
        if (json.isBlank()) return emptyList()
        return gson.fromJson<List<EpisodeSource>>(json, type)
            ?: throw java.io.IOException("Persisted JSON must be a non-null array: $file")
    }

    private fun write(items: List<EpisodeSource>) {
        val parent = file.parent ?: throw java.io.IOException("Catalog file must have a parent directory: $file")
        Files.createDirectories(parent)
        val tmp = Files.createTempFile(parent, "catalog-", ".tmp")
        try {
            Files.writeString(tmp, gson.toJson(items))
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private companion object {
        val locks = ConcurrentHashMap<Path, Any>()
    }
}
