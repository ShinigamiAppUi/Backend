package streamix.social

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import streamix.auth.FileUserRepository

class FileSocialRepositoryDuplicateIdTest {
    @Test
    fun commentMutationsOnlyChangeTheFirstMatchingActiveRecord() {
        val root = Files.createTempDirectory("social-duplicate-comment-id")
        val users = FileUserRepository(root.resolve("users.json"))
        val owner = users.create("duplicate-comment-owner", "test", "duplicate-comment-owner-subject")
        val other = users.create("duplicate-comment-other", "test", "duplicate-comment-other-subject")
        val socialRoot = root.resolve("social")
        Files.createDirectories(socialRoot)
        val duplicateId = "duplicate-comment-id"
        val records = listOf(
            mapOf("id" to duplicateId, "mediaId" to 42, "parentCommentId" to null, "authorId" to owner.id, "content" to "first", "tag" to null, "upvotesBy" to emptyList<String>(), "downvotesBy" to emptyList<String>(), "deleted" to false, "createdAt" to "2026-01-01T00:00:00Z", "updatedAt" to null),
            mapOf("id" to duplicateId, "mediaId" to 42, "parentCommentId" to null, "authorId" to other.id, "content" to "second", "tag" to null, "upvotesBy" to emptyList<String>(), "downvotesBy" to emptyList<String>(), "deleted" to false, "createdAt" to "2026-01-02T00:00:00Z", "updatedAt" to null)
        )
        val file = socialRoot.resolve("comments.json")
        Files.writeString(file, Gson().toJson(records))
        val repository = FileSocialRepository(socialRoot, users)

        assertEquals("edited first", repository.editComment(duplicateId, owner.id, "edited first")?.content)
        assertTrue(repository.voteComment(duplicateId, owner.id, 1) != null)
        val storedType = object : TypeToken<List<Map<String, Any?>>>() {}.type
        val stored: List<Map<String, Any?>> = Gson().fromJson(Files.readString(file), storedType)
        assertEquals("edited first", stored[0]["content"])
        assertEquals(listOf(owner.id), stored[0]["upvotesBy"])
        assertEquals("second", stored[1]["content"])
        assertTrue((stored[1]["upvotesBy"] as List<*>).isEmpty())

        assertTrue(repository.deleteComment(duplicateId, owner.id))
        val afterDelete: List<Map<String, Any?>> = Gson().fromJson(Files.readString(file), storedType)
        assertEquals(true, afterDelete[0]["deleted"])
        assertEquals(false, afterDelete[1]["deleted"])
        assertFalse(repository.deleteComment(duplicateId, owner.id))
    }

    @Test
    fun activityMutationsAndDeletionOnlyAffectTheFirstMatchingRecord() {
        val root = Files.createTempDirectory("social-duplicate-activity-id")
        val users = FileUserRepository(root.resolve("users.json"))
        val owner = users.create("duplicate-activity-owner", "test", "duplicate-activity-owner-subject")
        val other = users.create("duplicate-activity-other", "test", "duplicate-activity-other-subject")
        val socialRoot = root.resolve("social")
        Files.createDirectories(socialRoot)
        val duplicateId = "duplicate-activity-id"
        val records = listOf(
            mapOf("id" to duplicateId, "authorId" to owner.id, "type" to "STATUS", "text" to "first", "mediaId" to null, "mediaTitle" to null, "createdAt" to "2026-01-01T00:00:00Z", "likedBy" to emptyList<String>(), "subscribedBy" to emptyList<String>()),
            mapOf("id" to duplicateId, "authorId" to other.id, "type" to "STATUS", "text" to "second", "mediaId" to null, "mediaTitle" to null, "createdAt" to "2026-01-02T00:00:00Z", "likedBy" to emptyList<String>(), "subscribedBy" to emptyList<String>())
        )
        val file = socialRoot.resolve("activities.json")
        Files.writeString(file, Gson().toJson(records))
        val repository = FileSocialRepository(socialRoot, users)

        assertTrue(repository.toggleActivityLike(duplicateId, owner.id) != null)
        val storedType = object : TypeToken<List<Map<String, Any?>>>() {}.type
        val stored: List<Map<String, Any?>> = Gson().fromJson(Files.readString(file), storedType)
        assertEquals(listOf(owner.id), stored[0]["likedBy"])
        assertTrue((stored[1]["likedBy"] as List<*>).isEmpty())

        assertTrue(repository.deleteActivity(duplicateId, owner.id))
        val afterDelete: List<Map<String, Any?>> = Gson().fromJson(Files.readString(file), storedType)
        assertEquals(1, afterDelete.size)
        assertEquals(other.id, afterDelete.single()["authorId"])
    }

    @Test
    fun commentReplyCountIsScopedToTheSameAnime() {
        val root = Files.createTempDirectory("social-comment-replies-media-scope")
        val users = FileUserRepository(root.resolve("users.json"))
        val owner = users.create("comment-reply-scope-owner", "test", "comment-reply-scope-owner-subject")
        val socialRoot = root.resolve("social")
        Files.createDirectories(socialRoot)
        val parentId = "shared-parent-comment-id"
        val records = listOf(
            mapOf("id" to parentId, "mediaId" to 42, "parentCommentId" to null, "authorId" to owner.id, "content" to "parent", "tag" to null, "upvotesBy" to emptyList<String>(), "downvotesBy" to emptyList<String>(), "deleted" to false, "createdAt" to "2026-01-01T00:00:00Z", "updatedAt" to null),
            mapOf("id" to "reply-same-anime", "mediaId" to 42, "parentCommentId" to parentId, "authorId" to owner.id, "content" to "valid reply", "tag" to null, "upvotesBy" to emptyList<String>(), "downvotesBy" to emptyList<String>(), "deleted" to false, "createdAt" to "2026-01-02T00:00:00Z", "updatedAt" to null),
            mapOf("id" to "reply-other-anime", "mediaId" to 99, "parentCommentId" to parentId, "authorId" to owner.id, "content" to "unrelated reply", "tag" to null, "upvotesBy" to emptyList<String>(), "downvotesBy" to emptyList<String>(), "deleted" to false, "createdAt" to "2026-01-03T00:00:00Z", "updatedAt" to null)
        )
        Files.writeString(socialRoot.resolve("comments.json"), Gson().toJson(records))
        val repository = FileSocialRepository(socialRoot, users)

        assertEquals(1, repository.findComment(parentId, owner.id)?.replyCount)
        assertEquals(1, repository.commentReplies(parentId, owner.id, 1, 20).size)
    }
}
