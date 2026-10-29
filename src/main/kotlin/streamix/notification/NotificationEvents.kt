package streamix.notification

/**
 * Backend-facing notification events.
 *
 * Providers/runtime do not depend on notification storage. A higher-level
 * orchestration layer can publish these events after it has resolved the
 * affected Shinigami users.
 */
data class NewEpisodeEvent(
    val mediaId: Long,
    val title: String,
    val episode: Int,
    val recipientUserIds: List<String>,
    val imageUrl: String? = null
)

data class AnnouncementEvent(
    val title: String,
    val body: String,
    val recipientUserIds: List<String>,
    val imageUrl: String? = null,
    val announcementId: String? = null
)

class NotificationEventPublisher(
    private val service: NotificationService
) {
    fun publish(event: NewEpisodeEvent) {
        event.recipientUserIds.distinct().forEach { userId ->
            service.episode(
                userId = userId,
                mediaId = event.mediaId,
                title = event.title,
                episode = event.episode,
                imageUrl = event.imageUrl
            )
        }
    }

    fun publish(event: AnnouncementEvent) {
        event.recipientUserIds.distinct().forEach { userId ->
            service.announcement(
                userId = userId,
                title = event.title,
                body = event.body,
                imageUrl = event.imageUrl,
                targetId = event.announcementId
            )
        }
    }
}
