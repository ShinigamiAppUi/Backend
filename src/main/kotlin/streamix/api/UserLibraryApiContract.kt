package streamix.api

/**
 * User-owned anime library/state.
 *
 * Anime metadata is still resolved from AniList, but list membership,
 * progress, score and personal status belong to the Shinigami account.
 */
object UserLibraryApiContract {
    const val LISTS = "/api/v1/users/me/library"
    const val LIST = "/api/v1/users/me/library/{mediaId}"
    const val STATUS = "/api/v1/users/me/library/{mediaId}/status"
    const val PROGRESS = "/api/v1/users/me/library/{mediaId}/progress"
    const val SCORE = "/api/v1/users/me/library/{mediaId}/score"
}

enum class LibraryStatus {
    PLANNING,
    WATCHING,
    COMPLETED,
    PAUSED,
    DROPPED,
    REWATCHING
}

data class UserAnimeState(
    val mediaId: Long,
    val status: LibraryStatus? = null,
    val progress: Int = 0,
    val score: Double? = null,
    val isFavorite: Boolean = false,
    val notes: String? = null,
    val updatedAt: String? = null
)

data class UserLibraryPage(
    val items: List<UserAnimeState>,
    val page: Int,
    val perPage: Int,
    val hasNextPage: Boolean,
    val total: Long? = null
)


data class UpsertLibraryRequest(
    val status: LibraryStatus? = null,
    val progress: Int = 0,
    val score: Double? = null,
    val isFavorite: Boolean = false,
    val notes: String? = null
)

data class LibraryStatusRequest(val status: LibraryStatus?)
data class LibraryProgressRequest(val progress: Int? = null)
data class LibraryScoreRequest(val score: Double?)
