package streamix.catalog

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.io.path.createTempDirectory

class FileCatalogRepositoryConcurrencyTest {
    @Test
    fun concurrentUpsertsAcrossRepositoryInstancesDoNotLoseRecords() {
        val root = createTempDirectory("catalog-concurrent-repositories")
        val file = root.resolve("episodes.json")
        val repositories = List(2) { FileCatalogRepository(file) }
        val workers = 8
        val perWorker = 25
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val done = CountDownLatch(workers)
        val failure = AtomicReference<Throwable?>(null)
        val pool = Executors.newFixedThreadPool(workers)

        try {
            repeat(workers) { worker ->
                pool.execute {
                    ready.countDown()
                    try {
                        assertTrue(start.await(5, TimeUnit.SECONDS))
                        repeat(perWorker) { index ->
                            val id = "worker-$worker-source-$index"
                            repositories[(worker + index) % repositories.size].upsertSource(
                                EpisodeSource(
                                    id = id,
                                    anilistId = 1L,
                                    episodeNumber = worker + 1,
                                    sourceUrl = "https://example.com/$id.m3u8",
                                    type = SourceType.DIRECT
                                )
                            )
                        }
                    } catch (error: Throwable) {
                        failure.compareAndSet(null, error)
                    } finally {
                        done.countDown()
                    }
                }
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS), "catalog workers did not become ready")
            start.countDown()
            assertTrue(done.await(20, TimeUnit.SECONDS), "catalog writes timed out")
            failure.get()?.let { throw AssertionError("catalog concurrency worker failed", it) }
        } finally {
            start.countDown()
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }

        val persisted = FileCatalogRepository(file).listEpisodes(1L).flatMap { it.sources }
        assertEquals(workers * perWorker, persisted.size)
        assertEquals(workers * perWorker, persisted.map { it.id }.toSet().size)
        root.toFile().deleteRecursively()
    }
}
