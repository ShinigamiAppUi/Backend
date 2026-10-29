package streamix.api

import streamix.readingcatalog.ReadingKind

/** Read-only catalog DTOs deliberately omit provider source URLs and credentials. */
data class ReadingCatalogBrowseItem(
    val id: String,
    val kind: ReadingKind,
    val title: String,
    val coverUrl: String? = null,
    val description: String? = null,
    val author: String? = null,
    val genres: List<String> = emptyList(),
    val status: String? = null,
    val chapterCount: Int = 0,
    val updatedAt: Long = 0L,
    /** Canonical metadata identity; metadata itself is owned by AniList. */
    val metadataId: Long? = null
)

data class ReadingCatalogBrowsePage(
    val items: List<ReadingCatalogBrowseItem>,
    val page: Int,
    val perPage: Int,
    val total: Int,
    val hasNextPage: Boolean
)

data class ReadingCatalogChapterSummary(
    val id: String,
    val number: String,
    val title: String? = null
)
