package streamix.auth

import streamix.api.UserPage
import streamix.api.UserProfile
import streamix.api.UserStats
import streamix.api.ShinigamiUser

class UserService(
    private val users: UserRepository,
    private val premiumStatus: (String) -> Map<String, Any?> = { emptyMap() }
) {
    fun me(userId: String): UserProfile =
        profile(userId, userId)

    fun profile(userId: String, viewerId: String? = null): UserProfile {
        val user = users.findById(userId) ?: error("user not found")
        val viewer = viewerId?.let(users::findById)
        val relationship = viewerId?.takeIf { it != userId }?.let { users.relationship(it, userId) }
        val entitlement = premiumStatus(userId)
        val premium = entitlement["premium"] as? Boolean ?: false
        val premiumDaysRemaining = (entitlement["premiumDaysRemaining"] as? Number)?.toInt()?.coerceAtLeast(0) ?: 0
        return UserProfile(
            user = user.copy(
                isFollowing = relationship?.following ?: false,
                isFollower = relationship?.follower ?: false,
                isBlocked = relationship?.blocked ?: false
            ),
            stats = UserStats(),
            gamification = streamix.api.UserGamification(
                premium = premium,
                premiumDaysRemaining = premiumDaysRemaining
            ),
            followerCount = countFollowers(userId),
            followingCount = countFollowing(userId)
        )
    }

    private fun countFollowing(userId: String): Long = users.countFollowing(userId)

    private fun countFollowers(userId: String): Long = users.countFollowers(userId)

    fun following(userId: String, viewerId: String, page: Int = 1, perPage: Int = 20): UserPage =
        relationshipPage(users.following(userId, page, perPage.coerceIn(1, 100) + 1), viewerId, page, perPage)

    fun followers(userId: String, viewerId: String, page: Int = 1, perPage: Int = 20): UserPage =
        relationshipPage(users.followers(userId, page, perPage.coerceIn(1, 100) + 1), viewerId, page, perPage)

    private fun relationshipPage(items: List<ShinigamiUser>, viewerId: String, page: Int, perPage: Int): UserPage {
        val safePage = page.coerceAtLeast(1)
        val safePerPage = perPage.coerceIn(1, 100)
        val hasNextPage = items.size > safePerPage
        val enriched = items.take(safePerPage).map { item ->
            val relationship = if (item.id == viewerId) null else users.relationship(viewerId, item.id)
            item.copy(
                isFollowing = relationship?.following ?: false,
                isFollower = relationship?.follower ?: false,
                isBlocked = relationship?.blocked ?: false
            )
        }
        return UserPage(enriched, safePage, safePerPage, hasNextPage, null)
    }

    fun search(query: String, page: Int = 1, perPage: Int = 20): UserPage {
        val safePage = page.coerceAtLeast(1)
        val safePerPage = perPage.coerceIn(1, 100)
        val results = users.search(query, safePage, safePerPage + 1)
        val usersOnPage = results.take(safePerPage)
        return UserPage(
            users = usersOnPage,
            page = safePage,
            perPage = safePerPage,
            hasNextPage = results.size > safePerPage,
            total = null
        )
    }

    fun updateProfile(
        userId: String,
        username: String,
        displayName: String?,
        bio: String?,
        avatarUrl: String?,
        bannerUrl: String?
    ): ShinigamiUser {
        val current = users.findById(userId) ?: error("user not found")
        return users.update(
            current.copy(
                username = username.trim(),
                displayName = displayName?.trim()?.ifBlank { null },
                bio = bio,
                avatarUrl = avatarUrl,
                bannerUrl = bannerUrl
            )
        )
    }
}
