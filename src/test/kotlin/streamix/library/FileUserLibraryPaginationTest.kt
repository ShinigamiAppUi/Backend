package streamix.library

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import java.util.concurrent.Executors

class FileUserLibraryPaginationTest {
    @Test
    fun hugePageNumberReturnsEmptyListInsteadOfOverflowingOffset() {
        val root = Files.createTempDirectory("shinigami-library-pagination")
        val library = FileUserLibraryRepository(root.resolve("library.json"))

        assertTrue(library.list("user-1", Int.MAX_VALUE, 100).isEmpty())
    }

    @Test
    fun fullFinalPageDoesNotClaimThereIsAnotherPage() {
        val root = Files.createTempDirectory("shinigami-library-page-contract")
        val repository = FileUserLibraryRepository(root.resolve("library.json"))
        repository.upsert("user-1", streamix.api.UserAnimeState(mediaId = 1L))

        val page = UserLibraryService(repository).list("user-1", page = 1, perPage = 1)
        assertFalse(page.hasNextPage)
        assertTrue(page.items.size == 1)
    }

    @Test
    fun concurrentUpsertsAcrossRepositoryInstancesPreserveEveryEntry() {
        val root = Files.createTempDirectory("shinigami-library-concurrency")
        val file = root.resolve("library.json")
        val first = FileUserLibraryRepository(file)
        val second = FileUserLibraryRepository(file)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val tasks = (1..80).map { index ->
                java.util.concurrent.Callable {
                    (if (index % 2 == 0) first else second).upsert(
                        "user-1",
                        streamix.api.UserAnimeState(mediaId = index.toLong())
                    )
                }
            }
            executor.invokeAll(tasks).forEach { it.get() }
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
        }
        assertEquals(80, first.list("user-1", 1, 101).size)
    }
}
