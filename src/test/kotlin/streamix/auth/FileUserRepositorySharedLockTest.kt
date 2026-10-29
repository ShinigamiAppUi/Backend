package streamix.auth

import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileUserRepositorySharedLockTest {
    @Test
    fun concurrentRepositoryInstancesDoNotLoseUsers() {
        val root = Files.createTempDirectory("shinigami-user-shared-lock")
        val file = root.resolve("users.json")
        val repositories = listOf(
            FileUserRepository(file),
            FileUserRepository(root.resolve(".").resolve("users.json"))
        )
        val executor = Executors.newFixedThreadPool(8)
        try {
            val futures = (0 until 40).map { index ->
                executor.submit<streamix.api.ShinigamiUser> {
                    repositories[index % repositories.size].create(
                        username = "user-$index",
                        externalProvider = "test-provider",
                        externalSubject = "subject-$index"
                    )
                }
            }
            val created = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(40, created.map { it.id }.toSet().size)
            assertEquals(40L, repositories[0].count())
            created.forEach { user ->
                assertNotNull(repositories[1].findByExternalIdentity("test-provider", "subject-" + user.username.removePrefix("user-")))
            }
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    @Test
    fun legacyUserRecordsWithoutRelationshipSetsRemainReadableAndMutable() {
        val root = Files.createTempDirectory("shinigami-user-legacy-relationships")
        val file = root.resolve("users.json")
        Files.writeString(
            file,
            """[
                {"id":"user-1","username":"one","isFollowing":false,"isFollower":false,"isBlocked":false,"isAdmin":false,"isModerator":false,"externalProvider":"test","externalSubject":"one"},
                {"id":"user-2","username":"two","isFollowing":false,"isFollower":false,"isBlocked":false,"isAdmin":false,"isModerator":false,"externalProvider":"test","externalSubject":"two","followingUserIds":null,"blockedUserIds":null}
            ]""".trimIndent()
        )
        val repository = FileUserRepository(file)

        val initial = repository.relationship("user-1", "user-2")
        assertFalse(initial.following)
        assertFalse(initial.follower)
        assertFalse(initial.blocked)

        repository.setRelationship("user-1", "user-2", following = true, blocked = true)
        val updated = repository.relationship("user-1", "user-2")
        assertTrue(updated.following)
        assertTrue(updated.blocked)
        assertFalse(updated.follower)
    }


    @Test
    fun usernamesAreTrimmedAndUniquenessUsesNormalizedValues() {
        val root = Files.createTempDirectory("shinigami-user-normalized-username")
        val repository = FileUserRepository(root.resolve("users.json"))
        val first = repository.create(
            username = "  spaced-user  ",
            externalProvider = "test-provider",
            externalSubject = "normalized-subject"
        )
        assertEquals("spaced-user", first.username)
        assertEquals(first.id, repository.findByUsername("spaced-user")?.id)

        assertFailsWith<IllegalArgumentException> {
            repository.create(
                username = " SPACED-USER ",
                externalProvider = "test-provider",
                externalSubject = "duplicate-normalized-subject"
            )
        }

        val second = repository.create(
            username = "second-user",
            externalProvider = "test-provider",
            externalSubject = "second-subject"
        )
        val renamed = repository.update(second.copy(username = "  renamed-user  "))
        assertEquals("renamed-user", renamed.username)
        assertEquals(second.id, repository.findByUsername("renamed-user")?.id)

        assertFailsWith<IllegalArgumentException> {
            repository.update(renamed.copy(username = " SPACED-USER "))
        }
        assertEquals("renamed-user", repository.findById(second.id)?.username)
        assertEquals(2L, repository.count())
    }

    @Test
    fun blankUsernamesAreRejectedWithoutMutatingPersistedUsers() {
        val root = Files.createTempDirectory("shinigami-user-blank-username")
        val repository = FileUserRepository(root.resolve("users.json"))
        val user = repository.create(
            username = "valid-user",
            externalProvider = "test-provider",
            externalSubject = "valid-subject"
        )

        assertFailsWith<IllegalArgumentException> {
            repository.create(
                username = "   ",
                externalProvider = "test-provider",
                externalSubject = "blank-subject"
            )
        }
        assertFailsWith<IllegalArgumentException> {
            repository.update(user.copy(username = "  "))
        }

        assertEquals(1L, repository.count())
        assertEquals("valid-user", repository.findById(user.id)?.username)
        assertEquals(null, repository.findByExternalIdentity("test-provider", "blank-subject"))
    }

    @Test
    fun setRelationshipReturnsFlagsForTheMutatedTarget() {
        val root = Files.createTempDirectory("shinigami-user-relationship-response")
        val repository = FileUserRepository(root.resolve("users.json"))
        val actor = repository.create("actor", "test-provider", "actor-subject")
        val target = repository.create("target", "test-provider", "target-subject")

        val targetFollowsActor = repository.setRelationship(target.id, actor.id, following = true)
        assertTrue(targetFollowsActor.isFollowing)
        assertFalse(targetFollowsActor.isFollower)
        assertFalse(targetFollowsActor.isBlocked)

        val followed = repository.setRelationship(actor.id, target.id, following = true)
        assertTrue(followed.isFollowing)
        assertTrue(followed.isFollower)
        assertFalse(followed.isBlocked)

        val blocked = repository.setRelationship(actor.id, target.id, blocked = true)
        assertTrue(blocked.isFollowing)
        assertTrue(blocked.isFollower)
        assertTrue(blocked.isBlocked)

        val unfollowed = repository.setRelationship(actor.id, target.id, following = false)
        assertFalse(unfollowed.isFollowing)
        assertTrue(unfollowed.isFollower)
        assertTrue(unfollowed.isBlocked)
    }

}
