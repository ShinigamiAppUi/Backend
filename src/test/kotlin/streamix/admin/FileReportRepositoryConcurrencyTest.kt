package streamix.admin

import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import streamix.api.AdminReport
import streamix.api.ReportType

class FileReportRepositoryConcurrencyTest {
    @Test
    fun concurrentCreatesAcrossRepositoryInstancesPreserveEveryReport() {
        val root = Files.createTempDirectory("shinigami-report-concurrency")
        val repositories = listOf(FileReportRepository(root), FileReportRepository(root))
        val executor = Executors.newFixedThreadPool(8)
        try {
            val tasks = (1..80).map { index ->
                java.util.concurrent.Callable {
                    repositories[index % repositories.size].create(
                        AdminReport(
                            id = "report-$index",
                            reporterId = "reporter-$index",
                            targetUserId = "target-user-$index",
                            type = ReportType.OTHER,
                            description = "Description $index",
                            createdAt = "2026-01-01T00:00:00Z"
                        )
                    )
                }
            }
            executor.invokeAll(tasks).forEach { it.get() }
            val reader = FileReportRepository(root)
            val persisted = reader.list(1, 100)
            assertEquals(80L, reader.count())
            assertEquals((1..80).map { "report-$it" }.toSet(), persisted.map { it.id }.toSet())
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
            root.toFile().deleteRecursively()
        }
    }
}
