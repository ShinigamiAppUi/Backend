package streamix.admin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import streamix.api.AdminAuditEntry
import streamix.api.AdminPermission
import streamix.api.AdminReport
import streamix.api.AdminRole
import streamix.api.ModerationStatus
import streamix.api.ReportStatus
import streamix.api.ReportType
import streamix.api.ShinigamiUser
import streamix.api.UserRelationship
import streamix.auth.UserRepository

class AdminServiceReportIntegrityTest {
    @Test
    fun update_report_preserves_original_evidence_and_only_changes_workflow_fields() {
        val actor = ShinigamiUser(id = "admin-1", username = "admin")
        val users = FakeUsers(actor)
        val roles = FakeRoles()
        val reports = FakeReports(
            AdminReport(
                id = "report-1",
                reporterId = "original-reporter",
                targetUserId = "original-target",
                targetContentId = "content-1",
                type = ReportType.HARASSMENT,
                description = "Original report details",
                status = ReportStatus.PENDING,
                assignedTo = null,
                createdAt = "2026-01-01T00:00:00Z",
                resolvedAt = null
            )
        )
        val service = AdminService(
            users = users,
            access = AdminAccessService(roles),
            roles = roles,
            moderation = FakeModeration(),
            reports = reports,
            audit = FakeAudit()
        )

        val updated = service.updateReport(
            actor.id,
            AdminReport(
                id = "report-1",
                reporterId = "forged-reporter",
                targetUserId = "forged-target",
                targetContentId = "forged-content",
                type = ReportType.SPAM,
                description = "Forged details",
                status = ReportStatus.REVIEWING,
                assignedTo = null,
                createdAt = "2099-01-01T00:00:00Z",
                resolvedAt = "forged-resolved-time"
            )
        )

        assertEquals("original-reporter", updated.reporterId)
        assertEquals("original-target", updated.targetUserId)
        assertEquals("content-1", updated.targetContentId)
        assertEquals(ReportType.HARASSMENT, updated.type)
        assertEquals("Original report details", updated.description)
        assertEquals("2026-01-01T00:00:00Z", updated.createdAt)
        assertEquals(ReportStatus.REVIEWING, updated.status)
        assertEquals(actor.id, updated.assignedTo)
        assertNull(updated.resolvedAt)
    }

    private class FakeUsers(private val actor: ShinigamiUser) : UserRepository {
        override fun findById(id: String) = actor.takeIf { it.id == id }
        override fun findByExternalIdentity(provider: String, subject: String): ShinigamiUser? = null
        override fun findByUsername(username: String): ShinigamiUser? = null
        override fun search(query: String, page: Int, perPage: Int) = listOf(actor)
        override fun count() = 1L
        override fun allIds() = listOf(actor.id)
        override fun create(username: String, externalProvider: String, externalSubject: String, displayName: String?, email: String?) = actor
        override fun update(user: ShinigamiUser) = user
        override fun setRelationship(userId: String, targetUserId: String, following: Boolean?, blocked: Boolean?) = actor
        override fun relationship(userId: String, targetUserId: String) = UserRelationship(targetUserId, false, false, false)
        override fun countFollowing(userId: String) = 0L
        override fun countFollowers(userId: String) = 0L
        override fun following(userId: String, page: Int, perPage: Int) = emptyList<ShinigamiUser>()
        override fun followers(userId: String, page: Int, perPage: Int) = emptyList<ShinigamiUser>()
    }

    private class FakeRoles : AdminRoleRepository {
        override fun role(userId: String) = AdminRole.ADMIN
        override fun setRole(userId: String, role: AdminRole) = role
    }

    private class FakeModeration : ModerationRepository {
        override fun state(userId: String) = ModerationState(userId, ModerationStatus.ACTIVE)
        override fun setStatus(userId: String, status: ModerationStatus, suspendedUntil: String?, reason: String?) =
            ModerationState(userId, status, suspendedUntil, reason)
        override fun addWarning(userId: String, reason: String?) = ModerationState(userId, warningCount = 1)
    }

    private class FakeReports(initial: AdminReport) : ReportRepository {
        private var stored = initial
        override fun list(page: Int, perPage: Int) = listOf(stored)
        override fun find(id: String) = stored.takeIf { it.id == id }
        override fun create(report: AdminReport): AdminReport { stored = report; return report }
        override fun update(report: AdminReport): AdminReport { stored = report; return report }
        override fun count(status: ReportStatus?) = if (status == null || stored.status == status) 1L else 0L
        override fun countForUser(userId: String) = if (stored.targetUserId == userId) 1L else 0L
    }

    private class FakeAudit : AdminAuditRepository {
        override fun append(entry: AdminAuditEntry) = Unit
        override fun recent(limit: Int) = emptyList<AdminAuditEntry>()
    }
}
