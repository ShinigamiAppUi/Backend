package streamix.social

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import streamix.auth.FileUserRepository

class FileSocialRepositoryActivityInvariantTest {
    @Test
    fun createActivityEnforcesRouteInputConstraintsBeforePersisting() {
        val root = Files.createTempDirectory("social-activity-invariants")
        val users = FileUserRepository(root.resolve("users.json"))
        val author = users.create("activity-invariant-author", "test", "activity-invariant-subject")
        val repository = FileSocialRepository(root.resolve("social"), users)

        assertFailsWith<IllegalArgumentException> {
            repository.createActivity(author.id, "   ", "valid", null, null)
        }
        assertFailsWith<IllegalArgumentException> {
            repository.createActivity(author.id, "x".repeat(41), "valid", null, null)
        }
        assertFailsWith<IllegalArgumentException> {
            repository.createActivity(author.id, "STATUS", "x".repeat(5001), null, null)
        }
        assertFailsWith<IllegalArgumentException> {
            repository.createActivity(author.id, "STATUS", "valid", 0L, null)
        }

        val created = repository.createActivity(author.id, " STATUS ", "  normalized text  ", 42L, "  Anime title  ")

        assertEquals("STATUS", created.type)
        assertEquals("normalized text", created.text)
        assertEquals(42L, created.mediaId)
        assertEquals("Anime title", created.mediaTitle)
        assertEquals(1, repository.activities(author.id, 1, 20).size)
    }
}
