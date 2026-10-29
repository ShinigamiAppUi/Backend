package streamix.admin

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import streamix.api.AdminRole
import streamix.api.ShinigamiNotification
import streamix.auth.FileUserRepository
import streamix.notification.FileNotificationRepository
import streamix.notification.NotificationEventPublisher
import streamix.notification.NotificationRepository
import streamix.notification.NotificationService

class AnnouncementDeliveryRetryTest {
    @Test
    fun retryAfterPartialFanOutCompletesRecipientsWithoutDuplicateNotificationsOrAudit() {
        val root = Files.createTempDirectory("shinigami-announcement-delivery-retry")
        val users = FileUserRepository(root.resolve("users.json"))
        val admin = users.create("admin", "test", "admin")
        val firstReader = users.create("reader-one", "test", "reader-one")
        val lastReader = users.create("reader-two", "test", "reader-two")

        val roles = FileAdminRoleRepository(root.resolve("admin"))
        roles.setRole(admin.id, AdminRole.ADMIN)
        val access = AdminAccessService(roles)
        val storedNotifications = FileNotificationRepository(root.resolve("notifications"), users)
        val flakyNotifications = FailAfterPersistForRecipient(
            delegate = storedNotifications,
            recipientId = lastReader.id
        )
        val publisher = NotificationEventPublisher(NotificationService(flakyNotifications))
        val announcements = FileAnnouncementRepository(root.resolve("announcements"))
        val audit = FileAdminAuditRepository(root.resolve("admin"))
        val service = AnnouncementService(users, access, announcements, publisher, audit)
        val announcement = service.create(admin.id, "Maintenance", "Scheduled maintenance", null)

        assertFailsWith<IllegalStateException> {
            service.publish(admin.id, announcement.id)
        }
        assertEquals(true, announcements.find(announcement.id)?.published)
        assertEquals(1, storedNotifications.list(firstReader.id, 1, 100).size)

        service.publish(admin.id, announcement.id)
        service.publish(admin.id, announcement.id)

        assertEquals(1, storedNotifications.list(firstReader.id, 1, 100).count {
            it.targetType == "announcement" && it.targetId == announcement.id
        })
        assertEquals(1, storedNotifications.list(lastReader.id, 1, 100).count {
            it.targetType == "announcement" && it.targetId == announcement.id
        })
        assertEquals(1, audit.recent(100).count {
            it.action == "ANNOUNCEMENT_PUBLISHED" && it.targetId == announcement.id
        })
    }

    private class FailAfterPersistForRecipient(
        private val delegate: NotificationRepository,
        private val recipientId: String
    ) : NotificationRepository {
        private var failed = false

        override fun list(userId: String, page: Int, perPage: Int) =
            delegate.list(userId, page, perPage)

        override fun unreadCount(userId: String) = delegate.unreadCount(userId)

        override fun create(
            userId: String,
            type: String,
            title: String,
            body: String,
            imageUrl: String?,
            targetType: String?,
            targetId: String?,
            mediaId: Long?,
            actorId: String?,
            metadata: Map<String, String>
        ): ShinigamiNotification {
            val created = delegate.create(
                userId, type, title, body, imageUrl, targetType, targetId, mediaId, actorId, metadata
            )
            if (userId == recipientId && !failed) {
                failed = true
                throw IllegalStateException("simulated failure after notification persistence")
            }
            return created
        }

        override fun markRead(userId: String, notificationId: String) =
            delegate.markRead(userId, notificationId)

        override fun markAllRead(userId: String) = delegate.markAllRead(userId)
    }
}
