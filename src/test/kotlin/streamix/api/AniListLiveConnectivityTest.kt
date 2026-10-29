package streamix.api

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Opt-in upstream connectivity check. Deterministic HTTP contract tests remain
 * the regular CI gate; this probe confirms the public AniList GraphQL endpoint
 * is reachable from the CI runtime without requiring an AniList user token.
 */
class AniListLiveConnectivityTest {
    @Test
    fun publicAniListSearchAndDetailAreReachable() {
        val proxy = AniListProxy()
        val results = proxy.search("One Piece", 1)
        println("ANI_LIST_LIVE search query=One Piece count=${results.size} firstId=${results.firstOrNull()?.id}")
        assertTrue(results.any { it.id == "21" }, "AniList search did not return canonical One Piece id 21")

        val detail = proxy.detail(21L)
        println("ANI_LIST_LIVE detail id=21 title=${detail?.title}")
        assertNotNull(detail, "AniList detail query failed for canonical id 21")
        assertTrue(detail.title.contains("One Piece", ignoreCase = true), "AniList detail title mismatch")
    }
}
