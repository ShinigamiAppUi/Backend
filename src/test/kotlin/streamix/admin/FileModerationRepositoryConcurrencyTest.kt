package streamix.admin

import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals

class FileModerationRepositoryConcurrencyTest {
    @Test
    fun concurrentWarningsAcrossRepositoryInstancesPreserveEveryIncrement() {
        val root = Files.createTempDirectory("shinigami-moderation-concurrency")
        val repositories = listOf(FileModerationRepository(root), FileModerationRepository(root))
        val executor = Executors.newFixedThreadPool(8)
        try {
            val tasks = (1..80).map { index ->
                java.util.concurrent.Callable {
                    repositories[index % repositories.size].addWarning("target-user", "warning-$index")
                }
            }
            executor.invokeAll(tasks).forEach { it.get() }
            assertEquals(80, repositories.first().state("target-user").warningCount)
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
            root.toFile().deleteRecursively()
        }
    }
}
