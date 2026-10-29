package streamix.admin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import streamix.api.AdminPermission
import streamix.api.AdminRole
import streamix.api.ShinigamiUser

class AdminAccessServiceFailClosedTest {
    @Test
    fun missing_role_defaults_to_member_even_when_legacy_flags_are_true() {
        val access = AdminAccessService(FakeRoles())
        val user = user(isAdmin = true, isModerator = true)

        assertEquals(AdminRole.MEMBER, access.role(user))
        assertFalse(access.can(user, AdminPermission.ACCESS_BACKEND_CONTROL))
        assertFalse(access.can(user, AdminPermission.MANAGE_ROLES))
    }

    @Test
    fun persisted_role_is_the_only_source_of_privilege() {
        val roles = FakeRoles()
        val access = AdminAccessService(roles)
        val user = user(isAdmin = false, isModerator = false)

        assertEquals(AdminRole.MEMBER, access.role(user))
        roles.value = AdminRole.BACKEND_ADMIN
        assertEquals(AdminRole.BACKEND_ADMIN, access.role(user))
        assertTrue(access.can(user, AdminPermission.ACCESS_BACKEND_CONTROL))
        assertFalse(access.can(user, AdminPermission.MANAGE_ROLES))
    }

    @Test
    fun unknown_persisted_role_fails_closed_to_member() {
        val roles = FakeRoles()
        roles.value = AdminRole.MEMBER
        val access = AdminAccessService(roles)
        assertEquals(AdminRole.MEMBER, access.role(user()))
        assertFalse(access.can(user(), AdminPermission.ACCESS_BACKEND_CONTROL))
    }

    private fun user(
        isAdmin: Boolean = false,
        isModerator: Boolean = false
    ) = ShinigamiUser(
        id = "u-1",
        username = "user-1",
        isAdmin = isAdmin,
        isModerator = isModerator
    )

    private class FakeRoles : AdminRoleRepository {
        var value: AdminRole = AdminRole.MEMBER

        override fun role(userId: String): AdminRole = value

        override fun setRole(userId: String, role: AdminRole): AdminRole {
            value = role
            return role
        }
    }
}
