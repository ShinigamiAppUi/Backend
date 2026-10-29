package streamix.admin

import streamix.api.AdminPermission
import streamix.api.AdminRole
import streamix.api.ShinigamiUser

class AdminAccessService(private val roles: AdminRoleRepository) {
    fun role(user: ShinigamiUser): AdminRole {
        return roles.role(user.id)
    }

    fun hasRole(user: ShinigamiUser, minimum: AdminRole): Boolean = rank(role(user)) >= rank(minimum)
    fun can(user: ShinigamiUser, permission: AdminPermission): Boolean = permission in permissions(role(user))
    fun require(user: ShinigamiUser, permission: AdminPermission) {
        if (!can(user, permission)) throw SecurityException("permission denied: " + permission)
    }

    private fun permissions(role: AdminRole): Set<AdminPermission> = when (role) {
        AdminRole.FOUNDER -> AdminPermission.values().toSet()
        AdminRole.BACKEND_ADMIN -> setOf(
            AdminPermission.VIEW_DASHBOARD, AdminPermission.VIEW_USERS,
            AdminPermission.VIEW_REPORTS, AdminPermission.VIEW_AUDIT_LOG,
            AdminPermission.ACCESS_BACKEND_CONTROL, AdminPermission.MANAGE_CATALOG
        )
        AdminRole.ADMIN -> setOf(
            AdminPermission.VIEW_DASHBOARD, AdminPermission.VIEW_USERS,
            AdminPermission.VIEW_REPORTS, AdminPermission.MODERATE_CONTENT,
            AdminPermission.WARN_USERS, AdminPermission.SUSPEND_USERS,
            AdminPermission.BAN_USERS, AdminPermission.MANAGE_ROLES,
            AdminPermission.MANAGE_ANNOUNCEMENTS, AdminPermission.VIEW_AUDIT_LOG,
            AdminPermission.MANAGE_CATALOG
        )
        AdminRole.MODERATOR -> setOf(
            AdminPermission.VIEW_DASHBOARD, AdminPermission.VIEW_USERS,
            AdminPermission.VIEW_REPORTS, AdminPermission.MODERATE_CONTENT,
            AdminPermission.WARN_USERS, AdminPermission.SUSPEND_USERS
        )
        AdminRole.HELPER -> setOf(AdminPermission.VIEW_USERS, AdminPermission.VIEW_REPORTS)
        AdminRole.MEMBER -> emptySet()
    }

    private fun rank(role: AdminRole): Int = when (role) {
        AdminRole.MEMBER -> 0
        AdminRole.HELPER -> 1
        AdminRole.MODERATOR -> 2
        AdminRole.ADMIN -> 3
        AdminRole.BACKEND_ADMIN -> 4
        AdminRole.FOUNDER -> 5
    }
}
