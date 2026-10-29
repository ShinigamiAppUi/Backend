package streamix.api

/**
 * User/account contract owned by the Shinigami backend.
 *
 * User IDs are backend-owned opaque strings. They must never be treated as
 * AniList IDs. Anime references remain canonical AniList media IDs.
 */
object UserApiContract {
    const val SESSION = "/api/v1/auth/session"
    const val LOGOUT = "/api/v1/auth/logout"

    const val ME = "/api/v1/users/me"
    const val UPDATE_ME = "/api/v1/users/me/profile"
    const val PROFILE = "/api/v1/users/{userId}"
    const val SEARCH = "/api/v1/users/search"

    const val FOLLOWERS = "/api/v1/users/{userId}/followers"
    const val FOLLOWING = "/api/v1/users/{userId}/following"
    const val FOLLOW = "/api/v1/users/{userId}/follow"
    const val BLOCK = "/api/v1/users/{userId}/block"
}

data class ShinigamiUser(
    val id: String,
    val username: String,
    val displayName: String? = null,
    val bio: String? = null,
    val avatarUrl: String? = null,
    val bannerUrl: String? = null,
    val isFollowing: Boolean = false,
    val isFollower: Boolean = false,
    val isBlocked: Boolean = false,
    val isAdmin: Boolean = false,
    val isModerator: Boolean = false,
    val createdAt: String? = null
)

data class UserRelationship(
    val userId: String,
    val following: Boolean,
    val follower: Boolean,
    val blocked: Boolean
)

data class UserStats(
    val animeTotal: Int = 0,
    val animeWatching: Int = 0,
    val animeCompleted: Int = 0,
    val animePlanned: Int = 0,
    val animePaused: Int = 0,
    val animeDropped: Int = 0,
    val episodesWatched: Int = 0,
    val meanScore: Double? = null
)

data class UserGamification(
    val level: Int = 1,
    val xp: Long = 0,
    val xpToNextLevel: Long = 100,
    val rank: String = "C",
    val border: String? = null,
    val badges: List<String> = emptyList(),
    val watchStreakDays: Int = 0,
    val totalGifts: Long = 0,
    val premium: Boolean = false,
    val premiumDaysRemaining: Int = 0
)

data class UserProfile(
    val user: ShinigamiUser,
    val stats: UserStats = UserStats(),
    val gamification: UserGamification = UserGamification(),
    val followerCount: Long = 0,
    val followingCount: Long = 0
)

data class UserPage(
    val users: List<ShinigamiUser>,
    val page: Int,
    val perPage: Int,
    val hasNextPage: Boolean,
    val total: Long? = null
)

data class SessionResponse(
    val user: ShinigamiUser,
    val expiresAt: String? = null,
    val token: String? = null
)


data class UpdateProfileRequest(
    val username: String? = null,
    val displayName: String? = null,
    val bio: String? = null,
    val avatarUrl: String? = null,
    val bannerUrl: String? = null
)
