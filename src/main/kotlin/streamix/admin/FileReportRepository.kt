package streamix.admin

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import streamix.api.AdminReport
import streamix.api.ReportStatus
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

class FileReportRepository(
    directory: Path,
    private val gson: Gson = Gson()
) : ReportRepository {
    private val directory: Path = directory.toAbsolutePath().normalize()
    private val file = this.directory.resolve("reports.json")
    private val lock: Any = locks.computeIfAbsent(file) { Any() }

    companion object {
        // Protect read-modify-write across instances in this JVM, not across processes.
        private val locks = ConcurrentHashMap<Path, Any>()
    }

    override fun list(page: Int, perPage: Int): List<AdminReport> = synchronized(lock) {
        val size = perPage.coerceIn(1, 100)
        val safePage = page.coerceAtLeast(1)
        val offset = (safePage.toLong() - 1L) * size
        if (offset > Int.MAX_VALUE) return@synchronized emptyList()
        read().asReversed().drop(offset.toInt()).take(size)
    }

    override fun find(id: String): AdminReport? = synchronized(lock) {
        read().firstOrNull { it.id == id }
    }

    override fun create(report: AdminReport): AdminReport = synchronized(lock) {
        val current = read()
        require(current.none { it.id == report.id }) { "report already exists" }
        write(current + report)
        report
    }

    override fun update(report: AdminReport): AdminReport = synchronized(lock) {
        val current = read()
        require(current.any { it.id == report.id }) { "report not found" }
        write(current.map { if (it.id == report.id) report else it })
        report
    }

    override fun count(status: ReportStatus?): Long = synchronized(lock) {
        read().count { status == null || it.status == status }.toLong()
    }

    override fun countForUser(userId: String): Long = synchronized(lock) {
        read().count { it.targetUserId == userId }.toLong()
    }

    private fun read(): List<AdminReport> {
        if (!Files.exists(file)) return emptyList()
        val json = Files.readString(file)
        if (json.isBlank()) return emptyList()
        return gson.fromJson<List<AdminReport>>(
            json,
            object : TypeToken<List<AdminReport>>() {}.type
        ) ?: throw java.io.IOException("Persisted JSON must be a non-null array: $file")
    }

    private fun write(reports: List<AdminReport>) {
        Files.createDirectories(directory)
        val temp = Files.createTempFile(directory, "reports-", ".tmp")
        try {
            Files.writeString(temp, gson.toJson(reports))
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}
