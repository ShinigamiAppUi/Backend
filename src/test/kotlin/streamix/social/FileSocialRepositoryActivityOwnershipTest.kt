package streamix.social

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import streamix.auth.FileUserRepository

class FileSocialRepositoryActivityOwnershipTest {
    @Test
    fun onlyActivityAuthorCanDeleteActivity() {
        val root = Files.createTempDirectory("social-activity-ownership")
        val users = FileUserRepository(root.resolve("users.json"))
        val author = users.create("activity-author", "test", "activity-author-subject")
        val other = users.create("activity-other", "test", "activity-other-subject")
        val repository = FileSocialRepository(root.resolve("social"), users)
        val activity = repository.createActivity(author.id, "STATUS", "owned by author", null, null)

        repository.createReply(activity.id, other.id, "reply on activity")
        assertFalse(repository.deleteActivity(activity.id, other.id))
        assertTrue(repository.activity(activity.id, other.id) != null)

        assertTrue(repository.replies(activity.id, other.id, page = 1, perPage = 20).isNotEmpty())
        assertTrue(repository.deleteActivity(activity.id, author.id))
        assertNull(repository.activity(activity.id, author.id))
        assertTrue(repository.replies(activity.id, other.id, page = 1, perPage = 20).isEmpty())
        assertFalse(repository.deleteActivity("missing-activity", author.id))
    }
}
