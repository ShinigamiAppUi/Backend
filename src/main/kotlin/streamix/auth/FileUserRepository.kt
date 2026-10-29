package streamix.auth

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import streamix.api.ShinigamiUser
import streamix.api.UserRelationship
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

private data class StoredUser(
    val id: String,
    val username: String,
    val displayName: String?,
    val bio: String?,
    val avatarUrl: String?,
    val bannerUrl: String?,
    val isFollowing: Boolean,
    val isFollower: Boolean,
    val isBlocked: Boolean,
    val followingUserIds: Set<String> = emptySet(),
    val blockedUserIds: Set<String> = emptySet(),
    val isAdmin: Boolean,
    val isModerator: Boolean,
    val createdAt: String?,
    val externalProvider: String,
    val externalSubject: String,
    val email: String?
)

class FileUserRepository(
    file: Path,
    private val gson: Gson = Gson()
) : UserRepository {
    private val file = file.toAbsolutePath().normalize()
    // Repository instances targeting the same normalized file must share the mutation lock.
    private val lock = locks.computeIfAbsent(this.file) { Any() }

    override fun findById(id: String): ShinigamiUser? = synchronized(lock) {
        read().firstOrNull { it.id == id }?.toApiUser()
    }

    override fun findByExternalIdentity(provider: String, subject: String): ShinigamiUser? =
        synchronized(lock) {
            read().firstOrNull {
                it.externalProvider == provider && it.externalSubject == subject
            }?.toApiUser()
        }

    override fun findByUsername(username: String): ShinigamiUser? = synchronized(lock) {
        read().firstOrNull { it.username.equals(username, ignoreCase = true) }?.toApiUser()
    }

    override fun count(): Long = synchronized(lock) {
        read().size.toLong()
    }

    override fun allIds(): List<String> = synchronized(lock) {
        read().map { it.id }
    }

    override fun search(query: String, page: Int, perPage: Int): List<ShinigamiUser> =
        synchronized(lock) {
            val normalized = query.trim().lowercase()
            val safePage = page.coerceAtLeast(1)
            val safeSize = perPage.coerceIn(1, 101)
            val offset = (safePage.toLong() - 1L) * safeSize
            if (offset > Int.MAX_VALUE) return@synchronized emptyList()
            read()
                .asSequence()
                .filter {
                    normalized.isBlank() ||
                        it.username.lowercase().contains(normalized) ||
                        it.displayName.orEmpty().lowercase().contains(normalized)
                }
                .sortedBy { it.username.lowercase() }
                .drop(offset.toInt())
                .take(safeSize)
                .map { it.toApiUser() }
                .toList()
        }

    override fun create(
        username: String,
        externalProvider: String,
        externalSubject: String,
        displayName: String?,
        email: String?
    ): ShinigamiUser = synchronized(lock) {
        val normalizedUsername = username.trim()
        require(normalizedUsername.isNotBlank()) { "username must not be blank" }
        val users = read()
        require(users.none { it.externalProvider == externalProvider && it.externalSubject == externalSubject }) {
            "user already exists for external identity"
        }
        require(users.none { it.username.trim().equals(normalizedUsername, ignoreCase = true) }) {
            "username already exists"
        }

        val stored = StoredUser(
            id = UUID.randomUUID().toString(),
            username = normalizedUsername,
            displayName = displayName,
            bio = null,
            avatarUrl = null,
            bannerUrl = null,
            isFollowing = false,
            isFollower = false,
            isBlocked = false,
            isAdmin = false,
            isModerator = false,
            createdAt = Instant.now().toString(),
            externalProvider = externalProvider,
            externalSubject = externalSubject,
            email = email
        )
        write(users + stored)
        stored.toApiUser()
    }

    override fun update(user: ShinigamiUser): ShinigamiUser = synchronized(lock) {
        val normalizedUsername = user.username.trim()
        require(normalizedUsername.isNotBlank()) { "username must not be blank" }
        val users = read()
        val current = users.firstOrNull { it.id == user.id }
            ?: error("user not found: ${user.id}")
        require(users.none { it.id != user.id && it.username.trim().equals(normalizedUsername, ignoreCase = true) }) {
            "username already exists"
        }

        val updated = current.copy(
            username = normalizedUsername,
            displayName = user.displayName,
            bio = user.bio,
            avatarUrl = user.avatarUrl,
            bannerUrl = user.bannerUrl,
            isFollowing = user.isFollowing,
            isFollower = user.isFollower,
            isBlocked = user.isBlocked,
            isAdmin = user.isAdmin,
            isModerator = user.isModerator
        )
        write(users.map { if (it.id == user.id) updated else it })
        updated.toApiUser()
    }

    override fun setRelationship(userId: String, targetUserId: String, following: Boolean?, blocked: Boolean?): ShinigamiUser = synchronized(lock) {
        require(userId != targetUserId) { "cannot modify relationship with self" }
        val users = read()
        val current = users.firstOrNull { it.id == userId } ?: error("user not found")
        val target = users.firstOrNull { it.id == targetUserId } ?: error("target user not found")
        val updated = current.copy(
            followingUserIds = following?.let { enabled ->
                if (enabled) current.followingUserIds + targetUserId else current.followingUserIds - targetUserId
            } ?: current.followingUserIds,
            blockedUserIds = blocked?.let { enabled ->
                if (enabled) current.blockedUserIds + targetUserId else current.blockedUserIds - targetUserId
            } ?: current.blockedUserIds
        )
        write(users.map { if (it.id == userId) updated else it })
        updated.toApiUser().copy(
            isFollowing = targetUserId in updated.followingUserIds,
            isFollower = userId in target.followingUserIds,
            isBlocked = targetUserId in updated.blockedUserIds
        )
    }

    override fun relationship(userId: String, targetUserId: String): UserRelationship = synchronized(lock) {
        val users = read()
        val current = users.firstOrNull { it.id == userId } ?: error("user not found")
        val target = users.firstOrNull { it.id == targetUserId } ?: error("target user not found")
        UserRelationship(
            userId = targetUserId,
            following = targetUserId in current.followingUserIds,
            follower = userId in target.followingUserIds,
            blocked = targetUserId in current.blockedUserIds
        )
    }

    override fun countFollowing(userId: String): Long = synchronized(lock) {
        read().firstOrNull { it.id == userId }?.followingUserIds?.size?.toLong() ?: 0L
    }

    override fun countFollowers(userId: String): Long = synchronized(lock) {
        read().count { userId in it.followingUserIds }.toLong()
    }

    override fun following(userId: String, page: Int, perPage: Int): List<ShinigamiUser> = synchronized(lock) {
        val users = read()
        val current = users.firstOrNull { it.id == userId } ?: error("user not found")
        val safePage = page.coerceAtLeast(1)
        val safeSize = perPage.coerceIn(1, 101)
        val offset = (safePage.toLong() - 1L) * safeSize
        if (offset > Int.MAX_VALUE) return@synchronized emptyList()
        current.followingUserIds.asSequence()
            .mapNotNull { id -> users.firstOrNull { it.id == id } }
            .sortedBy { it.username.lowercase() }
            .drop(offset.toInt())
            .take(safeSize)
            .map { it.toApiUser() }
            .toList()
    }

    override fun followers(userId: String, page: Int, perPage: Int): List<ShinigamiUser> = synchronized(lock) {
        val users = read()
        if (users.none { it.id == userId }) error("user not found")
        val safePage = page.coerceAtLeast(1)
        val safeSize = perPage.coerceIn(1, 101)
        val offset = (safePage.toLong() - 1L) * safeSize
        if (offset > Int.MAX_VALUE) return@synchronized emptyList()
        users.asSequence()
            .filter { userId in it.followingUserIds }
            .sortedBy { it.username.lowercase() }
            .drop(offset.toInt())
            .take(safeSize)
            .map { it.toApiUser() }
            .toList()
    }

    private fun read(): List<StoredUser> {
        if (!Files.exists(file)) return emptyList()
        val json = Files.readString(file)
        if (json.isBlank()) return emptyList()
        val users = gson.fromJson<List<StoredUser>>(
            json,
            object : TypeToken<List<StoredUser>>() {}.type
        ) ?: throw java.io.IOException("Persisted JSON must be a non-null array: $file")
        // Gson bypasses Kotlin constructor defaults. Older user records written before
        // relationship sets existed (or with explicit JSON nulls) deserialize these
        // fields as null, so normalize them before any relationship operation.
        return users.map { user ->
            user.copy(
                followingUserIds = user.followingUserIds.orEmpty(),
                blockedUserIds = user.blockedUserIds.orEmpty()
            )
        }
    }

    private fun write(users: List<StoredUser>) {
        val parent = file.parent ?: throw java.io.IOException("User file must have a parent directory: $file")
        Files.createDirectories(parent)
        val temp = Files.createTempFile(parent, "users-", ".tmp")
        try {
            Files.writeString(temp, gson.toJson(users))
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
        val locks = java.util.concurrent.ConcurrentHashMap<Path, Any>()
    }

    private fun StoredUser.toApiUser() = ShinigamiUser(
        id = id,
        username = username,
        displayName = displayName,
        bio = bio,
        avatarUrl = avatarUrl,
        bannerUrl = bannerUrl,
        isFollowing = isFollowing,
        isFollower = isFollower,
        isBlocked = isBlocked,
        isAdmin = isAdmin,
        isModerator = isModerator,
        createdAt = createdAt
    )
}
