package streamix.admin

import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import streamix.api.AdminAuditEntry

class FileAdminAuditRepositoryConcurrencyTest {
    @Test
    fun separateInstancesDoNotLoseConcurrentAppendsToSameFile() {
        val directory = Files.createTempDirectory("shinigami-audit-concurrency")
        val repositories = listOf(
            FileAdminAuditRepository(directory),
            FileAdminAuditRepository(directory)
        )
        val workers = Executors.newFixedThreadPool(8)
        try {
            val tasks = (0 until 100).map { index ->
                java.util.concurrent.Callable {
                    repositories[index % repositories.size].append(
                        AdminAuditEntry(
                            id = "entry-$index",
                            actorId = "test-actor",
                            action = "TEST",
                            targetType = "TEST",
                            targetId = index.toString(),
                            createdAt = "2026-01-01T00:00:00Z"
                        )
                    )
                }
            }
            workers.invokeAll(tasks).forEach { it.get() }
            assertEquals(100, repositories.first().recent(500).size)
            assertEquals((0 until 100).map { "entry-$it" }.toSet(),
                repositories.first().recent(500).map { it.id }.toSet())
        } finally {
            workers.shutdownNow()
            workers.awaitTermination(5, TimeUnit.SECONDS)
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun findByOperationIdReturnsCorrelatedEventsBeyondRecentWindow() {
        val directory = Files.createTempDirectory("shinigami-audit-operation")
        val repository = FileAdminAuditRepository(directory)
        try {
            repeat(520) { index ->
                repository.append(AdminAuditEntry(
                    id = "unrelated-$index", actorId = "test-actor", action = "UNRELATED",
                    metadata = mapOf("operationId" to "other-$index"),
                    createdAt = "2026-10-20T00:00:00Z"
                ))
            }
            repository.append(AdminAuditEntry(
                id = "intent", actorId = "test-actor", action = "CATALOG_SOURCE_UPDATE_INTENT",
                metadata = mapOf("operationId" to "target-operation"),
                createdAt = "2026-10-20T00:00:01Z"
            ))
            repository.append(AdminAuditEntry(
                id = "completion", actorId = "test-actor", action = "CATALOG_SOURCE_UPDATE",
                metadata = mapOf("operationId" to "target-operation"),
                createdAt = "2026-10-20T00:00:02Z"
            ))
            assertEquals(listOf("completion", "intent"), repository.findByOperationId("target-operation").map { it.id })
            assertEquals(emptyList(), repository.findByOperationId("missing-operation"))
            assertEquals(emptyList(), repository.findByOperationId(" "))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

}
