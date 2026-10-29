package streamix.auth

import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileSessionRepositorySharedLockTest {
    @Test
    fun concurrentRepositoryInstancesDoNotLoseSessions() {
        val root = Files.createTempDirectory("shinigami-session-shared-lock")
        val file = root.resolve("sessions.json")
        val repositories = listOf(
            FileSessionRepository(file),
            FileSessionRepository(root.resolve(".").resolve("sessions.json"))
        )
        val executor = Executors.newFixedThreadPool(8)
        try {
            val futures = (0 until 40).map { index ->
                executor.submit<AuthSession> {
                    repositories[index % repositories.size].create("user-$index", 3600)
                }
            }
            val created = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(40, created.map { it.token }.toSet().size)
            created.forEach { session ->
                assertNotNull(repositories[0].find(session.token))
            }
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
        }
    }


    @Test
    fun creatingSessionDiscardsRecordsWithMalformedExpiryInsteadOfFailing() {
        val root = Files.createTempDirectory("shinigami-session-malformed-expiry")
        val file = root.resolve("sessions.json")
        Files.writeString(
            file,
            """[
                {"token":"bad-expiry","userId":"broken-user","createdAt":"2026-10-14T00:00:00Z","expiresAt":"not-an-instant"},
                {"token":"active-token","userId":"active-user","createdAt":"2026-10-14T00:00:00Z","expiresAt":"2099-01-01T00:00:00Z"}
            ]""".trimIndent()
        )
        val repository = FileSessionRepository(file)

        val created = repository.create("new-user", 3600)

        val persisted = Files.readString(file)
        assertFalse(persisted.contains("bad-expiry"))
        assertTrue(persisted.contains("active-token"))
        assertTrue(persisted.contains(created.token))
        assertNull(repository.find("bad-expiry"))
    }

    @Test
    fun findReturnsNullForMatchingSessionWithMalformedExpiry() {
        val root = Files.createTempDirectory("shinigami-session-malformed-expiry-lookup")
        val file = root.resolve("sessions.json")
        Files.writeString(
            file,
            """[
                {"token":"bad-expiry","userId":"broken-user","createdAt":"2026-10-14T00:00:00Z","expiresAt":"not-an-instant"}
            ]""".trimIndent()
        )
        val repository = FileSessionRepository(file)

        assertNull(repository.find("bad-expiry"))
    }


    @Test
    fun findReturnsNullForMatchingSessionWithMalformedTimestamp() {
        val root = Files.createTempDirectory("shinigami-session-malformed-timestamp")
        val file = root.resolve("sessions.json")
        Files.writeString(
            file,
            """[
                {"token":"bad-created-at","userId":"broken-user","createdAt":"not-an-instant","expiresAt":"2099-01-01T00:00:00Z"}
            ]""".trimIndent()
        )
        val repository = FileSessionRepository(file)

        assertNull(repository.find("bad-created-at"))
    }


    @Test
    fun creatingSessionPrunesExpiredRecordsAndPreservesActiveSessions() {
        val root = Files.createTempDirectory("shinigami-session-expiry-cleanup")
        val file = root.resolve("sessions.json")
        Files.writeString(
            file,
            """[
                {"token":"expired-token","userId":"expired-user","createdAt":"2020-01-01T00:00:00Z","expiresAt":"2020-01-02T00:00:00Z"},
                {"token":"active-token","userId":"active-user","createdAt":"2026-10-14T00:00:00Z","expiresAt":"2099-01-01T00:00:00Z"}
            ]""".trimIndent()
        )
        val repository = FileSessionRepository(file)

        val created = repository.create("new-user", 3600)

        val persisted = Files.readString(file)
        assertFalse(persisted.contains("expired-token"))
        assertEquals(true, persisted.contains("active-token"))
        assertEquals(true, persisted.contains(created.token))
        assertNull(repository.find("expired-token"))
        assertNotNull(repository.find("active-token"))
        assertNotNull(repository.find(created.token))
    }
}
