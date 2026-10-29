package streamix.social

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import streamix.auth.FileUserRepository

class FileSocialRepositoryNotificationFailureRollbackTest {
    @Test
    fun activityReplyIsRolledBackWhenNotificationPersistenceFails() {
        val root = Files.createTempDirectory("social-reply-notification-rollback")
        val users = FileUserRepository(root.resolve("users.json"))
        val owner = users.create("rollback-activity-owner", "test", "rollback-activity-owner-subject")
        val replier = users.create("rollback-activity-replier", "test", "rollback-activity-replier-subject")
        val socialRoot = root.resolve("social")
        val repository = FileSocialRepository(socialRoot, users)
        val activity = repository.createActivity(owner.id, "STATUS", "hello", null, null)
        Files.createDirectory(socialRoot.resolve("notifications.json"))

        assertFails {
            repository.createReply(activity.id, replier.id, "reply that cannot notify")
        }

        assertEquals(0, repository.replies(activity.id, owner.id, 1, 20).size)
        assertEquals(0, repository.activity(activity.id, owner.id)?.replyCount)
    }

    @Test
    fun commentReplyIsRolledBackWhenNotificationPersistenceFails() {
        val root = Files.createTempDirectory("social-comment-notification-rollback")
        val users = FileUserRepository(root.resolve("users.json"))
        val owner = users.create("rollback-comment-owner", "test", "rollback-comment-owner-subject")
        val replier = users.create("rollback-comment-replier", "test", "rollback-comment-replier-subject")
        val socialRoot = root.resolve("social")
        val repository = FileSocialRepository(socialRoot, users)
        val parent = repository.createComment(21L, owner.id, "parent comment", null)
        Files.createDirectory(socialRoot.resolve("notifications.json"))

        assertFails {
            repository.createComment(21L, replier.id, "reply that cannot notify", parent.id)
        }

        assertEquals(0, repository.commentReplies(parent.id, owner.id, 1, 20).size)
        assertEquals(0, repository.comments(21L, owner.id, parent.id, 1, 20).size)
    }
}
