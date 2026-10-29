package streamix.notification

import streamix.api.NotificationPage
import streamix.api.NotificationType
import streamix.api.ShinigamiNotification

class NotificationService(
    private val repository: NotificationRepository
) {
    fun list(userId: String, page: Int = 1, perPage: Int = 30): NotificationPage {
        val safePage = page.coerceAtLeast(1)
        val safePerPage = perPage.coerceIn(1, 100)
        val fetched = repository.list(userId, safePage, safePerPage + 1)
        return NotificationPage(
            items = fetched.take(safePerPage),
            page = safePage,
            perPage = safePerPage,
            hasNextPage = fetched.size > safePerPage
        )
    }

    fun unreadCount(userId: String): Int =
        repository.unreadCount(userId)

    fun markRead(userId: String, notificationId: String): Boolean =
        repository.markRead(userId, notificationId)

    fun markAllRead(userId: String): Int =
        repository.markAllRead(userId)

    fun create(
        userId: String,
        type: NotificationType,
        title: String,
        body: String,
        imageUrl: String? = null,
        targetType: String? = null,
        targetId: String? = null,
        mediaId: Long? = null,
        actorId: String? = null,
        metadata: Map<String, String> = emptyMap()
    ): ShinigamiNotification =
        repository.create(
            userId = userId,
            type = type.name,
            title = title,
            body = body,
            imageUrl = imageUrl,
            targetType = targetType,
            targetId = targetId,
            mediaId = mediaId,
            actorId = actorId,
            metadata = metadata
        )

    fun message(recipientId: String, senderId: String, preview: String, messageId: String): ShinigamiNotification =
        create(
            userId = recipientId,
            type = NotificationType.MESSAGE,
            title = "New message",
            body = preview.take(180),
            targetType = "message",
            targetId = senderId,
            actorId = senderId,
            metadata = mapOf("messageId" to messageId)
        )

    fun episode(
        userId: String,
        mediaId: Long,
        title: String,
        episode: Int,
        imageUrl: String? = null
    ): ShinigamiNotification =
        create(
            userId = userId,
            type = NotificationType.EPISODE_NEW,
            title = "New episode",
            body = "$title episode $episode is now available.",
            imageUrl = imageUrl,
            targetType = "episode",
            targetId = "$mediaId:$episode",
            mediaId = mediaId,
            metadata = mapOf("episode" to episode.toString())
        )

    fun announcement(
        userId: String,
        title: String,
        body: String,
        imageUrl: String? = null,
        targetId: String? = null
    ): ShinigamiNotification =
        create(
            userId = userId,
            type = NotificationType.ANNOUNCEMENT,
            title = title,
            body = body,
            imageUrl = imageUrl,
            targetType = "announcement",
            targetId = targetId
        )

}
