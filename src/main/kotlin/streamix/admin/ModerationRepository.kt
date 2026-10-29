package streamix.admin

import streamix.api.ModerationStatus

data class ModerationState(
    val userId: String,
    val status: ModerationStatus = ModerationStatus.ACTIVE,
    val suspendedUntil: String? = null,
    val reason: String? = null,
    val warningCount: Int = 0
)

interface ModerationRepository {
    fun state(userId: String): ModerationState
    fun setStatus(userId: String, status: ModerationStatus, suspendedUntil: String?, reason: String?): ModerationState
    fun addWarning(userId: String, reason: String?): ModerationState
}
