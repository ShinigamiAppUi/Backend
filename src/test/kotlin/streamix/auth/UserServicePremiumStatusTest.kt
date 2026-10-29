package streamix.auth

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class UserServicePremiumStatusTest {
    @Test
    fun profileExposesPremiumStatusFromInjectedEntitlementSource() {
        val root = Files.createTempDirectory("shinigami-user-premium-status")
        val users = FileUserRepository(root.resolve("users.json"))
        val user = users.create(
            username = "premium-viewer",
            externalProvider = "test",
            externalSubject = "premium-viewer-subject"
        )
        val service = UserService(users) { userId ->
            assertEquals(user.id, userId)
            mapOf(
                "premium" to true,
                "premiumDaysRemaining" to 5,
                "premiumUntil" to "2026-10-26T00:00:00Z"
            )
        }

        val profile = service.me(user.id)

        assertEquals(true, profile.gamification.premium)
        assertEquals(5, profile.gamification.premiumDaysRemaining)
    }

    @Test
    fun profileDefaultsToNonPremiumWhenEntitlementSourceHasNoStatus() {
        val root = Files.createTempDirectory("shinigami-user-no-premium-status")
        val users = FileUserRepository(root.resolve("users.json"))
        val user = users.create(
            username = "regular-viewer",
            externalProvider = "test",
            externalSubject = "regular-viewer-subject"
        )

        val profile = UserService(users).me(user.id)

        assertEquals(false, profile.gamification.premium)
        assertEquals(0, profile.gamification.premiumDaysRemaining)
    }
}
