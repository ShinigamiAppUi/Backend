package streamix.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import java.nio.file.Files

class AuthServiceTest {
    @Test
    fun verifiedIdentityCreatesStableUserAndRotatingSessions() {
        val root = Files.createTempDirectory("shinigami-auth-test")
        val users = FileUserRepository(root.resolve("users.json"))
        val sessions = FileSessionRepository(root.resolve("sessions.json"))
        val auth = AuthService(users, sessions, sessionTtlSeconds = 3600)

        val verifier = ExternalIdentityVerifier { provider, credential ->
            if (provider == "google" && credential == "valid") {
                ExternalIdentity(
                    provider = "google",
                    subject = "google-subject-1",
                    email = "user@example.com",
                    displayName = "Test User"
                )
            } else {
                null
            }
        }

        val first = auth.signIn("google", "valid", verifier)
        val second = auth.signIn("google", "valid", verifier)

        assertEquals(first.user.id, second.user.id)
        assertNotNull(first.token)
        assertNotNull(second.token)
        assertEquals(first.user.id, auth.currentUser(first.token!!)?.id)
        assertEquals(first.user.id, auth.currentUser(second.token!!)?.id)
    }

    @Test
    fun concurrentFirstSignInsForSameIdentityReuseCreatedUser() {
        val root = Files.createTempDirectory("shinigami-auth-race-test")
        val delegate = FileUserRepository(root.resolve("users.json"))
        val sessions = FileSessionRepository(root.resolve("sessions.json"))
        val lookupCount = java.util.concurrent.atomic.AtomicInteger()
        val bothLookedUp = java.util.concurrent.CountDownLatch(2)
        val users = object : UserRepository by delegate {
            override fun findByExternalIdentity(provider: String, subject: String): streamix.api.ShinigamiUser? {
                val result = delegate.findByExternalIdentity(provider, subject)
                val count = lookupCount.incrementAndGet()
                if (count <= 2) {
                    bothLookedUp.countDown()
                    check(bothLookedUp.await(5, java.util.concurrent.TimeUnit.SECONDS))
                }
                return result
            }
        }
        val auth = AuthService(users, sessions, sessionTtlSeconds = 3600)
        val identity = ExternalIdentity(
            provider = "google",
            subject = "concurrent-subject-123",
            displayName = "Concurrent User"
        )
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<streamix.api.SessionResponse> { auth.signIn(identity) }
            val second = executor.submit<streamix.api.SessionResponse> { auth.signIn(identity) }
            val firstSession = first.get(10, java.util.concurrent.TimeUnit.SECONDS)
            val secondSession = second.get(10, java.util.concurrent.TimeUnit.SECONDS)

            assertEquals(firstSession.user.id, secondSession.user.id)
            assertNotNull(firstSession.token)
            assertNotNull(secondSession.token)
            assertEquals(1L, delegate.count())
            assertEquals(firstSession.user.id, auth.currentUser(firstSession.token!!)?.id)
            assertEquals(secondSession.user.id, auth.currentUser(secondSession.token!!)?.id)
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    @Test
    fun logoutInvalidatesSession() {
        val root = Files.createTempDirectory("shinigami-auth-test")
        val users = FileUserRepository(root.resolve("users.json"))
        val sessions = FileSessionRepository(root.resolve("sessions.json"))
        val auth = AuthService(users, sessions, sessionTtlSeconds = 3600)

        val session = auth.signIn(
            ExternalIdentity(
                provider = "google",
                subject = "subject-2",
                displayName = "Another User"
            )
        )

        val token = session.token!!
        assertNotNull(auth.currentUser(token))
        auth.logout(token)
        assertNull(auth.currentUser(token))
    }
}
