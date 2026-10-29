package streamix.catalog

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CatalogCookieJarTest {
    private val root = "https://example.com/".toHttpUrl()
    private val subdomain = "https://media.example.com/".toHttpUrl()

    @Test
    fun refreshedDomainCookieReplacesExistingCookieAcrossSubdomains() {
        val jar = CatalogCookieJar()
        val old = Cookie.parse(root, "session=old; Domain=example.com; Path=/")!!
        val fresh = Cookie.parse(subdomain, "session=new; Domain=example.com; Path=/")!!

        jar.saveFromResponse(root, listOf(old))
        jar.saveFromResponse(subdomain, listOf(fresh))

        assertEquals(listOf("new"), jar.loadForRequest(root).map { it.value })
        assertEquals(listOf("new"), jar.loadForRequest(subdomain).map { it.value })
    }

    @Test
    fun sameNameCookiesWithDifferentPathsRemainDistinct() {
        val jar = CatalogCookieJar()
        val rootCookie = Cookie.parse(root, "session=root; Domain=example.com; Path=/")!!
        val mediaCookie = Cookie.parse(root, "session=media; Domain=example.com; Path=/media")!!

        jar.saveFromResponse(root, listOf(rootCookie, mediaCookie))

        assertEquals("root", jar.loadForRequest(root).single().value)
        assertEquals(setOf("root", "media"), jar.loadForRequest("https://example.com/media/episode".toHttpUrl()).map { it.value }.toSet())
    }

    @Test
    fun expiredCookiesAreNotSent() {
        val jar = CatalogCookieJar()
        val expired = Cookie.parse(root, "session=expired; Max-Age=0; Path=/")!!

        jar.saveFromResponse(root, listOf(expired))

        assertTrue(jar.loadForRequest(root).isEmpty())
    }
}
