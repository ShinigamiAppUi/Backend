package streamix.api

/**
 * Shinigami-owned notification contract.
 *
 * Notifications are persisted by the backend and are independent from AniList.
 * Anime metadata may be referenced by mediaId, but notification ownership stays
 * with the Shinigami account.
 */
object NotificationApiContract {
    const val NOTIFICATIONS = "/api/v1/notifications"
    const val UNREAD_COUNT = "/api/v1/notifications/unread-count"
    const val READ = "/api/v1/notifications/{notificationId}/read"
    const val READ_ALL = "/api/v1/notifications/read-all"
}

enum class NotificationType {
    EPISODE_NEW,
    MESSAGE,
    FOLLOW,
    MENTION,
    REPLY,
    MODERATION,
    ANNOUNCEMENT
}

data class ShinigamiNotification(
    val id: String,
    val type: NotificationType,
    val title: String,
    val body: String,
    val imageUrl: String? = null,
    val targetType: String? = null,
    val targetId: String? = null,
    val mediaId: Long? = null,
    val actor: ShinigamiUser? = null,
    val metadata: Map<String, String> = emptyMap(),
    val read: Boolean = false,
    val createdAt: String
)

data class NotificationPage(
    val items: List<ShinigamiNotification>,
    val page: Int,
    val perPage: Int,
    val hasNextPage: Boolean
)
