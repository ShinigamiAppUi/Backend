package streamix.giveaway

import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileGiveawayServiceTest {
    @Test
    fun claimGrantsPremiumAndExtendsExistingEntitlement() {
        val root = Files.createTempDirectory("shinigami-giveaway")
        var now = Instant.parse("2026-10-21T00:00:00Z")
        val service = FileGiveawayService(root, clock = { now })
        val giveaway = service.create("creator", CreateGiveawayRequest(21, message = "Have fun!", daysPerGift = 3, totalGifts = 10))

        val first = service.claim(giveaway.id, "viewer")
        assertEquals("ACTIVE", first.giveaway.status)
        assertTrue(service.premiumStatus("viewer")["premium"] as Boolean)

        now = now.plusSeconds(60)
        val second = service.create("another-creator", CreateGiveawayRequest(21, message = "Another gift", daysPerGift = 1, totalGifts = 10))
        val extended = service.claim(second.id, "viewer")
        assertTrue(extended.premiumDaysRemaining >= 3)
        assertTrue(Instant.parse(extended.premiumUntil).isAfter(Instant.parse(first.premiumUntil)))
    }

    @Test
    fun rejectsCreatorDuplicateAndExhaustedClaims() {
        val root = Files.createTempDirectory("shinigami-giveaway-rules")
        val service = FileGiveawayService(root)
        val giveaway = service.create("creator", CreateGiveawayRequest(21, message = "One gift", daysPerGift = 1, totalGifts = 10))

        assertFailsWith<IllegalArgumentException> { service.claim(giveaway.id, "creator") }
        assertEquals("ACTIVE", service.claim(giveaway.id, "viewer").giveaway.status)
        assertFailsWith<IllegalArgumentException> { service.claim(giveaway.id, "viewer") }
        var finalClaim = service.claim(giveaway.id, "second-viewer")
        for (index in 3..10) {
            finalClaim = service.claim(giveaway.id, "viewer-$index")
        }
        assertEquals("EXHAUSTED", finalClaim.giveaway.status)
        assertFailsWith<IllegalArgumentException> { service.claim(giveaway.id, "eleventh-viewer") }
    }

    @Test
    fun persistsGiveawayAndPremiumAcrossServiceInstances() {
        val root = Files.createTempDirectory("shinigami-giveaway-persistence")
        val service = FileGiveawayService(root)
        val giveaway = service.create("creator", CreateGiveawayRequest(21, message = "Persist me", daysPerGift = 7, totalGifts = 10))
        service.claim(giveaway.id, "viewer")

        val restarted = FileGiveawayService(root)
        assertEquals("ACTIVE", restarted.list(21).single().status)
        assertTrue(restarted.premiumStatus("viewer")["premium"] as Boolean)
    }

    @Test
    fun giveawayNeverExpiresAndRemainsClaimableUntilAllGiftsAreClaimed() {
        val root = Files.createTempDirectory("shinigami-giveaway-no-expiry")
        var now = Instant.parse("2026-10-21T00:00:00Z")
        val service = FileGiveawayService(root, clock = { now })
        val giveaway = service.create("creator", CreateGiveawayRequest(21, message = "Claim any time", daysPerGift = 1, totalGifts = 10))

        assertEquals(null, giveaway.expiresAt)
        now = now.plusSeconds(365L * 24 * 60 * 60)
        assertEquals("ACTIVE", service.list(21).single().status)
        assertEquals("ACTIVE", service.claim(giveaway.id, "viewer").giveaway.status)
    }

    @Test
    fun giveawayClaimsAreCappedAtSevenTotalPremiumDays() {
        val root = Files.createTempDirectory("shinigami-giveaway-seven-day-cap")
        val now = Instant.parse("2026-10-21T00:00:00Z")
        val service = FileGiveawayService(root, clock = { now })
        val threeDays = service.create("creator-a", CreateGiveawayRequest(21, message = "Three days", daysPerGift = 3, totalGifts = 10))
        val anotherThreeDays = service.create("creator-b", CreateGiveawayRequest(21, message = "Three more", daysPerGift = 3, totalGifts = 10))
        val oneDay = service.create("creator-c", CreateGiveawayRequest(21, message = "One day", daysPerGift = 1, totalGifts = 10))
        val extraDay = service.create("creator-d", CreateGiveawayRequest(21, message = "Too much", daysPerGift = 1, totalGifts = 10))

        assertEquals(3, service.claim(threeDays.id, "viewer").premiumDaysRemaining)
        assertEquals(6, service.claim(anotherThreeDays.id, "viewer").premiumDaysRemaining)
        assertEquals(7, service.claim(oneDay.id, "viewer").premiumDaysRemaining)
        assertFailsWith<IllegalArgumentException> { service.claim(extraDay.id, "viewer") }
        assertEquals(7, service.premiumStatus("viewer")["premiumDaysRemaining"])
    }

    @Test
    fun giveawayClaimCannotPushPremiumBeyondSevenDays() {
        val root = Files.createTempDirectory("shinigami-giveaway-cap-overflow")
        val now = Instant.parse("2026-10-21T00:00:00Z")
        val service = FileGiveawayService(root, clock = { now })
        val sevenDays = service.create("creator-a", CreateGiveawayRequest(21, message = "Seven days", daysPerGift = 7, totalGifts = 10))
        val threeDays = service.create("creator-b", CreateGiveawayRequest(21, message = "Three days", daysPerGift = 3, totalGifts = 10))

        assertEquals(7, service.claim(sevenDays.id, "viewer").premiumDaysRemaining)
        assertFailsWith<IllegalArgumentException> { service.claim(threeDays.id, "viewer") }
        assertEquals(7, service.premiumStatus("viewer")["premiumDaysRemaining"])
    }


    @Test
    fun rejectedThirtyDayClaimDoesNotConsumeGiftOrGrantPartialPremium() {
        val root = Files.createTempDirectory("shinigami-giveaway-thirty-day-conflict")
        val service = FileGiveawayService(root, clock = { Instant.parse("2026-10-21T00:00:00Z") })
        val giveaway = service.create(
            "creator",
            CreateGiveawayRequest(21, message = "Thirty-day gift", daysPerGift = 30, totalGifts = 10)
        )

        // The current product rules allow creating 30-day gifts but cap claims at seven days.
        // Until that rule conflict is resolved, a rejected claim must never consume a gift
        // or partially grant Premium.
        assertFailsWith<IllegalArgumentException> { service.claim(giveaway.id, "viewer") }
        val unchanged = service.list(21).single()
        assertEquals(0, unchanged.claimedGifts)
        assertEquals(10, unchanged.remainingGifts)
        assertEquals(false, service.premiumStatus("viewer")["premium"])
        assertEquals(0, service.premiumStatus("viewer")["premiumDaysRemaining"])
    }

    @Test
    fun validatesDurationGiftCountAndMessage() {
        val service = FileGiveawayService(Files.createTempDirectory("shinigami-giveaway-validation"))
        assertFailsWith<IllegalArgumentException> {
            service.create("creator", CreateGiveawayRequest(21, message = "bad duration", daysPerGift = 2, totalGifts = 10))
        }
        assertFailsWith<IllegalArgumentException> {
            service.create("creator", CreateGiveawayRequest(21, message = "too few", daysPerGift = 1, totalGifts = 9))
        }
        assertFailsWith<IllegalArgumentException> {
            service.create("creator", CreateGiveawayRequest(21, message = "too many", daysPerGift = 1, totalGifts = 101))
        }
        assertFailsWith<IllegalArgumentException> {
            service.create("creator", CreateGiveawayRequest(21, message = " ", daysPerGift = 1, totalGifts = 10))
        }
    }
}