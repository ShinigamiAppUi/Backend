package streamix.auth

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

private data class StoredSession(
    val token: String,
    val userId: String,
    val createdAt: String,
    val expiresAt: String
)

class FileSessionRepository(
    file: Path,
    private val gson: Gson = Gson()
) : SessionRepository {
    private val file = file.toAbsolutePath().normalize()
    // Repository instances for the same file must serialize read-modify-write operations.
    private val lock = locks.computeIfAbsent(this.file) { Any() }
    private val random = SecureRandom()

    override fun create(userId: String, ttlSeconds: Long): AuthSession = synchronized(lock) {
        require(ttlSeconds > 0) { "session TTL must be positive" }
        val now = Instant.now()
        val session = AuthSession(
            token = newToken(),
            userId = userId,
            createdAt = now,
            expiresAt = now.plusSeconds(ttlSeconds)
        )
        // Prune expired records when issuing a new session, rather than rewriting the file
        // on every lookup. Expired tokens are already rejected by find().
        val activeSessions = read().filter { stored ->
            runCatching { Instant.parse(stored.expiresAt).isAfter(now) }.getOrDefault(false)
        }
        write(activeSessions + session.toStored())
        session
    }

    override fun find(token: String): AuthSession? = synchronized(lock) {
        read().firstOrNull { it.token == token }
            ?.let { stored -> runCatching { stored.toSession() }.getOrNull() }
            ?.takeIf { it.expiresAt.isAfter(Instant.now()) }
    }

    override fun delete(token: String) = synchronized(lock) {
        write(read().filterNot { it.token == token })
    }

    private fun newToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun read(): List<StoredSession> {
        if (!Files.exists(file)) return emptyList()
        val json = Files.readString(file)
        if (json.isBlank()) return emptyList()
        return gson.fromJson<List<StoredSession>>(
            json,
            object : TypeToken<List<StoredSession>>() {}.type
        ) ?: throw java.io.IOException("Persisted JSON must be a non-null array: $file")
    }

    private fun write(sessions: List<StoredSession>) {
        val parent = file.parent ?: throw java.io.IOException("Session file must have a parent directory: $file")
        Files.createDirectories(parent)
        val temp = Files.createTempFile(parent, "sessions-", ".tmp")
        try {
            Files.writeString(temp, gson.toJson(sessions))
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

    private fun AuthSession.toStored() = StoredSession(
        token = token,
        userId = userId,
        createdAt = createdAt.toString(),
        expiresAt = expiresAt.toString()
    )

    private fun StoredSession.toSession() = AuthSession(
        token = token,
        userId = userId,
        createdAt = Instant.parse(createdAt),
        expiresAt = Instant.parse(expiresAt)
    )
}
