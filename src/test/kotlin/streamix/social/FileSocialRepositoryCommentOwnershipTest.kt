package streamix.social

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import streamix.auth.FileUserRepository

class FileSocialRepositoryCommentOwnershipTest {
    @Test
    fun onlyCommentAuthorCanEditOrDeleteComment() {
        val root = Files.createTempDirectory("social-comment-ownership")
        val users = FileUserRepository(root.resolve("users.json"))
        val author = users.create("comment-author", "test", "comment-author-subject")
        val other = users.create("comment-other", "test", "comment-other-subject")
        val repository = FileSocialRepository(root.resolve("social"), users)
        val comment = repository.createComment(42L, author.id, "original content", null)

        assertNull(repository.editComment(comment.id, other.id, "unauthorized edit"))
        assertFalse(repository.deleteComment(comment.id, other.id))
        assertEquals("original content", repository.findComment(comment.id, other.id)?.content)

        val edited = repository.editComment(comment.id, author.id, "updated content")
        assertEquals("updated content", edited?.content)
        assertFailsWith<IllegalArgumentException> {
            repository.editComment(comment.id, author.id, "x".repeat(5001))
        }
        assertEquals("updated content", repository.findComment(comment.id, author.id)?.content)
        assertTrue(repository.deleteComment(comment.id, author.id))
        assertFalse(repository.deleteComment(comment.id, author.id))
        assertNull(repository.findComment(comment.id, author.id))
        assertNull(repository.editComment(comment.id, author.id, "edit after deletion"))
    }
}
