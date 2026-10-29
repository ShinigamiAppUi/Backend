package streamix.readingcatalog

interface ReadingCatalogRepository {
    fun all(kind: ReadingKind? = null): List<ReadingCatalogEntry>
    fun find(id: String): ReadingCatalogEntry?
    fun upsert(entry: ReadingCatalogEntry): ReadingCatalogEntry
    fun replace(entry: ReadingCatalogEntry): ReadingCatalogEntry = upsert(entry)
    fun delete(id: String): Boolean
}
