package streamix.auth

import streamix.api.ShinigamiUser
import streamix.api.UserRelationship

/**
 * Durable user/account boundary.
 *
 * Authentication providers must resolve into this repository; the repository
 * never depends on AniList identities.
 */
interface UserRepository {
    fun findById(id: String): ShinigamiUser?
    fun findByExternalIdentity(provider: String, subject: String): ShinigamiUser?
    fun findByUsername(username: String): ShinigamiUser?
    fun search(query: String, page: Int = 1, perPage: Int = 20): List<ShinigamiUser>
    fun count(): Long
    fun allIds(): List<String>
    fun create(
        username: String,
        externalProvider: String,
        externalSubject: String,
        displayName: String? = null,
        email: String? = null
    ): ShinigamiUser
    fun update(user: ShinigamiUser): ShinigamiUser
    fun setRelationship(userId: String, targetUserId: String, following: Boolean? = null, blocked: Boolean? = null): ShinigamiUser
    fun relationship(userId: String, targetUserId: String): UserRelationship
    fun countFollowing(userId: String): Long
    fun countFollowers(userId: String): Long
    fun following(userId: String, page: Int = 1, perPage: Int = 20): List<ShinigamiUser>
    fun followers(userId: String, page: Int = 1, perPage: Int = 20): List<ShinigamiUser>
}
