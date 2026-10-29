package streamix.social

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import streamix.auth.FileUserRepository

class FileSocialRepositoryConcurrencyTest {
    @Test
    fun activitiesAreOrderedByInstantRatherThanTimestampText() {
        val root = createTempDirectory("social-timestamp-order")
        val users = FileUserRepository(root.resolve("users.json"))
        val user = users.create("timestamp-reader", "test", "timestamp-reader-subject")
        val socialRoot = root.resolve("social")
        Files.createDirectories(socialRoot)
        Files.writeString(
            socialRoot.resolve("activities.json"),
            """[
              {"id":"whole-second","authorId":"${user.id}","type":"STATUS","text":"Older","mediaId":null,"mediaTitle":null,"createdAt":"2026-10-15T12:00:00Z","likedBy":[],"subscribedBy":[]},
              {"id":"fractional-second","authorId":"${user.id}","type":"STATUS","text":"Newer","mediaId":null,"mediaTitle":null,"createdAt":"2026-10-15T12:00:00.100Z","likedBy":[],"subscribedBy":[]}
            ]"""
        )

        val result = FileSocialRepository(socialRoot, users).activities(user.id, page = 1, perPage = 10)

        assertEquals(listOf("fractional-second", "whole-second"), result.map { it.id })
        root.toFile().deleteRecursively()
    }

    @Test
    fun duplicateCommentIdsOnlyMutateTheFirstPersistedRecord() {
        val root = createTempDirectory("social-duplicate-comment-ids")
        val users = FileUserRepository(root.resolve("users.json"))
        val actor = users.create("duplicate-editor", "test", "duplicate-editor-subject")
        val other = users.create("duplicate-owner", "test", "duplicate-owner-subject")
        val socialRoot = root.resolve("social")
        Files.createDirectories(socialRoot)
        Files.writeString(
            socialRoot.resolve("comments.json"),
            """[
              {"id":"duplicate-id","mediaId":42,"parentCommentId":null,"authorId":"${actor.id}","content":"Original first","tag":null,"createdAt":"2026-10-15T12:00:00Z","updatedAt":null,"deleted":false,"upvotesBy":[],"downvotesBy":[]},
              {"id":"duplicate-id","mediaId":42,"parentCommentId":null,"authorId":"${other.id}","content":"Preserve second","tag":null,"createdAt":"2026-10-15T12:00:01Z","updatedAt":null,"deleted":false,"upvotesBy":["existing-voter"],"downvotesBy":[]}
            ]""".trimIndent()
        )
        val repository = FileSocialRepository(socialRoot, users)

        repository.editComment("duplicate-id", actor.id, "Edited first")
        repository.voteComment("duplicate-id", actor.id, 1)

        val persisted = repository.comments(42L, actor.id, null, page = 1, perPage = 10)
        assertEquals(2, persisted.size)
        assertEquals("Edited first", persisted[0].content)
        assertEquals(1, persisted[0].userVote)
        assertEquals("Preserve second", persisted[1].content)
        assertEquals(null, persisted[1].userVote)
        assertEquals(1, persisted[1].upvotes)
        root.toFile().deleteRecursively()
    }

    @Test
    fun concurrentActivityCreatesAcrossRepositoryInstancesDoNotLoseRecords() {
        val root = createTempDirectory("social-concurrent-repositories")
        val users = FileUserRepository(root.resolve("users.json"))
        val user = users.create("social-writer", "test", "social-writer-subject")
        val socialRoot = root.resolve("social")
        val repositories = List(2) { FileSocialRepository(socialRoot, users) }
        val workers = 8
        val perWorker = 25
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val done = CountDownLatch(workers)
        val failure = AtomicReference<Throwable?>(null)
        val pool = Executors.newFixedThreadPool(workers)

        repeat(workers) { worker ->
            pool.execute {
                ready.countDown()
                try {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    repeat(perWorker) { index ->
                        repositories[(worker + index) % repositories.size].createActivity(
                            authorId = user.id,
                            type = "STATUS",
                            text = "worker-$worker-item-$index",
                            mediaId = null,
                            mediaTitle = null
                        )
                    }
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                    throw error
                } finally {
                    done.countDown()
                }
            }
        }

        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS), "social workers did not become ready")
            start.countDown()
            assertTrue(done.await(20, TimeUnit.SECONDS), "social writes timed out")
            failure.get()?.let { throw AssertionError("concurrent social activity write failed", it) }
        } finally {
            // Release workers even when readiness or an assertion fails.
            start.countDown()
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }

        val reader = FileSocialRepository(socialRoot, users)
        val persisted = reader.activities(user.id, page = 1, perPage = 100) +
            reader.activities(user.id, page = 2, perPage = 100)
        assertEquals(workers * perWorker, persisted.size)
        assertEquals(workers * perWorker, persisted.map { it.id }.toSet().size)
        root.toFile().deleteRecursively()
    }

    @Test
    fun concurrentCommentCreatesAcrossRepositoryInstancesDoNotLoseRecords() {
        val root = createTempDirectory("social-comment-concurrent-repositories")
        val users = FileUserRepository(root.resolve("users.json"))
        val user = users.create("comment-writer", "test", "comment-writer-subject")
        val socialRoot = root.resolve("social")
        val repositories = List(2) { FileSocialRepository(socialRoot, users) }
        val workers = 8
        val perWorker = 25
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val done = CountDownLatch(workers)
        val failure = AtomicReference<Throwable?>(null)
        val pool = Executors.newFixedThreadPool(workers)

        repeat(workers) { worker ->
            pool.execute {
                ready.countDown()
                try {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    repeat(perWorker) { index ->
                        repositories[(worker + index) % repositories.size].createComment(
                            mediaId = 42L,
                            authorId = user.id,
                            content = "worker-$worker-comment-$index",
                            parentCommentId = null,
                            tag = null
                        )
                    }
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                    throw error
                } finally {
                    done.countDown()
                }
            }
        }

        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS), "social comment workers did not become ready")
            start.countDown()
            assertTrue(done.await(20, TimeUnit.SECONDS), "social comment writes timed out")
            failure.get()?.let { throw AssertionError("concurrent social comment write failed", it) }
        } finally {
            // Release workers even when readiness or an assertion fails.
            start.countDown()
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }

        val reader = FileSocialRepository(socialRoot, users)
        val firstPage = reader.comments(42L, user.id, null, page = 1, perPage = 100)
        val secondPage = reader.comments(42L, user.id, null, page = 2, perPage = 100)
        val persisted = firstPage + secondPage
        assertEquals(workers * perWorker, persisted.size)
        assertEquals(workers * perWorker, persisted.map { it.id }.toSet().size)
        root.toFile().deleteRecursively()
    }

}
