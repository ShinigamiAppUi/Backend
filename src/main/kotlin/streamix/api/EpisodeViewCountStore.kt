package streamix.api

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/** Persistent playback-start counters. playbackId makes client retries idempotent. */
class EpisodeViewCountStore(
    private val path: Path = Paths.get(System.getenv("SHINIGAMI_VIEW_COUNTS_PATH")?.takeIf { it.isNotBlank() } ?: "data/episode-view-counts.json"),
    private val gson: Gson = Gson()
) {
    private data class Snapshot(
        val counts: MutableMap<String, Long> = linkedMapOf(),
        val playbackIds: MutableList<String> = mutableListOf()
    )

    private val lock = Any()
    private val type = object : TypeToken<Snapshot>() {}.type
    private var snapshot: Snapshot = load()

    fun list(anilistId: Long): Map<String, Long> = synchronized(lock) {
        require(anilistId > 0L) { "anime ID must be positive" }
        snapshot.counts
            .filter { (key, value) ->
                key.startsWith("$anilistId.") &&
                    key.substringAfter('.').toIntOrNull()?.let { it > 0 } == true &&
                    value >= 0L
            }
            .mapKeys { it.key.substringAfter('.') }
    }

    fun record(anilistId: Long, episodeNumber: Int, playbackId: String): Long = synchronized(lock) {
        require(anilistId > 0L && episodeNumber > 0) { "anime and episode IDs must be positive" }
        require(playbackId.matches(Regex("[A-Za-z0-9_-]{16,100}"))) { "playback ID is invalid" }

        val dedupeKey = "$anilistId:$episodeNumber:$playbackId"
        val key = "$anilistId.$episodeNumber"
        if (dedupeKey in snapshot.playbackIds) return@synchronized snapshot.counts[key] ?: 0L

        // Persist a candidate first. A disk error must not leave an unpersisted count
        // visible in memory or make a failed request appear successful on retry.
        val nextCounts = LinkedHashMap(snapshot.counts)
        nextCounts[key] = Math.addExact(nextCounts[key] ?: 0L, 1L)
        val nextPlaybackIds = ArrayList(snapshot.playbackIds)
        nextPlaybackIds += dedupeKey
        while (nextPlaybackIds.size > MAX_PLAYBACK_IDS) nextPlaybackIds.removeAt(0)
        val next = Snapshot(nextCounts, nextPlaybackIds)
        persist(next)
        snapshot = next
        nextCounts.getValue(key)
    }

    private fun load(): Snapshot = runCatching {
        if (!Files.exists(path)) return@runCatching Snapshot()
        Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
            val loaded = gson.fromJson<Snapshot>(reader, type) ?: return@use Snapshot()
            Snapshot(
                counts = loaded.counts.filter { (key, value) ->
                    val parts = key.split('.', limit = 2)
                    parts.size == 2 && parts[0].toLongOrNull()?.let { it > 0L } == true &&
                        parts[1].toIntOrNull()?.let { it > 0 } == true && value >= 0L
                }.toMap(LinkedHashMap()),
                playbackIds = loaded.playbackIds
                    .filter { it.matches(Regex("[1-9][0-9]*:[1-9][0-9]*:[A-Za-z0-9_-]{16,100}")) }
                    .takeLast(MAX_PLAYBACK_IDS)
                    .toMutableList()
            )
        }
    }.getOrDefault(Snapshot())

    private fun persist(value: Snapshot) {
        val absolute = path.toAbsolutePath()
        absolute.parent?.let { Files.createDirectories(it) }
        val temp = absolute.resolveSibling(absolute.fileName.toString() + ".tmp")
        try {
            Files.newBufferedWriter(temp, StandardCharsets.UTF_8).use { gson.toJson(value, it) }
            try {
                Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private companion object {
        const val MAX_PLAYBACK_IDS = 100_000
    }
}
