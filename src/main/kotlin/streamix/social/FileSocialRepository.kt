package streamix.social

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import streamix.api.ActivityRef
import streamix.api.ActivityReplyRef
import streamix.api.ShinigamiUser
import streamix.api.SocialComment
import streamix.api.NotificationRef
import streamix.auth.UserRepository
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private data class StoredActivity(
    val id: String,
    val authorId: String,
    val type: String,
    val text: String?,
    val mediaId: Long?,
    val mediaTitle: String?,
    val createdAt: String,
    val likedBy: Set<String> = emptySet(),
    val subscribedBy: Set<String> = emptySet()
)

private data class StoredActivityReply(
    val id: String,
    val activityId: String,
    val authorId: String,
    val text: String,
    val createdAt: String,
    val likedBy: Set<String> = emptySet()
)

private data class StoredComment(
    val id: String,
    val mediaId: Long,
    val parentCommentId: String?,
    val authorId: String,
    val content: String,
    val tag: Int? = null,
    val upvotesBy: Set<String> = emptySet(),
    val downvotesBy: Set<String> = emptySet(),
    val deleted: Boolean = false,
    val createdAt: String,
    val updatedAt: String? = null
)

private data class StoredNotification(
    val id: String,
    val userId: String,
    val type: String,
    val actorId: String?,
    val activityId: String?,
    val commentId: String?,
    val mediaId: Long?,
    val message: String?,
    val read: Boolean,
    val createdAt: String
)

