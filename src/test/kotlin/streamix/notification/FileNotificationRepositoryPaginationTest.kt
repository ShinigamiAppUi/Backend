package streamix.notification

import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import streamix.auth.FileUserRepository

class FileNotificationRepositoryPaginationTest {
    @Test
    fun hugePageNumberReturnsEmptyListInsteadOfOverflowingOffset() {
        val root = Files.createTempDirectory("shinigami-notification-pagination")
        val users = FileUserRepository(root.resolve("users.json"))
        val user = users.create(
            username = "reader",
            externalProvider = "test",
            externalSubject = "reader"
        )
        val notifications = FileNotificationRepository(root.resolve("notifications"), users)
        notifications.create(
            userId = user.id,
            type = "MESSAGE",
            title = "Test",
            body = "Pagination regression"
        )

        assertTrue(notifications.list(user.id, Int.MAX_VALUE, 100).isEmpty())
    }


    @Test
    fun listOrdersTimestampsChronologicallyWhenFractionalPrecisionDiffers() {
        val root = Files.createTempDirectory("shinigami-notification-timestamp-order")
        val users = FileUserRepository(root.resolve("users.json"))
        val user = users.create("reader", "test", "reader")
        val notificationsRoot = root.resolve("notifications")
        Files.createDirectories(notificationsRoot)
        Files.writeString(
            notificationsRoot.resolve("notifications.json"),
            """[
                {"id":"whole-second","userId":"${user.id}","type":"MESSAGE","title":"Whole second","body":"Older","imageUrl":null,"targetType":null,"targetId":null,"mediaId":null,"actorId":null,"metadata":{},"read":false,"createdAt":"2026-10-15T12:00:00Z"},
                {"id":"fractional-second","userId":"${user.id}","type":"MESSAGE","title":"Fractional second","body":"Newer","imageUrl":null,"targetType":null,"targetId":null,"mediaId":null,"actorId":null,"metadata":{},"read":false,"createdAt":"2026-10-15T12:00:00.100Z"}
            ]""".trimIndent()
        )
        val repository = FileNotificationRepository(notificationsRoot, users)

        assertTrue(repository.list(user.id, 1, 10).first().id == "fractional-second")
    }

    @Test
    fun nullLegacyMetadataIsNormalizedWithoutBreakingNotificationReads() {
        val root = Files.createTempDirectory("shinigami-notification-null-metadata")
        val users = FileUserRepository(root.resolve("users.json"))
        val user = users.create("reader", "test", "reader")
        val notificationsRoot = root.resolve("notifications")
        Files.createDirectories(notificationsRoot)
        Files.writeString(
            notificationsRoot.resolve("notifications.json"),
            """[
                {"id":"legacy","userId":"${user.id}","type":"MESSAGE","title":"Legacy","body":"Older record","imageUrl":null,"targetType":null,"targetId":null,"mediaId":null,"actorId":null,"metadata":null,"read":false,"createdAt":"2026-10-15T12:00:00Z"}
            ]""".trimIndent()
        )

        val repository = FileNotificationRepository(notificationsRoot, users)
        val notification = repository.list(user.id, 1, 10).single()

        assertEquals("legacy", notification.id)
        assertEquals(emptyMap(), notification.metadata)
        assertEquals(1, repository.unreadCount(user.id))
    }

    @Test
    fun retryingAnnouncementNotificationForSameRecipientIsIdempotent() {
        val root = Files.createTempDirectory("shinigami-announcement-notification-idempotency")
        val users = FileUserRepository(root.resolve("users.json"))
        val user = users.create("reader", "test", "reader")
        val repository = FileNotificationRepository(root.resolve("notifications"), users)

        val first = repository.create(
            userId = user.id,
            type = "ANNOUNCEMENT",
            title = "Maintenance",
            body = "Scheduled maintenance",
            targetType = "announcement",
            targetId = "announcement-1"
        )
        val retry = repository.create(
            userId = user.id,
            type = "ANNOUNCEMENT",
            title = "Maintenance",
            body = "Scheduled maintenance",
            targetType = "announcement",
            targetId = "announcement-1"
        )

        assertTrue(first.id == retry.id)
        assertTrue(repository.list(user.id, 1, 100).size == 1)
    }

    @Test
    fun fullFinalPageDoesNotClaimThereIsAnotherPage() {
        val root = Files.createTempDirectory("shinigami-notification-page-contract")
        val users = FileUserRepository(root.resolve("users.json"))
        val user = users.create(
            username = "reader",
            externalProvider = "test",
            externalSubject = "reader"
        )
        val repository = FileNotificationRepository(root.resolve("notifications"), users)
        repository.create(user.id, "MESSAGE", "Test", "One notification")

        val page = NotificationService(repository).list(user.id, page = 1, perPage = 1)
        assertFalse(page.hasNextPage)
        assertTrue(page.items.size == 1)
    }

    @Test
    fun concurrentCreatesAcrossRepositoryInstancesPreserveEveryNotification() {
        val root = Files.createTempDirectory("shinigami-notification-shared-lock")
        val users = FileUserRepository(root.resolve("users.json"))
        val user = users.create("reader", "test", "reader")
        val notificationsRoot = root.resolve("notifications")
        val firstRepository = FileNotificationRepository(notificationsRoot, users)
        val secondRepository = FileNotificationRepository(notificationsRoot, users)
        val executor = Executors.newFixedThreadPool(8)

        try {
            val tasks = (0 until 80).map { index ->
                Callable {
                    val repository = if (index % 2 == 0) firstRepository else secondRepository
                    repository.create(
                        userId = user.id,
                        type = "MESSAGE",
                        title = "Concurrent $index",
                        body = "Notification $index"
                    )
                }
            }
            executor.invokeAll(tasks).forEach { it.get() }
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
        }

        assertTrue(firstRepository.list(user.id, 1, 101).size == 80)
        assertTrue(firstRepository.unreadCount(user.id) == 80)
    }
}
