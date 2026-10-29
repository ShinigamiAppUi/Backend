package streamix.admin

import streamix.api.AdminAuditEntry
import streamix.api.AdminDashboard
import streamix.api.AdminPermission
import streamix.api.AdminReport
import streamix.api.AdminRole
import streamix.api.AdminUserView
import streamix.api.ModerationStatus
import streamix.api.ReportStatus
import streamix.auth.UserRepository
import java.time.Instant
import java.util.UUID

class AdminService(
    private val users: UserRepository,
    private val access: AdminAccessService,
    private val roles: AdminRoleRepository,
    private val moderation: ModerationRepository,
    private val reports: ReportRepository,
    private val audit: AdminAuditRepository
) {
    fun dashboard(actorId: String): AdminDashboard {
        val actor = users.findById(actorId) ?: error("user not found")
        access.require(actor, AdminPermission.VIEW_DASHBOARD)
        val total = users.count()
        val all = users.search("", 1, 100)
        val states = all.map { moderation.state(it.id) }
        return AdminDashboard(
            totalUsers = total,
            activeUsers = (total - moderationCount(ModerationStatus.SUSPENDED) - moderationCount(ModerationStatus.BANNED)).coerceAtLeast(0),
            suspendedUsers = moderationCount(ModerationStatus.SUSPENDED),
            bannedUsers = moderationCount(ModerationStatus.BANNED),
            pendingReports = reports.count(ReportStatus.PENDING),
            recentActions = audit.recent(20)
        )
    }

    fun users(actorId: String, query: String, page: Int, perPage: Int): List<AdminUserView> {
        val actor = users.findById(actorId) ?: error("user not found")
        access.require(actor, AdminPermission.VIEW_USERS)
        return users.search(query, page, perPage).map(::view)
    }

    fun user(actorId: String, targetId: String): AdminUserView {
        val actor = users.findById(actorId) ?: error("user not found")
        access.require(actor, AdminPermission.VIEW_USERS)
        val target = users.findById(targetId) ?: error("user not found")
        return view(target)
    }

    fun setRole(actorId: String, targetId: String, role: AdminRole): AdminUserView {
        val actor = users.findById(actorId) ?: error("user not found")
        access.require(actor, AdminPermission.MANAGE_ROLES)
        if (role == AdminRole.FOUNDER || role == AdminRole.BACKEND_ADMIN) {
            access.require(actor, AdminPermission.ACCESS_BACKEND_CONTROL)
        }
        val target = users.findById(targetId) ?: error("user not found")
        // Elevated backend roles require backend-control permission to change as well as to assign.
        if (access.hasRole(target, AdminRole.BACKEND_ADMIN)) {
            access.require(actor, AdminPermission.ACCESS_BACKEND_CONTROL)
        }
        roles.setRole(target.id, role)
        record(actorId, "ROLE_CHANGED", "user", target.id, mapOf("role" to role.name))
        return view(target)
    }

    fun warn(actorId: String, targetId: String, reason: String?): AdminUserView {
        val actor = users.findById(actorId) ?: error("user not found")
        access.require(actor, AdminPermission.WARN_USERS)
        val target = users.findById(targetId) ?: error("user not found")
        moderation.addWarning(target.id, reason)
        record(actorId, "USER_WARNED", "user", target.id, reason?.let { mapOf("reason" to it) } ?: emptyMap())
        return view(target)
    }

    fun setStatus(
        actorId: String,
        targetId: String,
        status: ModerationStatus,
        reason: String? = null,
        suspendedUntil: String? = null
    ): AdminUserView {
        val actor = users.findById(actorId) ?: error("user not found")
        when (status) {
            ModerationStatus.ACTIVE -> access.require(actor, AdminPermission.MODERATE_CONTENT)
            ModerationStatus.SUSPENDED -> access.require(actor, AdminPermission.SUSPEND_USERS)
            ModerationStatus.BANNED -> access.require(actor, AdminPermission.BAN_USERS)
        }
        val target = users.findById(targetId) ?: error("user not found")
        moderation.setStatus(target.id, status, suspendedUntil, reason)
        record(
            actorId,
            "USER_STATUS_CHANGED",
            "user",
            target.id,
            mapOf("status" to status.name) + (reason?.let { mapOf("reason" to it) } ?: emptyMap())
        )
        return view(target)
    }

    fun reports(actorId: String, page: Int, perPage: Int): List<AdminReport> {
        val actor = users.findById(actorId) ?: error("user not found")
        access.require(actor, AdminPermission.VIEW_REPORTS)
        return reports.list(page, perPage)
    }

    fun updateReport(actorId: String, report: AdminReport): AdminReport {
        val actor = users.findById(actorId) ?: error("user not found")
        access.require(actor, AdminPermission.MODERATE_CONTENT)
        val existing = reports.find(report.id) ?: error("report not found")
        // The request may change workflow fields only. Reporter, target, type,
        // description, and creation time are immutable report evidence.
        val updated = reports.update(existing.copy(
            status = report.status,
            assignedTo = report.assignedTo ?: existing.assignedTo ?: actor.id,
            resolvedAt = if (report.status == ReportStatus.RESOLVED || report.status == ReportStatus.DISMISSED) {
                Instant.now().toString()
            } else null
        ))
        record(actorId, "REPORT_UPDATED", "report", report.id, mapOf("status" to report.status.name))
        return updated
    }

    fun auditLog(actorId: String, limit: Int = 100, operationId: String? = null): List<AdminAuditEntry> {
        val actor = users.findById(actorId) ?: error("user not found")
        access.require(actor, AdminPermission.VIEW_AUDIT_LOG)
        val lookup = operationId?.trim()?.takeIf { it.isNotEmpty() }
        return if (lookup == null) audit.recent(limit) else audit.findByOperationId(lookup)
    }

    private fun moderationCount(status: ModerationStatus): Long =
        users.allIds().count { moderation.state(it).status == status }.toLong()

    private fun view(user: streamix.api.ShinigamiUser): AdminUserView {
        val state = moderation.state(user.id)
        return AdminUserView(
            user = user,
            role = access.role(user),
            status = state.status,
            warningCount = state.warningCount,
            reportCount = reports.countForUser(user.id).toInt()
        )
    }

    private fun record(
        actorId: String,
        action: String,
        targetType: String?,
        targetId: String?,
        metadata: Map<String, String>
    ) {
        audit.append(
            AdminAuditEntry(
                id = UUID.randomUUID().toString(),
                actorId = actorId,
                action = action,
                targetType = targetType,
                targetId = targetId,
                metadata = metadata,
                createdAt = Instant.now().toString()
            )
        )
    }
}
