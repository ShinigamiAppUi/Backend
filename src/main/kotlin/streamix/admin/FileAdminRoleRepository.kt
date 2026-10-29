package streamix.admin

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import streamix.api.AdminRole
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

private data class StoredRole(
    val userId: String,
    val role: String
)

class FileAdminRoleRepository(
    directory: Path,
    private val gson: Gson = Gson()
) : AdminRoleRepository {
    private val directory: Path = directory.toAbsolutePath().normalize()
    private val file = this.directory.resolve("roles.json")
    private val lock: Any = locks.computeIfAbsent(file) { Any() }

    companion object {
        // Serialize read-modify-write across instances in this JVM, not across processes.
        private val locks = ConcurrentHashMap<Path, Any>()
    }

    override fun role(userId: String): AdminRole = synchronized(lock) {
        read().firstOrNull { it.userId == userId }?.role
            ?.let { runCatching { AdminRole.valueOf(it) }.getOrNull() }
            ?: AdminRole.MEMBER
    }

    override fun setRole(userId: String, role: AdminRole): AdminRole = synchronized(lock) {
        val current = read()
        val updated = current.filterNot { it.userId == userId } + StoredRole(userId, role.name)
        write(updated)
        role
    }

    private fun read(): List<StoredRole> {
        if (!Files.exists(file)) return emptyList()
        val json = Files.readString(file)
        if (json.isBlank()) return emptyList()
        return gson.fromJson<List<StoredRole>>(
            json,
            object : TypeToken<List<StoredRole>>() {}.type
        ) ?: throw java.io.IOException("Persisted JSON must be a non-null array: $file")
    }

    private fun write(roles: List<StoredRole>) {
        directory.let(Files::createDirectories)
        val temp = Files.createTempFile(directory, "roles-", ".tmp")
        try {
            Files.writeString(temp, gson.toJson(roles))
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
