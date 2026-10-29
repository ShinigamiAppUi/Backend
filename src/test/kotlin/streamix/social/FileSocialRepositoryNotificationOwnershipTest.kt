package streamix.social

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import streamix.auth.FileUserRepository

class FileSocialRepositoryNotificationOwnershipTest {
    @Test
    fun markingNotificationReadOnlyUpdatesTheRequestingUsersRecord() {
        val root = Files.createTempDirectory("social-notification-ownership")
        val users = FileUserRepository(root.resolve("users.json"))
        val owner = users.create("notification-owner", "test", "notification-owner-subject")
        val other = users.create("notification-other", "test", "notification-other-subject")
        val socialRoot = root.resolve("social")
        Files.createDirectories(socialRoot)
        val notificationId = "shared-notification-id"
        val notifications = listOf(
            mapOf(
                "id" to notificationId, "userId" to owner.id, "type" to "TEST",
                "actorId" to null, "activityId" to null, "commentId" to null,
                "mediaId" to null, "message" to "owner", "read" to false,
                "createdAt" to "2026-01-01T00:00:00Z"
            ),
            mapOf(
                "id" to notificationId, "userId" to other.id, "type" to "TEST",
                "actorId" to null, "activityId" to null, "commentId" to null,
                "mediaId" to null, "message" to "other", "read" to false,
                "createdAt" to "2026-01-01T00:00:00Z"
            )
        )
        Files.writeString(socialRoot.resolve("notifications.json"), Gson().toJson(notifications))
        val repository = FileSocialRepository(socialRoot, users)

        assertTrue(repository.markNotificationRead(notificationId, owner.id))

        val storedType = object : TypeToken<List<Map<String, Any>>>() {}.type
        val stored: List<Map<String, Any>> = Gson().fromJson(
            Files.readString(socialRoot.resolve("notifications.json")),
            storedType
        )
        assertEquals(true, stored.single { it["userId"] == owner.id }["read"])
        assertEquals(false, stored.single { it["userId"] == other.id }["read"])
    }
    @Test
    fun duplicateNotificationIdsForSameOwnerOnlyUpdateTheFirstRecord() {
        val root = Files.createTempDirectory("social-duplicate-notification-id")
        val users = FileUserRepository(root.resolve("users.json"))
        val owner = users.create("duplicate-notification-owner", "test", "duplicate-notification-owner-subject")
        val socialRoot = root.resolve("social")
        Files.createDirectories(socialRoot)
        val notificationId = "duplicate-notification-id"
        val notifications = listOf(
            mapOf("id" to notificationId, "userId" to owner.id, "type" to "TEST", "actorId" to null, "activityId" to null, "commentId" to null, "mediaId" to null, "message" to "first", "read" to false, "createdAt" to "2026-01-01T00:00:00Z"),
            mapOf("id" to notificationId, "userId" to owner.id, "type" to "TEST", "actorId" to null, "activityId" to null, "commentId" to null, "mediaId" to null, "message" to "second", "read" to false, "createdAt" to "2026-01-02T00:00:00Z")
        )
        val file = socialRoot.resolve("notifications.json")
        Files.writeString(file, Gson().toJson(notifications))
        val repository = FileSocialRepository(socialRoot, users)

        assertTrue(repository.markNotificationRead(notificationId, owner.id))

        val storedType = object : TypeToken<List<Map<String, Any>>>() {}.type
        val stored: List<Map<String, Any>> = Gson().fromJson(Files.readString(file), storedType)
        assertEquals(true, stored[0]["read"])
        assertEquals(false, stored[1]["read"])
    }

}
