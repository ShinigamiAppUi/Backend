package streamix.library

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import streamix.api.UserAnimeState
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class FileUserLibraryRepository(
    file: Path,
    private val gson: Gson = Gson()
) : UserLibraryRepository {
    private val file: Path = file.toAbsolutePath().normalize()
    private val lock: Any = locks.computeIfAbsent(this.file) { Any() }

    companion object {
        // Serialize read-modify-write across instances in this JVM. This is not cross-process locking.
        private val locks = ConcurrentHashMap<Path, Any>()
    }
    private data class StoredEntry(
        val userId: String,
        val mediaId: Long,
        val status: streamix.api.LibraryStatus? = null,
        val progress: Int = 0,
        val score: Double? = null,
        val isFavorite: Boolean = false,
        val notes: String? = null,
        val updatedAt: String? = null
    )

    override fun list(userId: String, page: Int, perPage: Int): List<UserAnimeState> = synchronized(lock) {
        val safePage = page.coerceAtLeast(1)
        val safeSize = perPage.coerceIn(1, 101)
        val entries = read()
            .asSequence()
            .filter { it.userId == userId }
            .sortedByDescending { it.updatedAt.orEmpty() }
        val offset = (safePage.toLong() - 1L) * safeSize.toLong()
        if (offset >= Int.MAX_VALUE.toLong()) return@synchronized emptyList()
        entries.drop(offset.toInt())
            .take(safeSize)
            .map { it.asPublic() }
            .toList()
    }

    override fun find(userId: String, mediaId: Long): UserAnimeState? = synchronized(lock) {
        read().firstOrNull { it.userId == userId && it.mediaId == mediaId }?.asPublic()
    }

    override fun upsert(userId: String, state: UserAnimeState): UserAnimeState = synchronized(lock) {
        require(state.mediaId > 0) { "mediaId must be positive" }
        val entries = read()
        val stored = StoredEntry(
            userId = userId,
            mediaId = state.mediaId,
            status = state.status,
            progress = state.progress.coerceAtLeast(0),
            score = state.score,
            isFavorite = state.isFavorite,
            notes = state.notes,
            updatedAt = state.updatedAt ?: Instant.now().toString()
        )
        val updated = entries.filterNot { it.userId == userId && it.mediaId == state.mediaId } + stored
        write(updated)
        stored.asPublic()
    }

    override fun delete(userId: String, mediaId: Long) = synchronized(lock) {
        write(read().filterNot { it.userId == userId && it.mediaId == mediaId })
    }

    private fun read(): List<StoredEntry> {
        if (!Files.exists(file)) return emptyList()
        val json = Files.readString(file)
        if (json.isBlank()) return emptyList()
        return gson.fromJson<List<StoredEntry>>(
            json,
            object : TypeToken<List<StoredEntry>>() {}.type
        ) ?: throw java.io.IOException("Persisted JSON must be a non-null array: $file")
    }

    private fun write(entries: List<StoredEntry>) {
        file.parent?.let(Files::createDirectories)
        val temp = Files.createTempFile(file.parent, "library-", ".tmp")
        try {
            Files.writeString(temp, gson.toJson(entries))
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun StoredEntry.asPublic() = UserAnimeState(
        mediaId = mediaId,
        status = status,
        progress = progress,
        score = score,
        isFavorite = isFavorite,
        notes = notes,
        updatedAt = updatedAt
    )
}
