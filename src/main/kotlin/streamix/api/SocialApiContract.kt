package streamix.api

/**
 * Social/community contract owned entirely by the Shinigami backend.
 *
 * These resources are intentionally independent from AniList GraphQL models.
 * AniList media IDs may be referenced for anime context, but user/social
 * identity and state are Shinigami-owned.
 */
object SocialApiContract {
    const val REPORTS = "/api/v1/social/reports"
    const val FEED = "/api/v1/social/feed"
    const val ACTIVITIES = "/api/v1/social/activities"
    const val ACTIVITY = "/api/v1/social/activities/{activityId}"
    const val ACTIVITY_REPLIES = "/api/v1/social/activities/{activityId}/replies"
    const val ACTIVITY_LIKE = "/api/v1/social/activities/{activityId}/like"
    const val ACTIVITY_SUBSCRIBE = "/api/v1/social/activities/{activityId}/subscribe"

    const val COMMENTS = "/api/v1/social/comments"
    const val GIVEAWAYS = "/api/v1/social/giveaways"
    const val COMMENT = "/api/v1/social/comments/{commentId}"
    const val COMMENT_REPLIES = "/api/v1/social/comments/{commentId}/replies"
    const val COMMENT_VOTE = "/api/v1/social/comments/{commentId}/vote"


    const val NOTIFICATIONS = "/api/v1/social/notifications"
    const val NOTIFICATION_READ = "/api/v1/social/notifications/{notificationId}/read"
    const val NOTIFICATION_COUNT = "/api/v1/social/notifications/unread-count"
}

data class ActivityRef(
    val id: String,
    val author: ShinigamiUser,
    val type: String,
    val text: String? = null,
    val mediaId: Long? = null,
    val mediaTitle: String? = null,
    val replyCount: Int = 0,
    val likeCount: Int = 0,
    val isLiked: Boolean = false,
    val isSubscribed: Boolean = false,
    val createdAt: String
)

data class ActivityReplyRef(
    val id: String,
    val activityId: String,
    val author: ShinigamiUser,
    val text: String,
    val likeCount: Int = 0,
    val isLiked: Boolean = false,
    val createdAt: String
)

data class SocialComment(
    val id: String,
    val author: ShinigamiUser,
    val mediaId: Long,
    val parentCommentId: String? = null,
    val content: String,
    val tag: Int? = null,
    val upvotes: Int = 0,
    val downvotes: Int = 0,
    val userVote: Int? = null,
    val replyCount: Int = 0,
    val deleted: Boolean = false,
    val createdAt: String,
    val updatedAt: String? = null
)

data class NotificationRef(
    val id: String,
    val type: String,
    val actor: ShinigamiUser? = null,
    val activityId: String? = null,
    val commentId: String? = null,
    val mediaId: Long? = null,
    val message: String? = null,
    val read: Boolean,
    val createdAt: String
)

data class SocialPage<T>(
    val items: List<T>,
    val page: Int,
    val perPage: Int,
    val hasNextPage: Boolean,
    val total: Long? = null
)
