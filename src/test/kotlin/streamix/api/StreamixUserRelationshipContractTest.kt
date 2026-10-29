package streamix.api

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import streamix.catalog.CatalogService
import streamix.catalog.FileCatalogRepository
import streamix.core.StreamixExtractorRegistry
import streamix.runtime.StreamixService

class StreamixUserRelationshipContractTest {
    @Test
    fun relationshipRoutesValidateTargetsAndExposeViewerRelativeProfileState() {
        val temp = Files.createTempDirectory("shinigami-user-relationship-api")
        val auth = streamix.auth.AuthRuntime(temp.resolve("auth"))
        val alice = auth.auth.signIn(streamix.auth.ExternalIdentity("test", "relationship-alice", displayName = "Alice"))
        val bob = auth.auth.signIn(streamix.auth.ExternalIdentity("test", "relationship-bob", displayName = "Bob"))
        val catalog = CatalogService(
            repository = FileCatalogRepository(temp.resolve("catalog/episodes.json")),
            extractors = StreamixExtractorRegistry(emptyList())
        )
        val server = StreamixHttpServer(
            service = StreamixService(catalog = catalog),
            authRuntime = auth,
            catalog = catalog,
            host = "127.0.0.1",
            port = 0
        )
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        try {
            server.start()
            val base = "http://127.0.0.1:${server.boundPort()}"

            assertEquals(401, send(client, base, "/api/v1/users/${bob.user.id}/follow?enabled=true", "POST").statusCode())
            assertEquals(400, send(client, base, "/api/v1/users/${bob.user.id}/follow?enabled=maybe", "POST", alice.token).statusCode())
            assertEquals(400, send(client, base, "/api/v1/users/${alice.user.id}/follow?enabled=true", "POST", alice.token).statusCode())
            assertEquals(404, send(client, base, "/api/v1/users/missing-user/follow?enabled=true", "POST", alice.token).statusCode())
            assertEquals(404, send(client, base, "/api/v1/users/missing-user/block?enabled=true", "POST", alice.token).statusCode())

            val follow = send(client, base, "/api/v1/users/${bob.user.id}/follow?enabled=true", "POST", alice.token)
            assertEquals(200, follow.statusCode())

            // A full final page must not claim that another page exists.
            val finalFollowersPage = send(client, base, "/api/v1/users/${bob.user.id}/followers?page=1&perPage=1", "GET", bob.token)
            assertEquals(200, finalFollowersPage.statusCode())
            assertTrue(finalFollowersPage.body().contains("\"hasNextPage\":false"))
            val bobViewedByAlice = send(client, base, "/api/v1/users/${bob.user.id}", "GET", alice.token)
            assertEquals(200, bobViewedByAlice.statusCode())
            assertTrue(bobViewedByAlice.body().contains("\"isFollowing\":true"))
            assertTrue(bobViewedByAlice.body().contains("\"isFollower\":false"))

            val aliceViewedByBob = send(client, base, "/api/v1/users/${alice.user.id}", "GET", bob.token)
            assertEquals(200, aliceViewedByBob.statusCode())
            assertTrue(aliceViewedByBob.body().contains("\"isFollowing\":false"))
            assertTrue(aliceViewedByBob.body().contains("\"isFollower\":true"))

            val block = send(client, base, "/api/v1/users/${bob.user.id}/block?enabled=true", "POST", alice.token)
            assertEquals(200, block.statusCode())
            val blockedProfile = send(client, base, "/api/v1/users/${bob.user.id}", "GET", alice.token)
            assertEquals(200, blockedProfile.statusCode())
            assertTrue(blockedProfile.body().contains("\"isBlocked\":true"))

            val unblock = send(client, base, "/api/v1/users/${bob.user.id}/block?enabled=false", "POST", alice.token)
            assertEquals(200, unblock.statusCode())
            val unblockedProfile = send(client, base, "/api/v1/users/${bob.user.id}", "GET", alice.token)
            assertEquals(200, unblockedProfile.statusCode())
            assertTrue(unblockedProfile.body().contains("\"isBlocked\":false"))
        } finally {
            server.close()
            Files.walk(temp).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    private fun send(
        client: HttpClient,
        base: String,
        path: String,
        method: String,
        token: String? = null
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder().uri(URI(base + path)).timeout(Duration.ofSeconds(3))
        if (token != null) builder.header("Authorization", "Bearer $token")
        when (method) {
            "GET" -> builder.GET()
            "POST" -> builder.POST(HttpRequest.BodyPublishers.ofString("{}"))
            else -> error("unsupported test HTTP method: $method")
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }
}
