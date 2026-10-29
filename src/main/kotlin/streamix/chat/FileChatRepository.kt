package streamix.chat

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import streamix.api.ChatMessageRef
import streamix.api.ChatRoomType
import streamix.api.MessageConversationRef
import streamix.auth.UserRepository
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class FileChatRepository(
    root: Path,
    private val users: UserRepository,
    private val gson: Gson = Gson()
) : ChatRepository {
    private val root: Path = root.toAbsolutePath().normalize()
    private val file = this.root.resolve("messages.json")
    private val lock: Any = locks.computeIfAbsent(file) { Any() }

    companion object {
        // Serialize read-modify-write across instances in this JVM. This is not cross-process locking.
        private val locks = ConcurrentHashMap<Path, Any>()
    }

    init {
        Files.createDirectories(root)
        if (!Files.exists(file)) write(emptyList())
    }

    override fun global(viewerId: String, page: Int, perPage: Int): List<ChatMessageRef> = synchronized(lock) {
        paginate(
            read().filter { it.roomType == ChatRoomType.GLOBAL }.filter { visibleTo(it, viewerId) },
            page,
            perPage
        ).mapNotNull(::toApi)
    }

    override fun anime(viewerId: String, mediaId: Long, page: Int, perPage: Int): List<ChatMessageRef> = synchronized(lock) {
        paginate(read().filter { it.roomType == ChatRoomType.ANIME && it.mediaId == mediaId }.filter { visibleTo(it, viewerId) }, page, perPage).mapNotNull(::toApi)
    }

    override fun sendGlobal(senderId: String, content: String) =
        create(senderId, ChatRoomType.GLOBAL, null, null, content, true)

    override fun sendAnime(senderId: String, mediaId: Long, content: String) =
        create(senderId, ChatRoomType.ANIME, mediaId, null, content, true)

    override fun conversations(userId: String, page: Int, perPage: Int): List<MessageConversationRef> = synchronized(lock) {
        val messages = read().filter {
            it.roomType == ChatRoomType.DIRECT && (it.senderId == userId || it.recipientId == userId)
        }
        val latest = messages.groupBy { if (it.senderId == userId) it.recipientId else it.senderId }
            .mapNotNull { (otherId, items) ->
                val other = otherId ?: return@mapNotNull null
                val last = items.maxByOrNull { it.createdAt } ?: return@mapNotNull null
                val lastApi = toApi(last) ?: return@mapNotNull null
                MessageConversationRef(
                    user = users.findById(other) ?: return@mapNotNull null,
                    lastMessage = lastApi,
                    unreadCount = items.count { it.recipientId == userId && !it.read }
                )
            }
            .sortedByDescending { it.lastMessage.createdAt }
        paginate(latest, page, perPage)
    }

    override fun messages(userId: String, otherUserId: String, page: Int, perPage: Int): List<ChatMessageRef> = synchronized(lock) {
        if (users.findById(otherUserId) == null) return@synchronized emptyList()
        paginate(
            read().filter {
                it.roomType == ChatRoomType.DIRECT &&
                    ((it.senderId == userId && it.recipientId == otherUserId) ||
                        (it.senderId == otherUserId && it.recipientId == userId))
            },
            page,
            perPage
        ).mapNotNull(::toApi)
    }

    override fun sendMessage(senderId: String, recipientId: String, content: String): ChatMessageRef {
        require(senderId != recipientId) { "cannot message yourself" }
        require(users.findById(senderId) != null) { "sender not found" }
        require(users.findById(recipientId) != null) { "recipient not found" }
        require(!blockedEitherWay(senderId, recipientId)) { "messaging is blocked" }
        return create(senderId, ChatRoomType.DIRECT, null, recipientId, content, false)
    }

    override fun unreadCount(userId: String) = synchronized(lock) {
        read().count { it.roomType == ChatRoomType.DIRECT && it.recipientId == userId && !it.read }
    }

    override fun markRead(userId: String, otherUserId: String) = synchronized(lock) {
        val items = read()
        var changed = 0
        val updated = items.map {
            if (it.roomType == ChatRoomType.DIRECT && it.senderId == otherUserId && it.recipientId == userId && !it.read) {
                changed++
                it.copy(read = true)
            } else it
        }
        if (changed > 0) write(updated)
        changed
    }

    private fun create(
        senderId: String,
        roomType: ChatRoomType,
        mediaId: Long?,
        recipientId: String?,
        content: String,
        read: Boolean
    ): ChatMessageRef = synchronized(lock) {
        require(users.findById(senderId) != null) { "sender not found" }
        val clean = content.trim()
        require(clean.isNotBlank()) { "message content is required" }
        require(clean.length <= 4000) { "message is too long" }
        val stored = StoredMessage(UUID.randomUUID().toString(), roomType, senderId, recipientId, mediaId, clean, read, Instant.now().toString())
        write(read() + stored)
        toApi(stored)!!
    }

    private fun visibleTo(item: StoredMessage, pageViewerId: String?): Boolean {
        if (pageViewerId == null) return true
        return !users.relationship(pageViewerId, item.senderId).blocked
    }

    private fun blockedEitherWay(a: String, b: String): Boolean =
        users.relationship(a, b).blocked || users.relationship(b, a).blocked

    private fun toApi(item: StoredMessage): ChatMessageRef? {
        val sender = users.findById(item.senderId) ?: return null
        val recipient = item.recipientId?.let(users::findById)
        return ChatMessageRef(item.id, item.roomType, sender, recipient, item.mediaId, item.content, item.read, item.createdAt)
    }

    private fun <T> paginate(items: List<T>, page: Int, perPage: Int): List<T> {
        val safePage = page.coerceAtLeast(1)
        val safePerPage = perPage.coerceIn(1, 101)
        val offset = (safePage.toLong() - 1L) * safePerPage.toLong()
        if (offset >= items.size.toLong()) return emptyList()
        return items.drop(offset.toInt()).take(safePerPage)
    }

    private fun read(): List<StoredMessage> {
        if (!Files.exists(file)) return emptyList()
        val json = Files.readString(file, StandardCharsets.UTF_8)
        if (json.isBlank()) return emptyList()
        val type = object : TypeToken<List<StoredMessage>>() {}.type
        return gson.fromJson<List<StoredMessage>>(json, type) ?: throw java.io.IOException("Persisted JSON must be a non-null array: $file")
    }

    private fun write(items: List<StoredMessage>) {
        val temp = Files.createTempFile(root, "messages-", ".tmp")
        try {
            Files.writeString(temp, gson.toJson(items), StandardCharsets.UTF_8)
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private data class StoredMessage(
        val id: String,
        val roomType: ChatRoomType,
        val senderId: String,
        val recipientId: String?,
        val mediaId: Long?,
        val content: String,
        val read: Boolean,
        val createdAt: String
    )
}
