package streamix.social

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import streamix.auth.FileUserRepository

class FileSocialRepositoryNotificationProducerTest {
    @Test
    fun activityReplyNotifiesActivityOwnerButNotOnSelfReply() {
        val root = Files.createTempDirectory("social-activity-reply-notification")
        val users = FileUserRepository(root.resolve("users.json"))
        val owner = users.create("activity-notify-owner", "test", "activity-notify-owner-subject")
        val replier = users.create("activity-notify-replier", "test", "activity-notify-replier-subject")
        val repository = FileSocialRepository(root.resolve("social"), users)
        val activity = repository.createActivity(owner.id, "STATUS", "hello", null, null)

        repository.createReply(activity.id, replier.id, "reply")

        val notifications = repository.notifications(owner.id, 1, 20)
        assertEquals(1, notifications.size)
        assertEquals("ACTIVITY_REPLY", notifications.single().type)
        assertEquals(activity.id, notifications.single().activityId)
        assertEquals(replier.id, notifications.single().actor?.id)
        assertFalse(notifications.single().read)
        assertEquals(0, repository.notifications(replier.id, 1, 20).size)

        repository.createReply(activity.id, owner.id, "self reply")
        assertEquals(1, repository.notifications(owner.id, 1, 20).size)
    }

    @Test
    fun nestedCommentReplyNotifiesParentAuthorButNotOnSelfReply() {
        val root = Files.createTempDirectory("social-comment-reply-notification")
        val users = FileUserRepository(root.resolve("users.json"))
        val parentAuthor = users.create("comment-notify-parent", "test", "comment-notify-parent-subject")
        val replier = users.create("comment-notify-replier", "test", "comment-notify-replier-subject")
        val repository = FileSocialRepository(root.resolve("social"), users)
        val parent = repository.createComment(21L, parentAuthor.id, "parent comment", null)

        val reply = repository.createComment(21L, replier.id, "nested reply", parent.id)

        val notifications = repository.notifications(parentAuthor.id, 1, 20)
        assertEquals(1, notifications.size)
        assertEquals("COMMENT_REPLY", notifications.single().type)
        assertEquals(reply.id, notifications.single().commentId)
        assertEquals(21L, notifications.single().mediaId)
        assertEquals(replier.id, notifications.single().actor?.id)
        assertFalse(notifications.single().read)
        assertEquals(0, repository.notifications(replier.id, 1, 20).size)

        repository.createComment(21L, parentAuthor.id, "self reply", parent.id)
        assertEquals(1, repository.notifications(parentAuthor.id, 1, 20).size)
    }

    @Test
    fun topLevelAnimeCommentDoesNotInventNotificationRecipient() {
        val root = Files.createTempDirectory("social-top-level-comment-notification")
        val users = FileUserRepository(root.resolve("users.json"))
        val author = users.create("top-comment-author", "test", "top-comment-author-subject")
        val repository = FileSocialRepository(root.resolve("social"), users)

        val comment = repository.createComment(21L, author.id, "top-level comment", null)

        assertNotNull(repository.findComment(comment.id, author.id))
        assertTrue(repository.notifications(author.id, 1, 20).isEmpty())
    }
}
