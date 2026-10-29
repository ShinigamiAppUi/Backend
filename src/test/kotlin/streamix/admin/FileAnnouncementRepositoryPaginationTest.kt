package streamix.admin

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import java.util.concurrent.Executors
import streamix.api.Announcement

class FileAnnouncementRepositoryPaginationTest {
    @Test
    fun hugePageNumberReturnsEmptyListInsteadOfOverflowingOffset() {
        val root = Files.createTempDirectory("shinigami-announcement-pagination")
        val announcements = FileAnnouncementRepository(root)

        assertTrue(announcements.list(Int.MAX_VALUE, 100).isEmpty())
    }
    @Test
    fun concurrentCreatesAcrossRepositoryInstancesPreserveEveryAnnouncement() {
        val root = Files.createTempDirectory("shinigami-announcement-concurrency")
        val repositories = listOf(FileAnnouncementRepository(root), FileAnnouncementRepository(root))
        val executor = Executors.newFixedThreadPool(8)
        try {
            val tasks = (1..80).map { index ->
                java.util.concurrent.Callable {
                    repositories[index % repositories.size].create(
                        Announcement(
                            id = "announcement-$index",
                            title = "Title $index",
                            body = "Body $index",
                            createdBy = "admin",
                            createdAt = "2026-01-01T00:00:00Z"
                        )
                    )
                }
            }
            executor.invokeAll(tasks).forEach { it.get() }
            val persisted = repositories.first().list(1, 100)
            assertEquals(80, persisted.size)
            assertEquals((1..80).map { "announcement-$it" }.toSet(), persisted.map { it.id }.toSet())
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
            root.toFile().deleteRecursively()
        }
    }
}
