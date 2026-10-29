package streamix.admin

import streamix.api.AdminRole

interface AdminRoleRepository {
    fun role(userId: String): AdminRole
    fun setRole(userId: String, role: AdminRole): AdminRole
}
