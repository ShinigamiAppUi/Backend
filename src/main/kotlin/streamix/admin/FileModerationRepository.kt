package streamix.admin

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import streamix.api.ModerationStatus
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

class FileModerationRepository(
    directory: Path,
    private val gson: Gson = Gson()
) : ModerationRepository {
    private val directory: Path = directory.toAbsolutePath().normalize()
    private val file = this.directory.resolve("moderation.json")
    private val lock: Any = locks.computeIfAbsent(file) { Any() }

    companion object {
        // Protect read-modify-write across instances in this JVM, not across processes.
        private val locks = ConcurrentHashMap<Path, Any>()
    }

    override fun state(userId: String): ModerationState = synchronized(lock) {
        read().firstOrNull { it.userId == userId } ?: ModerationState(userId)
    }

    override fun setStatus(
        userId: String,
        status: ModerationStatus,
        suspendedUntil: String?,
        reason: String?
    ): ModerationState = synchronized(lock) {
        val current = read()
        val existing = current.firstOrNull { it.userId == userId } ?: ModerationState(userId)
        val next = existing.copy(status = status, suspendedUntil = suspendedUntil, reason = reason)
        write(current.filterNot { it.userId == userId } + next)
        next
    }

    override fun addWarning(userId: String, reason: String?): ModerationState = synchronized(lock) {
        val current = read()
        val existing = current.firstOrNull { it.userId == userId } ?: ModerationState(userId)
        val next = existing.copy(
            warningCount = existing.warningCount + 1,
            reason = reason ?: existing.reason
        )
        write(current.filterNot { it.userId == userId } + next)
        next
    }

    private fun read(): List<ModerationState> {
        if (!Files.exists(file)) return emptyList()
        val json = Files.readString(file)
        if (json.isBlank()) return emptyList()
        return gson.fromJson<List<ModerationState>>(
            json,
            object : TypeToken<List<ModerationState>>() {}.type
        ) ?: throw java.io.IOException("Persisted JSON must be a non-null array: $file")
    }

    private fun write(states: List<ModerationState>) {
        Files.createDirectories(directory)
        val temp = Files.createTempFile(directory, "moderation-", ".tmp")
        try {
            Files.writeString(temp, gson.toJson(states))
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}
