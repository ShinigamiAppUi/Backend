package streamix.admin

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import streamix.api.AdminAuditEntry
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

class FileAdminAuditRepository(
    private val directory: Path,
    private val gson: Gson = Gson()
) : AdminAuditRepository {
    private val file = directory.toAbsolutePath().normalize().resolve("audit-log.json")
    // Repository instances targeting the same file must serialize read-modify-write too.
    private val lock = locks.computeIfAbsent(file) { Any() }

    override fun append(entry: AdminAuditEntry) = synchronized(lock) {
        val entries = read()
        if (entries.any { it.id == entry.id }) return@synchronized
        write(entries + entry)
    }

    override fun recent(limit: Int): List<AdminAuditEntry> = synchronized(lock) {
        read().asReversed().take(limit.coerceIn(1, 500))
    }

    override fun findByOperationId(operationId: String): List<AdminAuditEntry> = synchronized(lock) {
        if (operationId.isBlank()) return@synchronized emptyList()
        read().filter { it.metadata["operationId"] == operationId }.asReversed()
    }

    private fun read(): List<AdminAuditEntry> {
        if (!Files.exists(file)) return emptyList()
        val json = Files.readString(file)
        if (json.isBlank()) return emptyList()
        return gson.fromJson<List<AdminAuditEntry>>(
            json,
            object : TypeToken<List<AdminAuditEntry>>() {}.type
        ) ?: throw java.io.IOException("Persisted JSON must be a non-null array: $file")
    }

    private fun write(entries: List<AdminAuditEntry>) {
        directory.let(Files::createDirectories)
        val temp = Files.createTempFile(directory, "audit-log-", ".tmp")
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

    private companion object {
        val locks = ConcurrentHashMap<Path, Any>()
    }
}
