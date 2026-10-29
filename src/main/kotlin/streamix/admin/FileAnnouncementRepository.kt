package streamix.admin

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import streamix.api.Announcement
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

class FileAnnouncementRepository(
    directory: Path,
    private val gson: Gson = Gson()
) : AnnouncementRepository {
    private val directory: Path = directory.toAbsolutePath().normalize()
    private val file = this.directory.resolve("announcements.json")
    private val lock: Any = locks.computeIfAbsent(file) { Any() }

    companion object {
        // Serialize read-modify-write across instances in this JVM, not across processes.
        private val locks = ConcurrentHashMap<Path, Any>()
    }

    override fun list(page: Int, perPage: Int): List<Announcement> = synchronized(lock) {
        val size = perPage.coerceIn(1, 100)
        val items = read().asReversed()
        val offset = (page.coerceAtLeast(1).toLong() - 1L) * size.toLong()
        if (offset >= items.size.toLong()) return@synchronized emptyList()
        items.drop(offset.toInt()).take(size)
    }

    override fun find(id: String): Announcement? = synchronized(lock) {
        read().firstOrNull { it.id == id }
    }

    override fun create(announcement: Announcement): Announcement = synchronized(lock) {
        require(read().none { it.id == announcement.id }) { "announcement already exists" }
        write(read() + announcement)
        announcement
    }

    override fun update(announcement: Announcement): Announcement = synchronized(lock) {
        require(read().any { it.id == announcement.id }) { "announcement not found" }
        write(read().map { if (it.id == announcement.id) announcement else it })
        announcement
    }

    override fun delete(id: String): Boolean = synchronized(lock) {
        val current = read()
        if (current.none { it.id == id }) return false
        write(current.filterNot { it.id == id })
        true
    }

    private fun read(): List<Announcement> {
        if (!Files.exists(file)) return emptyList()
        val json = Files.readString(file)
        if (json.isBlank()) return emptyList()
        return gson.fromJson<List<Announcement>>(
            json,
            object : TypeToken<List<Announcement>>() {}.type
        ) ?: throw java.io.IOException("Persisted JSON must be a non-null array: $file")
    }

    private fun write(items: List<Announcement>) {
        directory.let(Files::createDirectories)
        val temp = Files.createTempFile(directory, "announcements-", ".tmp")
        try {
            Files.writeString(temp, gson.toJson(items))
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
