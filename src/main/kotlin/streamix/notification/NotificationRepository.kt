package streamix.notification

import streamix.api.ShinigamiNotification

interface NotificationRepository {
    fun list(userId: String, page: Int, perPage: Int): List<ShinigamiNotification>
    fun unreadCount(userId: String): Int
    fun create(
        userId: String,
        type: String,
        title: String,
        body: String,
        imageUrl: String? = null,
        targetType: String? = null,
        targetId: String? = null,
        mediaId: Long? = null,
        actorId: String? = null,
        metadata: Map<String, String> = emptyMap()
    ): ShinigamiNotification
    fun markRead(userId: String, notificationId: String): Boolean
    fun markAllRead(userId: String): Int
}
