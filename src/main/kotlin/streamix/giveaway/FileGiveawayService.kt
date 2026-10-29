package streamix.giveaway

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class GiveawayRef(
    val id: String,
    val mediaId: Long,
    val commentId: String?,
    val creatorId: String,
    val message: String,
    val daysPerGift: Int,
    val totalGifts: Int,
    val claimedUserIds: List<String>,
    val createdAt: String,
    val expiresAt: String? = null,
    val status: String
) {
    val claimedGifts: Int get() = claimedUserIds.size
    val remainingGifts: Int get() = (totalGifts - claimedUserIds.size).coerceAtLeast(0)
}

data class CreateGiveawayRequest(
    val mediaId: Long = 0,
    val commentId: String? = null,
    val message: String? = null,
    val daysPerGift: Int = 1,
    val totalGifts: Int = 1
)

data class GiveawayClaimResult(
    val giveaway: GiveawayRef,
    val premiumUntil: String,
    val premiumDaysRemaining: Int
)

private data class GiveawayStore(
    val giveaways: List<GiveawayRef> = emptyList(),
    val premiumUntilByUser: Map<String, String> = emptyMap()
)

/**
 * Durable MVP store. Giveaway claims and entitlement extension are persisted
 * together under one lock/file so a claim cannot consume a gift without
 * recording the corresponding Premium entitlement.
 */
class FileGiveawayService(
    dataRoot: Path,
    private val gson: Gson = Gson(),
    private val clock: () -> Instant = { Instant.now() }
) {
    private val file = dataRoot.resolve("giveaways.json").toAbsolutePath().normalize()
    private val lock = locks.computeIfAbsent(file) { Any() }
    private val storeType = object : TypeToken<GiveawayStore>() {}.type

    init { Files.createDirectories(file.parent) }

    fun create(creatorId: String, request: CreateGiveawayRequest): GiveawayRef = synchronized(lock) {
        require(creatorId.isNotBlank()) { "creatorId is required" }
        require(request.mediaId > 0) { "mediaId must be positive" }
        require(request.daysPerGift in setOf(1, 3, 7, 30)) { "daysPerGift must be one of 1, 3, 7, 30" }
        require(request.totalGifts in 10..100) { "totalGifts must be between 10 and 100" }
        val message = request.message?.trim().orEmpty()
        require(message.isNotBlank() && message.length <= 280) { "message must contain 1 to 280 characters" }
        val now = clock()
        val ref = GiveawayRef(
            id = UUID.randomUUID().toString(),
            mediaId = request.mediaId,
            commentId = request.commentId?.trim()?.takeIf { it.isNotEmpty() },
            creatorId = creatorId,
            message = message,
            daysPerGift = request.daysPerGift,
            totalGifts = request.totalGifts,
            claimedUserIds = emptyList(),
            createdAt = now.toString(),
            expiresAt = null,
            status = "ACTIVE"
        )
        val store = read()
        write(store.copy(giveaways = store.giveaways + ref))
        ref
    }

    fun list(mediaId: Long): List<GiveawayRef> = synchronized(lock) {
        val now = clock()
        read().giveaways.filter { it.mediaId == mediaId }
            .map { it.copy(status = status(it, now)) }
            .sortedByDescending { it.createdAt }
    }

    fun claim(giveawayId: String, userId: String): GiveawayClaimResult = synchronized(lock) {
        require(userId.isNotBlank()) { "userId is required" }
        val store = read()
        val index = store.giveaways.indexOfFirst { it.id == giveawayId }
        if (index < 0) throw IllegalStateException("giveaway not found")
        val item = store.giveaways[index]
        val now = clock()
        require(item.creatorId != userId) { "creators cannot claim their own giveaway" }
        require(item.claimedUserIds.size < item.totalGifts) { "giveaway has no gifts remaining" }
        require(userId !in item.claimedUserIds) { "you have already claimed this giveaway" }
        val previousUntil = store.premiumUntilByUser[userId]?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val activeUntil = previousUntil?.takeIf { it.isAfter(now) }
        val currentDaysRemaining = activeUntil?.let { ChronoUnit.DAYS.between(now, it).toInt().coerceAtLeast(0) } ?: 0
        require(currentDaysRemaining < MAX_GIVEAWAY_PREMIUM_DAYS) {
            "giveaway claims are limited to users with fewer than $MAX_GIVEAWAY_PREMIUM_DAYS Premium days remaining"
        }
        val start = activeUntil ?: now
        val premiumUntil = start.plus(item.daysPerGift.toLong(), ChronoUnit.DAYS)
        require(!premiumUntil.isAfter(now.plus(MAX_GIVEAWAY_PREMIUM_DAYS.toLong(), ChronoUnit.DAYS))) {
            "this giveaway would exceed the $MAX_GIVEAWAY_PREMIUM_DAYS-day Premium claim limit"
        }
        val updated = item.copy(
            claimedUserIds = item.claimedUserIds + userId,
            status = if (item.claimedUserIds.size + 1 >= item.totalGifts) "EXHAUSTED" else "ACTIVE"
        )
        val updatedGiveaways = store.giveaways.toMutableList().also { it[index] = updated }
        write(store.copy(giveaways = updatedGiveaways, premiumUntilByUser = store.premiumUntilByUser + (userId to premiumUntil.toString())))
        GiveawayClaimResult(updated, premiumUntil.toString(), ChronoUnit.DAYS.between(now, premiumUntil).toInt().coerceAtLeast(0))
    }

    fun premiumStatus(userId: String): Map<String, Any?> = synchronized(lock) {
        val until = read().premiumUntilByUser[userId]?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val now = clock()
        val activeUntil = until?.takeIf { it.isAfter(now) }
        mapOf(
            "premium" to (activeUntil != null),
            "premiumUntil" to activeUntil?.toString(),
            "premiumDaysRemaining" to (activeUntil?.let { ChronoUnit.DAYS.between(now, it).toInt().coerceAtLeast(0) } ?: 0)
        )
    }

    private fun status(item: GiveawayRef, now: Instant): String = when {
        item.claimedUserIds.size >= item.totalGifts -> "EXHAUSTED"
        else -> "ACTIVE"
    }

    private fun read(): GiveawayStore {
        if (!Files.exists(file)) return GiveawayStore()
        val raw = Files.readString(file)
        if (raw.isBlank()) return GiveawayStore()
        return runCatching { gson.fromJson(raw, storeType) ?: GiveawayStore() }
            .getOrElse { throw IllegalStateException("giveaway store is unreadable") }
    }

    private fun write(store: GiveawayStore) {
        val temp = Files.createTempFile(file.parent, "giveaways-", ".tmp")
        try {
            Files.writeString(temp, gson.toJson(store))
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    companion object {
        const val MAX_GIVEAWAY_PREMIUM_DAYS = 7
        private val locks = ConcurrentHashMap<Path, Any>()
    }
}