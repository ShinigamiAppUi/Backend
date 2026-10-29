package streamix.admin

import com.google.gson.Gson
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import java.util.concurrent.Executors
import streamix.api.AdminRole

class FileAdminRoleRepositoryFailClosedTest {
    @Test
    fun missing_roles_file_defaults_to_member() {
        val root = createTempDirectory("admin-role-missing")
        val repository = FileAdminRoleRepository(root)
        assertEquals(AdminRole.MEMBER, repository.role("missing-user"))
        root.toFile().deleteRecursively()
    }

    @Test
    fun malformed_or_unknown_role_defaults_to_member() {
        val root = createTempDirectory("admin-role-invalid")
        Files.createDirectories(root)
        Files.writeString(
            root.resolve("roles.json"),
            Gson().toJson(listOf(mapOf("userId" to "u-1", "role" to "NOT_A_REAL_ROLE")))
        )

        val repository = FileAdminRoleRepository(root)
        assertEquals(AdminRole.MEMBER, repository.role("u-1"))
        root.toFile().deleteRecursively()
    }

    @Test
    fun persisted_backend_admin_is_explicitly_required_for_backend_control() {
        val root = createTempDirectory("admin-role-explicit")
        val repository = FileAdminRoleRepository(root)
        repository.setRole("u-1", AdminRole.BACKEND_ADMIN)

        assertEquals(AdminRole.BACKEND_ADMIN, repository.role("u-1"))
        root.toFile().deleteRecursively()
    }
    @Test
    fun concurrentRoleUpdatesAcrossRepositoryInstancesPreserveEveryRole() {
        val root = createTempDirectory("admin-role-concurrency")
        val repositories = listOf(FileAdminRoleRepository(root), FileAdminRoleRepository(root))
        val executor = Executors.newFixedThreadPool(8)
        try {
            val tasks = (1..80).map { index ->
                java.util.concurrent.Callable {
                    repositories[index % repositories.size].setRole("user-$index", AdminRole.BACKEND_ADMIN)
                }
            }
            executor.invokeAll(tasks).forEach { it.get() }
            val reader = FileAdminRoleRepository(root)
            (1..80).forEach { index ->
                assertEquals(AdminRole.BACKEND_ADMIN, reader.role("user-$index"))
            }
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
            root.toFile().deleteRecursively()
        }
    }
}
