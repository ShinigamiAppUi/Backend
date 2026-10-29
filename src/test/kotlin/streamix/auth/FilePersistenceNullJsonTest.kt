package streamix.auth

import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import streamix.notification.FileNotificationRepository

class FilePersistenceNullJsonTest {
    @Test
    fun userRepositoryDoesNotOverwriteJsonNullDuringMutation() {
        val root = Files.createTempDirectory("shinigami-user-null-json")
        val file = root.resolve("users.json")
        val repository = FileUserRepository(file)
        repository.create("existing", "test", "existing-subject")
        Files.writeString(file, "null")

        assertFailsWith<IOException> {
            repository.create("another", "test", "another-subject")
        }
        assertEquals("null", Files.readString(file))
    }

    @Test
    fun sessionRepositoryDoesNotOverwriteJsonNullDuringMutation() {
        val root = Files.createTempDirectory("shinigami-session-null-json")
        val file = root.resolve("sessions.json")
        Files.writeString(file, "null")
        val repository = FileSessionRepository(file)

        assertFailsWith<IOException> {
            repository.create("user-1", ttlSeconds = 3600)
        }
        assertEquals("null", Files.readString(file))
    }

    @Test
    fun notificationRepositoryDoesNotOverwriteJsonNullDuringMutation() {
        val root = Files.createTempDirectory("shinigami-notification-null-json")
        val users = FileUserRepository(root.resolve("users.json"))
        val user = users.create("reader", "test", "reader-subject")
        val notificationRoot = root.resolve("notifications")
        Files.createDirectories(notificationRoot)
        val file = notificationRoot.resolve("notifications.json")
        Files.writeString(file, "null")
        val repository = FileNotificationRepository(notificationRoot, users)

        assertFailsWith<IOException> {
            repository.create(user.id, "MESSAGE", "Test", "Must not erase persisted data")
        }
        assertEquals("null", Files.readString(file))
    }
}
