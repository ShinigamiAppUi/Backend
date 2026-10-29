package streamix.api

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import streamix.catalog.CatalogService
import streamix.catalog.FileCatalogRepository
import streamix.core.StreamixExtractorRegistry
import streamix.runtime.StreamixService

class StreamixSocialCommentLifecycleContractTest {
    @Test
    fun commentMutationRequiresOwnershipAndDeletedCommentCannotBeFetchedOrVotedOn() {
        val temp = Files.createTempDirectory("shinigami-social-comment-lifecycle")
        val auth = streamix.auth.AuthRuntime(temp.resolve("auth"))
        val owner = auth.auth.signIn(streamix.auth.ExternalIdentity("test", "comment-lifecycle-owner", displayName = "Owner"))
        val voter = auth.auth.signIn(streamix.auth.ExternalIdentity("test", "comment-lifecycle-voter", displayName = "Voter"))
        val ownerToken = owner.token ?: error("owner token missing")
        val voterToken = voter.token ?: error("voter token missing")
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
            val missingContent = send(client, base, "/api/v1/social/comments", "POST", ownerToken,
                """{"mediaId":21}""")
            assertEquals(400, missingContent.statusCode())
            assertEquals(404, send(client, base, "/api/v1/social/comments/missing-comment/replies", "GET", ownerToken).statusCode())

            val created = send(client, base, "/api/v1/social/comments", "POST", ownerToken,
                """{"mediaId":21,"content":"comment to delete"}""")
            assertEquals(201, created.statusCode())
            val id = Regex("\"id\":\"([^\"]+)\"").find(created.body())?.groupValues?.get(1)
                ?: error("created comment id missing")

            // Comments are readable by other authenticated users, but only the author may edit/delete.
            assertEquals(200, send(client, base, "/api/v1/social/comments/$id", "GET", voterToken).statusCode())
            assertEquals(404, send(client, base, "/api/v1/social/comments/$id", "PUT", voterToken,
                """{"content":"unauthorized edit"}""").statusCode())
            assertEquals(404, send(client, base, "/api/v1/social/comments/$id", "DELETE", voterToken).statusCode())
            assertEquals(200, send(client, base, "/api/v1/social/comments/$id", "GET", ownerToken).statusCode())

            assertEquals(200, send(client, base, "/api/v1/social/comments/$id", "DELETE", ownerToken).statusCode())
            assertEquals(404, send(client, base, "/api/v1/social/comments/$id", "GET", voterToken).statusCode())
            assertEquals(404, send(client, base, "/api/v1/social/comments/$id/vote", "POST", voterToken, """{"vote":1}""").statusCode())
            assertEquals(404, send(client, base, "/api/v1/social/comments/$id/replies", "GET", voterToken).statusCode())
            val listed = send(client, base, "/api/v1/social/comments?mediaId=21", "GET", ownerToken)
            assertEquals(200, listed.statusCode())
            assertEquals(0, Regex("\"id\"").findAll(listed.body()).count())
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
        token: String,
        body: String? = null
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder().uri(URI(base + path)).timeout(Duration.ofSeconds(3))
            .header("Authorization", "Bearer $token")
        if (body != null) builder.header("Content-Type", "application/json")
        when (method) {
            "GET" -> builder.GET()
            "POST" -> builder.POST(HttpRequest.BodyPublishers.ofString(body ?: "{}"))
            "PUT" -> builder.PUT(HttpRequest.BodyPublishers.ofString(body ?: "{}"))
            "DELETE" -> builder.DELETE()
            else -> error("unsupported test HTTP method: $method")
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }
}
