package streamix.api

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import streamix.catalog.CatalogService
import streamix.catalog.FileCatalogRepository
import streamix.core.StreamixExtractorRegistry
import streamix.runtime.StreamixService

class StreamixSocialActivityReportContractTest {
    @Test
    fun activityRoutesEnforceOwnershipAndSupportRepliesLikesAndSubscriptions() {
        val temp = Files.createTempDirectory("shinigami-social-activity-api")
        val auth = streamix.auth.AuthRuntime(temp.resolve("auth"))
        val owner = auth.auth.signIn(streamix.auth.ExternalIdentity("test", "activity-owner", displayName = "Activity Owner"))
        val other = auth.auth.signIn(streamix.auth.ExternalIdentity("test", "activity-other", displayName = "Other User"))
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
            val anonymous = send(client, base, "/api/v1/social/activities", "GET")
            assertEquals(401, anonymous.statusCode())

            val invalid = send(client, base, "/api/v1/social/activities", "POST", owner.token, """{"type":"watch","mediaId":0}""")
            assertEquals(400, invalid.statusCode())

            val missingType = send(client, base, "/api/v1/social/activities", "POST", owner.token, """{"text":"missing type"}""")
            assertEquals(400, missingType.statusCode())

            val created = send(client, base, "/api/v1/social/activities", "POST", owner.token,
                """{"type":"watch","text":"Finished an episode","mediaId":21,"mediaTitle":"One Piece"}""")
            assertEquals(201, created.statusCode())
            assertTrue(created.body().contains("\"mediaId\":21"))
            val activityId = Regex("\"id\":\"([^\"]+)\"").find(created.body())?.groupValues?.get(1)
                ?: error("created activity id missing")

            val feed = send(client, base, "/api/v1/social/feed?page=1&perPage=10", "GET", owner.token)
            assertEquals(200, feed.statusCode())
            assertTrue(feed.body().contains("Finished an episode"))
            assertTrue(feed.body().contains("\"page\":1"))
            assertTrue(feed.body().contains("\"perPage\":10"))
            assertTrue(feed.body().contains("\"hasNextPage\":false"))

            // Extremely large page numbers must safely return an empty page, not overflow the offset.
            val hugePage = send(client, base, "/api/v1/social/feed?page=2147483647&perPage=100", "GET", owner.token)
            assertEquals(200, hugePage.statusCode())
            assertTrue(hugePage.body().contains("\"items\":[]"))
            assertTrue(hugePage.body().contains("\"hasNextPage\":false"))

            val missingActivityReplies = send(client, base, "/api/v1/social/activities/missing-activity/replies", "GET", other.token)
            assertEquals(404, missingActivityReplies.statusCode())

            val missingActivityReply = send(client, base, "/api/v1/social/activities/missing-activity/replies", "POST", other.token,
                """{"text":"This must not be created"}""")
            assertEquals(404, missingActivityReply.statusCode())

            val reply = send(client, base, "/api/v1/social/activities/$activityId/replies", "POST", other.token,
                """{"text":"Nice!"}""")
            assertEquals(201, reply.statusCode())
            assertTrue(reply.body().contains("Nice!"))

            val like = send(client, base, "/api/v1/social/activities/$activityId/like", "POST", other.token, "{}")
            assertEquals(200, like.statusCode())
            assertTrue(like.body().contains("\"isLiked\":true"))

            val subscribe = send(client, base, "/api/v1/social/activities/$activityId/subscribe", "POST", other.token, "{}")
            assertEquals(200, subscribe.statusCode())
            assertTrue(subscribe.body().contains("\"isSubscribed\":true"))

            val forbiddenDelete = send(client, base, "/api/v1/social/activities/$activityId", "DELETE", other.token)
            assertEquals(404, forbiddenDelete.statusCode())

            val ownerDelete = send(client, base, "/api/v1/social/activities/$activityId", "DELETE", owner.token)
            assertEquals(200, ownerDelete.statusCode())
            assertTrue(ownerDelete.body().contains("\"deleted\":true"))

            val missing = send(client, base, "/api/v1/social/activities/$activityId", "GET", owner.token)
            assertEquals(404, missing.statusCode())
        } finally {
            server.close()
            Files.walk(temp).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    @Test
    fun reportRouteValidatesInputAndPersistsAuthenticatedReports() {
        val temp = Files.createTempDirectory("shinigami-social-report-api")
        val auth = streamix.auth.AuthRuntime(temp.resolve("auth"))
        val reporter = auth.auth.signIn(streamix.auth.ExternalIdentity("test", "reporter-user", displayName = "Reporter"))
        val target = auth.auth.signIn(streamix.auth.ExternalIdentity("test", "report-target", displayName = "Target"))
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
            val path = "/api/v1/social/reports"

            assertEquals(401, send(client, base, path, "POST", body = """{"type":"SPAM","description":"spam","targetContentId":"post-1"}""").statusCode())

            val unsupported = send(client, base, path, "POST", reporter.token,
                """{"type":"NOT_A_REPORT_TYPE","description":"spam","targetContentId":"post-1"}""")
            assertEquals(400, unsupported.statusCode())

            val missingDescription = send(client, base, path, "POST", reporter.token,
                """{"type":"SPAM","targetContentId":"post-1"}""")
            assertEquals(400, missingDescription.statusCode())

            val tooLongDescription = send(client, base, path, "POST", reporter.token,
                """{"type":"SPAM","description":"${"x".repeat(2001)}","targetContentId":"post-1"}""")
            assertEquals(400, tooLongDescription.statusCode())

            val oversizedBody = send(client, base, path, "POST", reporter.token, "x".repeat(64 * 1024 + 1))
            assertEquals(413, oversizedBody.statusCode())

            val missingTarget = send(client, base, path, "POST", reporter.token,
                """{"type":"SPAM","description":"spam"}""")
            assertEquals(400, missingTarget.statusCode())

            val missingUser = send(client, base, path, "POST", reporter.token,
                """{"type":"SPAM","description":"spam","targetUserId":"not-a-real-user"}""")
            assertEquals(404, missingUser.statusCode())

            // Optional target IDs containing only whitespace normalize to absent.
            // A valid content target must not be rejected as a nonexistent blank user.
            val contentOnlyWithBlankUser = send(client, base, path, "POST", reporter.token,
                """{"type":"SPAM","description":"Review this post","targetUserId":"   ","targetContentId":"  post-41  "}""")
            assertEquals(201, contentOnlyWithBlankUser.statusCode())
            assertTrue(contentOnlyWithBlankUser.body().contains("\"status\":\"PENDING\""))
            assertEquals(1L, auth.reports.count())

            val created = send(client, base, path, "POST", reporter.token,
                """{"type":"HARASSMENT","description":"Please review this content","targetUserId":"${target.user.id}","targetContentId":"post-42"}""")
            assertEquals(201, created.statusCode())
            assertTrue(created.body().contains("\"status\":\"PENDING\""))
            assertFalse(created.body().contains("Please review this content"))
            assertEquals(2L, auth.reports.count())
            assertEquals(1L, auth.reports.countForUser(target.user.id))
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
        token: String? = null,
        body: String? = null
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder()
            .uri(URI(base + path))
            .timeout(Duration.ofSeconds(3))
        if (token != null) builder.header("Authorization", "Bearer $token")
        if (body != null) builder.header("Content-Type", "application/json")
        when (method) {
            "GET" -> builder.GET()
            "POST" -> builder.POST(HttpRequest.BodyPublishers.ofString(body ?: "{}"))
            "DELETE" -> builder.DELETE()
            else -> error("unsupported test HTTP method: $method")
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }
}
