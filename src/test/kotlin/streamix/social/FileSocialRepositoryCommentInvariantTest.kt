package streamix.social

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith
import streamix.auth.FileUserRepository

class FileSocialRepositoryCommentInvariantTest {
    @Test
    fun rejectsParentCommentFromDifferentAnimeAtRepositoryBoundary() {
        val root = Files.createTempDirectory("shinigami-social-comment-parent")
        val users = FileUserRepository(root.resolve("users.json"))
        val user = users.create("commenter", "test", "commenter-subject")
        val repository = FileSocialRepository(root.resolve("social"), users)
        val parent = repository.createComment(21L, user.id, "Parent", null)

        assertFailsWith<IllegalArgumentException> {
            repository.createComment(22L, user.id, "Child", parent.id)
        }
    }

    @Test
    fun rejectsMissingParentCommentAtRepositoryBoundary() {
        val root = Files.createTempDirectory("shinigami-social-comment-missing-parent")
        val users = FileUserRepository(root.resolve("users.json"))
        val user = users.create("commenter", "test", "commenter-subject")
        val repository = FileSocialRepository(root.resolve("social"), users)

        assertFailsWith<IllegalArgumentException> {
            repository.createComment(21L, user.id, "Child", "missing-parent")
        }
    }
}
