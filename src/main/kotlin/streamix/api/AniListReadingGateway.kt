package streamix.api

import streamix.readingcatalog.ReadingKind

/**
 * AniList metadata for the reading domain. This is deliberately separate from
 * ProviderAnime and is not persisted in the Reading Catalog.
 */
data class AniListReadingMedia(
    val anilistId: Long,
    val kind: ReadingKind,
    val title: String,
    val url: String,
    val coverImage: String? = null,
    val bannerImage: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val format: String? = null,
    val countryOfOrigin: String? = null,
    val status: String? = null,
    val averageScore: Int? = null
)

/** Dedicated search/detail boundary for AniList's MANGA media type. */
interface AniListReadingGateway {
    fun search(query: String, page: Int = 1): List<AniListReadingMedia>
    fun detail(anilistId: Long): AniListReadingMedia?
}
