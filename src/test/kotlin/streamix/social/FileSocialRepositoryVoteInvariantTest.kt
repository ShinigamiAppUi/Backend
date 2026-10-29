package streamix.social

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import streamix.auth.FileUserRepository

class FileSocialRepositoryVoteInvariantTest {
    @Test
    fun rejectsUnsupportedVoteWithoutChangingStoredComment() {
        val root = Files.createTempDirectory("shinigami-social-comment-vote")
        val users = FileUserRepository(root.resolve("users.json"))
        val user = users.create("voter", "test", "voter-subject")
        val repository = FileSocialRepository(root.resolve("social"), users)
        val comment = repository.createComment(21L, user.id, "Comment", null)
        val before = Files.readString(root.resolve("social/comments.json"))

        assertFailsWith<IllegalArgumentException> {
            repository.voteComment(comment.id, user.id, 2)
        }

        assertEquals(before, Files.readString(root.resolve("social/comments.json")))
    }
}
