package streamix.auth

import java.time.Instant

data class AuthSession(
    val token: String,
    val userId: String,
    val createdAt: Instant,
    val expiresAt: Instant
)

interface SessionRepository {
    fun create(userId: String, ttlSeconds: Long): AuthSession
    fun find(token: String): AuthSession?
    fun delete(token: String)
}