class FileSocialRepository(
    root: Path,
    private val users: UserRepository,
    private val gson: Gson = Gson()
) : SocialRepository {
    private val root: Path = root.toAbsolutePath().normalize()
    private val lock: Any = locks.computeIfAbsent(this.root) { Any() }

    init {
        Files.createDirectories(this.root)
    }

    companion object {
        /**
         * File-backed repositories sharing a directory must serialize read-modify-write
         * operations across instances in this JVM, not merely within each instance.
         * This intentionally does not claim cross-process locking.
         */
        private val locks = ConcurrentHashMap<Path, Any>()
    }

    override fun activities(userId: String?, page: Int, perPage: Int): List<ActivityRef> = synchronized(lock) {
        paginate(read<StoredActivity>("activities.json").sortedWith(compareByDescending<StoredActivity> { timestampOrder(it.createdAt) }.thenByDescending { it.createdAt }), page, perPage)
            .mapNotNull { activity(it, userId) }
    }

    override fun activity(activityId: String, viewerId: String?): ActivityRef? = synchronized(lock) {
        read<StoredActivity>("activities.json").firstOrNull { it.id == activityId }?.let { activity(it, viewerId) }
    }

    override fun createActivity(authorId: String, type: String, text: String?, mediaId: Long?, mediaTitle: String?): ActivityRef =
        synchronized(lock) {
            require(users.findById(authorId) != null) { "author not found" }
            val normalizedType = type.trim()
            require(normalizedType.isNotBlank() && normalizedType.length <= 40) {
                "activity type must contain 1 to 40 characters"
            }
            val normalizedText = text?.trim()
            require(normalizedText == null || normalizedText.length <= 5000) {
                "activity text must be at most 5000 characters"
            }
            require(mediaId == null || mediaId > 0L) { "mediaId must be positive" }
            val normalizedMediaTitle = mediaTitle?.trim()
            val stored = StoredActivity(UUID.randomUUID().toString(), authorId, normalizedType, normalizedText, mediaId, normalizedMediaTitle, Instant.now().toString())
            write("activities.json", read<StoredActivity>("activities.json") + stored)
            activity(stored, authorId)!!
        }

    override fun deleteActivity(activityId: String, actorId: String): Boolean = synchronized(lock) {
        val items = read<StoredActivity>("activities.json")
        val index = items.indexOfFirst { it.id == activityId }
        if (index < 0) return false
        if (items[index].authorId != actorId) return false
        write("activities.json", items.filterIndexed { itemIndex, _ -> itemIndex != index })
        true
    }

    override fun replies(activityId: String, viewerId: String?, page: Int, perPage: Int): List<ActivityReplyRef> = synchronized(lock) {
        if (read<StoredActivity>("activities.json").none { it.id == activityId }) return@synchronized emptyList()
        paginate(read<StoredActivityReply>("activity-replies.json").filter { it.activityId == activityId }.sortedWith(compareBy<StoredActivityReply> { timestampOrder(it.createdAt) }.thenBy { it.createdAt }), page, perPage)
            .mapNotNull { reply ->
                val author = users.findById(reply.authorId) ?: return@mapNotNull null
                ActivityReplyRef(reply.id, reply.activityId, author, reply.text, reply.likedBy.size, viewerId in reply.likedBy, reply.createdAt)
            }
    }

    override fun createReply(activityId: String, authorId: String, text: String): ActivityReplyRef = synchronized(lock) {
        val normalized = text.trim()
        require(normalized.isNotBlank() && normalized.length <= 5000) {
            "reply text must contain 1 to 5000 characters"
        }
        require(activity(activityId, authorId) != null) { "activity not found" }
        require(users.findById(authorId) != null) { "author not found" }
        val parentActivity = read<StoredActivity>("activities.json").firstOrNull { it.id == activityId }
            ?: throw IllegalArgumentException("activity not found")
        val stored = StoredActivityReply(UUID.randomUUID().toString(), activityId, authorId, normalized, Instant.now().toString())
        val previousReplies = read<StoredActivityReply>("activity-replies.json")
        write("activity-replies.json", previousReplies + stored)
        if (parentActivity.authorId != authorId) {
            try {
                appendNotification(
                    userId = parentActivity.authorId,
                    type = "ACTIVITY_REPLY",
                    actorId = authorId,
                    activityId = activityId,
                    commentId = null,
                    mediaId = parentActivity.mediaId,
                    message = "replied to your activity"
                )
            } catch (failure: Exception) {
                // Keep the reply and its notification consistent when the second
                // persistence step fails. The rollback is best-effort; preserve the
                // original persistence failure and attach any rollback failure.
                runCatching { write("activity-replies.json", previousReplies) }
                    .exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        }
        ActivityReplyRef(
            id = stored.id,
            activityId = stored.activityId,
            author = users.findById(authorId) ?: throw IllegalStateException("author not found"),
            text = stored.text,
            likeCount = stored.likedBy.size,
            isLiked = authorId in stored.likedBy,
            createdAt = stored.createdAt
        )
    }

    override fun toggleActivityLike(activityId: String, userId: String): ActivityRef? = synchronized(lock) {
        mutateActivity(activityId) { it.copy(likedBy = toggle(it.likedBy, userId)) }?.let { activity(it, userId) }
    }

    override fun toggleActivitySubscription(activityId: String, userId: String): ActivityRef? = synchronized(lock) {
        mutateActivity(activityId) { it.copy(subscribedBy = toggle(it.subscribedBy, userId)) }?.let { activity(it, userId) }
    }

    override fun findComment(commentId: String, viewerId: String?): SocialComment? = synchronized(lock) {
        read<StoredComment>("comments.json").firstOrNull { it.id == commentId && !it.deleted }?.let { commentToApi(it, viewerId) }
    }

    override fun comments(mediaId: Long, viewerId: String?, parentCommentId: String?, page: Int, perPage: Int): List<SocialComment> = synchronized(lock) {
        paginate(read<StoredComment>("comments.json").filter { it.mediaId == mediaId && it.parentCommentId == parentCommentId && !it.deleted }.sortedWith(compareBy<StoredComment> { timestampOrder(it.createdAt) }.thenBy { it.createdAt }), page, perPage)
            .mapNotNull { comment -> commentToApi(comment, viewerId) }
    }

    override fun commentReplies(commentId: String, viewerId: String?, page: Int, perPage: Int): List<SocialComment> = synchronized(lock) {
        val items = read<StoredComment>("comments.json")
        val parent = items.firstOrNull { it.id == commentId && !it.deleted } ?: return@synchronized emptyList()
        paginate(items.filter { it.mediaId == parent.mediaId && it.parentCommentId == commentId && !it.deleted }.sortedWith(compareBy<StoredComment> { timestampOrder(it.createdAt) }.thenBy { it.createdAt }), page, perPage)
            .mapNotNull { commentToApi(it, viewerId) }
    }

    override fun createComment(mediaId: Long, authorId: String, content: String, parentCommentId: String?, tag: Int?): SocialComment = synchronized(lock) {
        require(mediaId > 0L) { "mediaId must be positive" }
        require(users.findById(authorId) != null) { "author not found" }
        val items = read<StoredComment>("comments.json")
        if (parentCommentId != null) {
            val parent = items.firstOrNull { it.id == parentCommentId && !it.deleted }
                ?: throw IllegalArgumentException("parent comment not found")
            require(parent.mediaId == mediaId) { "parent comment belongs to a different anime" }
        }
        val normalized = content.trim()
        require(normalized.isNotBlank() && normalized.length <= 5000) { "comment content must contain 1 to 5000 characters" }
        val stored = StoredComment(UUID.randomUUID().toString(), mediaId, parentCommentId, authorId, normalized, tag = tag, createdAt = Instant.now().toString())
        val recipientId = parentCommentId?.let { parentId ->
            items.firstOrNull { it.id == parentId && !it.deleted }?.authorId
        }
        write("comments.json", items + stored)
        if (recipientId != null && recipientId != authorId) {
            try {
                appendNotification(
                    userId = recipientId,
                    type = "COMMENT_REPLY",
                    actorId = authorId,
                    activityId = null,
                    commentId = stored.id,
                    mediaId = mediaId,
                    message = "replied to your comment"
                )
            } catch (failure: Exception) {
                // A reply must not remain committed when its notification fails.
                runCatching { write("comments.json", items) }
                    .exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        }
        commentToApi(stored, authorId)!!
    }

    override fun deleteComment(commentId: String, actorId: String): Boolean = synchronized(lock) {
        val items = read<StoredComment>("comments.json")
        val index = items.indexOfFirst { it.id == commentId && !it.deleted }
        if (index < 0) return false
        if (items[index].authorId != actorId) return false
        val changed = items.toMutableList()
        changed[index] = changed[index].copy(deleted = true, updatedAt = Instant.now().toString())
        write("comments.json", changed)
        true
    }

    override fun editComment(commentId: String, actorId: String, content: String): SocialComment? = synchronized(lock) {
        val normalized = content.trim()
        require(normalized.isNotBlank() && normalized.length <= 5000) {
            "comment content must contain 1 to 5000 characters"
        }
        val items = read<StoredComment>("comments.json")
        val index = items.indexOfFirst { it.id == commentId && !it.deleted }
        if (index < 0) return null
        val item = items[index]
        if (item.authorId != actorId) return null
        val updated = item.copy(content = normalized, updatedAt = Instant.now().toString())
        val changed = items.toMutableList()
        changed[index] = updated
        write("comments.json", changed)
        commentToApi(updated, actorId)
    }

    override fun voteComment(commentId: String, userId: String, vote: Int?): SocialComment? = synchronized(lock) {
        require(vote == null || vote == -1 || vote == 1) { "vote must be -1, 1, or null" }
        val items = read<StoredComment>("comments.json")
        val index = items.indexOfFirst { it.id == commentId && !it.deleted }
        if (index < 0) return null
        val item = items[index]
        val updated = item.copy(
            upvotesBy = item.upvotesBy - userId + if (vote == 1) setOf(userId) else emptySet(),
            downvotesBy = item.downvotesBy - userId + if (vote == -1) setOf(userId) else emptySet()
        )
        val changed = items.toMutableList()
        changed[index] = updated
        write("comments.json", changed)
        commentToApi(updated, userId)
    }

    override fun notifications(userId: String, page: Int, perPage: Int): List<NotificationRef> = synchronized(lock) {
        paginate(read<StoredNotification>("notifications.json").filter { it.userId == userId }.sortedWith(compareByDescending<StoredNotification> { timestampOrder(it.createdAt) }.thenByDescending { it.createdAt }), page, perPage)
            .mapNotNull { notificationToApi(it) }
    }

    override fun unreadNotificationCount(userId: String): Int =
        synchronized(lock) { read<StoredNotification>("notifications.json").count { it.userId == userId && !it.read } }

    override fun markNotificationRead(notificationId: String, userId: String): Boolean = synchronized(lock) {
        val items = read<StoredNotification>("notifications.json")
        val index = items.indexOfFirst { it.id == notificationId && it.userId == userId }
        if (index < 0) return false
        val changed = items.toMutableList()
        changed[index] = changed[index].copy(read = true)
        write("notifications.json", changed)
        true
    }

    private fun activity(item: StoredActivity, viewerId: String?): ActivityRef? {
        val author = users.findById(item.authorId) ?: return null
        val replies = read<StoredActivityReply>("activity-replies.json").count { it.activityId == item.id }
        return ActivityRef(item.id, author, item.type, item.text, item.mediaId, item.mediaTitle, replies, item.likedBy.size, viewerId in item.likedBy, viewerId in item.subscribedBy, item.createdAt)
    }

    private fun commentToApi(item: StoredComment, viewerId: String?): SocialComment? {
        val author = users.findById(item.authorId) ?: return null
        val vote = when {
            viewerId != null && viewerId in item.upvotesBy -> 1
            viewerId != null && viewerId in item.downvotesBy -> -1
            else -> null
        }
        val replies = read<StoredComment>("comments.json").count { it.mediaId == item.mediaId && it.parentCommentId == item.id && !it.deleted }
        return SocialComment(item.id, author, item.mediaId, item.parentCommentId, item.content, item.tag, item.upvotesBy.size, item.downvotesBy.size, vote, replies, item.deleted, item.createdAt, item.updatedAt)
    }

    private fun appendNotification(
        userId: String,
        type: String,
        actorId: String,
        activityId: String?,
        commentId: String?,
        mediaId: Long?,
        message: String
    ) {
        val notification = StoredNotification(
            id = UUID.randomUUID().toString(),
            userId = userId,
            type = type,
            actorId = actorId,
            activityId = activityId,
            commentId = commentId,
            mediaId = mediaId,
            message = message,
            read = false,
            createdAt = Instant.now().toString()
        )
        write("notifications.json", read<StoredNotification>("notifications.json") + notification)
    }

    private fun notificationToApi(item: StoredNotification): NotificationRef? {
        val actor = item.actorId?.let(users::findById)
        return NotificationRef(item.id, item.type, actor, item.activityId, item.commentId, item.mediaId, item.message, item.read, item.createdAt)
    }

    private fun mutateActivity(id: String, transform: (StoredActivity) -> StoredActivity): StoredActivity? {
        val items = read<StoredActivity>("activities.json")
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return null
        val updated = transform(items[index])
        val changed = items.toMutableList()
        changed[index] = updated
        write("activities.json", changed)
        return updated
    }

    private fun toggle(current: Set<String>, userId: String): Set<String> =
        if (userId in current) current - userId else current + userId

    private inline fun <reified T> read(name: String): List<T> {
        val file = root.resolve(name)
        if (!Files.exists(file)) return emptyList()
        val text = Files.readString(file)
        if (text.isBlank()) return emptyList()
        return gson.fromJson(text, object : TypeToken<List<T>>() {}.type) ?: throw java.io.IOException("Persisted JSON must be a non-null array: $file")
    }

    private fun <T> write(name: String, items: List<T>) {
        val file = root.resolve(name)
        val temp = Files.createTempFile(root, "$name.", ".tmp")
        try {
            Files.writeString(temp, gson.toJson(items))
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun timestampOrder(value: String): Instant =
        runCatching { Instant.parse(value) }.getOrDefault(Instant.MIN)

    private fun <T> paginate(items: List<T>, page: Int, perPage: Int): List<T> {
        val safePage = page.coerceAtLeast(1)
        val safeSize = perPage.coerceIn(1, 101)
        val offset = (safePage.toLong() - 1L) * safeSize.toLong()
        if (offset >= items.size.toLong()) return emptyList()
        return items.drop(offset.toInt()).take(safeSize)
    }
}
