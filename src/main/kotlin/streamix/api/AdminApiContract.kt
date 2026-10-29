package streamix.api

object AdminApiContract {
    const val DASHBOARD = "/api/v1/admin/dashboard"
    const val USERS = "/api/v1/admin/users"
    const val USER = "/api/v1/admin/users/{userId}"
    const val USER_ACTION = "/api/v1/admin/users/{userId}/actions"
    const val REPORTS = "/api/v1/admin/reports"
    const val REPORT = "/api/v1/admin/reports/{reportId}"
    const val ROLES = "/api/v1/admin/roles"
    const val ROLE = "/api/v1/admin/users/{userId}/role"
    const val ANNOUNCEMENTS = "/api/v1/admin/announcements"
    const val ANNOUNCEMENT = "/api/v1/admin/announcements/{announcementId}"
    const val AUDIT_LOG = "/api/v1/admin/audit-log"
    const val CATALOG_EPISODES = "/api/v1/admin/catalog/anime/{anilistId}/episodes"
    const val CATALOG_SOURCE = "/api/v1/admin/catalog/anime/{anilistId}/episode/{episodeNumber}/sources"
    const val CATALOG_IMPORT = "/api/v1/admin/catalog/anime/{anilistId}/import"
}

enum class AdminRole {
    MEMBER, HELPER, MODERATOR, ADMIN, BACKEND_ADMIN, FOUNDER
}

enum class AdminPermission {
    VIEW_DASHBOARD, VIEW_USERS, VIEW_REPORTS, MODERATE_CONTENT, WARN_USERS,
    SUSPEND_USERS, BAN_USERS, MANAGE_ROLES, MANAGE_ANNOUNCEMENTS, VIEW_AUDIT_LOG,
    ACCESS_BACKEND_CONTROL, MANAGE_CATALOG
}

enum class ModerationStatus { ACTIVE, SUSPENDED, BANNED }
enum class ReportType { SPAM, PROMOTION, HARASSMENT, NSFW, SCAM, OTHER }
enum class ReportStatus { PENDING, REVIEWING, RESOLVED, DISMISSED }

data class AdminDashboard(
    val totalUsers: Long, val activeUsers: Long, val suspendedUsers: Long,
    val bannedUsers: Long, val pendingReports: Long, val pendingAppeals: Long = 0,
    val pendingPromotions: Long = 0, val recentActions: List<AdminAuditEntry> = emptyList()
)

data class AdminUserView(
    val user: ShinigamiUser, val role: AdminRole,
    val status: ModerationStatus = ModerationStatus.ACTIVE,
    val warningCount: Int = 0, val reportCount: Int = 0
)

data class AdminReport(
    val id: String, val reporterId: String, val targetUserId: String? = null,
    val targetContentId: String? = null, val type: ReportType, val description: String,
    val status: ReportStatus = ReportStatus.PENDING, val assignedTo: String? = null,
    val createdAt: String, val resolvedAt: String? = null
)

data class Announcement(
    val id: String, val title: String, val body: String, val imageUrl: String? = null,
    val published: Boolean = false, val createdBy: String, val createdAt: String,
    val publishedAt: String? = null
)

data class AdminAuditEntry(
    val id: String, val actorId: String, val action: String,
    val targetType: String? = null, val targetId: String? = null,
    val metadata: Map<String, String> = emptyMap(), val createdAt: String
)
