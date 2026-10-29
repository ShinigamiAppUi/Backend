package streamix.social

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import streamix.auth.FileUserRepository

class FileSocialRepositoryActivityReplyInvariantTest {
    @Test
    fun creatingReplyStillReturnsNewReplyAfterFirstHundredAndOne() {
        val root = Files.createTempDirectory("shinigami-social-activity-replies")
        val users = FileUserRepository(root.resolve("users.json"))
        val user = users.create("reply-author", "test", "reply-author-subject")
        val repository = FileSocialRepository(root.resolve("social"), users)
        val activity = repository.createActivity(user.id, "STATUS", "hello", null, null)

        repeat(101) { index ->
            repository.createReply(activity.id, user.id, "reply-$index")
        }

        val created = repository.createReply(activity.id, user.id, "reply-after-page-boundary")

        assertFailsWith<IllegalArgumentException> { repository.createReply(activity.id, user.id, "   ") }
        assertFailsWith<IllegalArgumentException> { repository.createReply(activity.id, user.id, "x".repeat(5001)) }
        assertEquals(102, repository.activity(activity.id, user.id)?.replyCount)
        assertEquals("reply-after-page-boundary", created.text)
        assertEquals(activity.id, created.activityId)
        assertEquals(user.id, created.author.id)
    }
}
