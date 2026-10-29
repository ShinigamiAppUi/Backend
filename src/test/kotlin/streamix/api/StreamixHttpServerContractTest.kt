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
import streamix.api.AdminRole
import streamix.catalog.CatalogService
import streamix.catalog.EpisodeSource
import streamix.catalog.FileCatalogRepository
import streamix.catalog.SourceType
import streamix.core.StreamixExtractorRegistry
import streamix.runtime.StreamixService

class StreamixHttpServerContractTest {
    @Test
    fun publicAnimeRoutesUseCatalogAndInjectedAniListGateway() {
        val temp = Files.createTempDirectory("shinigami-http-contract")
        val registry = StreamixExtractorRegistry(emptyList())
        val catalog = CatalogService(
            repository = FileCatalogRepository(temp.resolve("catalog/episodes.json")),
            extractors = registry
        )
        catalog.upsertSource(
            EpisodeSource(
                id = "",
                anilistId = 21L,
                episodeNumber = 1,
                sourceUrl = "https://example.com/one-piece-720.m3u8",
                type = SourceType.DIRECT,
                quality = 720,
                language = "id",
                subtitleLanguage = "en",
                headers = mapOf("X-Catalog-Test" to "preserved"),
                referer = "https://catalog.example/watch"
            )
        )
        val aniList = FakeAniListGateway()
        val service = StreamixService(
            aniList = aniList,
            catalog = catalog,
            animeinApiProvider = streamix.runtime.AnimeinStreamProvider { _, episode ->
                if (episode == 2) listOf(
                    streamix.api.StreamRef(
                        providerId = "animein-api",
                        url = "https://cdn.animein.example/episode-2.m3u8",
                        quality = 1080,
                        type = "m3u8",
                        language = "id",
                        subtitleLanguage = "en",
                        headers = mapOf("X-Stream-Test" to "preserved"),
                        referer = "https://api.animein.example/",
                        subtitles = listOf(
                            streamix.api.SubtitleRef(
                                url = "https://cdn.animein.example/episode-2.en.vtt",
                                language = "en",
                                headers = mapOf("X-Subtitle-Test" to "preserved"),
                                referer = "https://api.animein.example/"
                            )
                        )
                    )
                ) else emptyList()
            }
        )
        val server = StreamixHttpServer(
            service = service,
            extractorRegistry = registry,
            catalog = catalog,
            host = "127.0.0.1",
            port = 0,
            aniListProxy = aniList
        )
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

        try {
            server.start()
            val base = "http://127.0.0.1:${server.boundPort()}"

            val health = get(client, "$base/api/v1/health")
            assertEquals(200, health.statusCode())
            assertTrue(health.body().contains("\"status\":\"ok\""))

            val unsupportedHealthMethod = client.send(
                HttpRequest.newBuilder().uri(URI("$base/api/v1/health"))
                    .method("PATCH", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(405, unsupportedHealthMethod.statusCode())
            assertEquals("GET", unsupportedHealthMethod.headers().firstValue("Allow").orElse(""))

            val missingQuery = get(client, "$base/api/v1/anime/search")
            assertEquals(400, missingQuery.statusCode())
            assertTrue(missingQuery.body().contains("q is required"))

            val search = get(client, "$base/api/v1/anime/search?q=One%20Piece&page=2")
            assertEquals(200, search.statusCode())
            assertTrue(search.body().contains("\"title\":\"One Piece\""))
            assertEquals(2, aniList.lastSearchPage)

            aniList.searchFailure = "sensitive AniList search outage detail"
            val failedSearch = get(client, "$base/api/v1/anime/search?q=Failure")
            assertEquals(500, failedSearch.statusCode())
            assertTrue(failedSearch.body().contains("internal server error"))
            assertFalse(failedSearch.body().contains("sensitive AniList search outage detail"))
            aniList.searchFailure = null

            aniList.searchUpstreamStatus = 429
            val rateLimitedSearch = get(client, "$base/api/v1/anime/search?q=One%20Piece")
            assertEquals(502, rateLimitedSearch.statusCode())
            assertTrue(rateLimitedSearch.body().contains("AniList request failed"))
            aniList.searchUpstreamStatus = null

            val detail = get(client, "$base/api/v1/anime/21")
            assertEquals(200, detail.statusCode())
            assertTrue(detail.body().contains("\"id\":\"21\""))

            aniList.detailFailure = "sensitive AniList detail outage detail"
            val failedDetail = get(client, "$base/api/v1/anime/21")
            assertEquals(500, failedDetail.statusCode())
            assertTrue(failedDetail.body().contains("internal server error"))
            assertFalse(failedDetail.body().contains("sensitive AniList detail outage detail"))
            aniList.detailFailure = null

            val episodes = get(client, "$base/api/v1/anime/21/episodes")
            assertEquals(200, episodes.statusCode())
            assertTrue(episodes.body().contains("\"number\":1"))

            val streams = get(client, "$base/api/v1/anime/21/episode/1/streams")
            assertEquals(200, streams.statusCode())
            assertTrue(streams.body().contains("https://example.com/one-piece-720.m3u8"))
            assertTrue(streams.body().contains("\"quality\":720"))
            assertTrue(streams.body().contains("\"language\":\"id\""))
            assertTrue(streams.body().contains("\"subtitleLanguage\":\"en\""))
            assertTrue(streams.body().contains("\"X-Catalog-Test\":\"preserved\""))
            assertTrue(streams.body().contains("\"referer\":\"https://catalog.example/watch\""))

            val animeinStreams = get(client, "$base/api/v1/anime/21/episode/2/streams")
            assertEquals(200, animeinStreams.statusCode())
            assertTrue(animeinStreams.body().contains("https://cdn.animein.example/episode-2.m3u8"))
            assertTrue(animeinStreams.body().contains("\"quality\":1080"))
            assertTrue(animeinStreams.body().contains("\"X-Stream-Test\":\"preserved\""))
            assertTrue(animeinStreams.body().contains("\"referer\":\"https://api.animein.example/\""))
            assertTrue(animeinStreams.body().contains("https://cdn.animein.example/episode-2.en.vtt"))
            assertTrue(animeinStreams.body().contains("\"X-Subtitle-Test\":\"preserved\""))

            val sourceId = catalog.episodes(21L).single().sources.single().id
            val failedSource = get(client, "$base/api/v1/anime/21/episode/1/streams?failedSourceIds=$sourceId")
            assertEquals(200, failedSource.statusCode())
            assertEquals("[]", failedSource.body())

            // Android forwards StreamRef.providerId (catalog:<source-id>), not
            // the bare Catalog repository ID. Both forms must skip the source.
            val failedSourceByProviderId = get(
                client,
                "$base/api/v1/anime/21/episode/1/streams?failedSourceIds=catalog%3A$sourceId"
            )
            assertEquals(200, failedSourceByProviderId.statusCode())
            assertEquals("[]", failedSourceByProviderId.body())

            val missingDetail = get(client, "$base/api/v1/anime/999")
            assertEquals(200, missingDetail.statusCode())
            assertEquals("[]", missingDetail.body())
            assertTrue(aniList.detailCalls.get() >= 1)
        } finally {
            server.close()
            Files.walk(temp).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun authenticatedChatAndNotificationRoutesAreWired() {
        val temp = Files.createTempDirectory("shinigami-chat-notification-api")
        val auth = streamix.auth.AuthRuntime(temp.resolve("auth"))
        val sender = auth.auth.signIn(streamix.auth.ExternalIdentity("test", "chat-sender", displayName = "Sender"))
        val recipient = auth.auth.signIn(streamix.auth.ExternalIdentity("test", "chat-recipient", displayName = "Recipient"))
        val registry = StreamixExtractorRegistry(emptyList())
        val catalog = CatalogService(repository = FileCatalogRepository(temp.resolve("catalog/episodes.json")), extractors = registry)
        val server = StreamixHttpServer(service = StreamixService(catalog = catalog), authRuntime = auth, catalog = catalog, host = "127.0.0.1", port = 0)
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        try {
            server.start()
            val base = "http://127.0.0.1:${server.boundPort()}"
            val oversizedBody = """{"content":"${"x".repeat(65_536)}"}"""
            // Isolate the rejected oversized request so the main client cannot reuse its connection.
            val oversizedClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
            val oversized = oversizedClient.send(
                HttpRequest.newBuilder().uri(URI("$base/api/v1/chat/global/messages"))
                    .header("Authorization", "Bearer ${sender.token}")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(oversizedBody)).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(413, oversized.statusCode())

            val send = HttpRequest.newBuilder().uri(URI("$base/api/v1/chat/global/messages"))
                .header("Authorization", "Bearer ${sender.token}").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"content\":\"hello room\"}")).build()
            val sent = client.send(send, HttpResponse.BodyHandlers.ofString())
            assertEquals(201, sent.statusCode())
            assertTrue(sent.body().contains("hello room"))

            val communityPath = ChatApiContract.COMMUNITY_MESSAGES.replace("{mediaId}", "21")
            val invalidCommunityId = client.send(HttpRequest.newBuilder()
                .uri(URI("$base/api/v1/community/0/messages"))
                .header("Authorization", "Bearer ${sender.token}").GET().build(),
                HttpResponse.BodyHandlers.ofString())
            assertEquals(400, invalidCommunityId.statusCode(), invalidCommunityId.body())

            val unauthenticatedCommunity = client.send(HttpRequest.newBuilder()
                .uri(URI("$base$communityPath")).GET().build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(401, unauthenticatedCommunity.statusCode(), unauthenticatedCommunity.body())

            val unsupportedCommunityMethod = client.send(HttpRequest.newBuilder()
                .uri(URI("$base$communityPath"))
                .header("Authorization", "Bearer ${sender.token}")
                .method("DELETE", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString())
            assertEquals(405, unsupportedCommunityMethod.statusCode(), unsupportedCommunityMethod.body())
            val communitySend = client.send(HttpRequest.newBuilder().uri(URI("$base$communityPath"))
                .header("Authorization", "Bearer ${sender.token}").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"content\":\"hello community\"}")).build(),
                HttpResponse.BodyHandlers.ofString())
            assertEquals(201, communitySend.statusCode(), communitySend.body())
            assertTrue(communitySend.body().contains("hello community"))
            val communityMessages = client.send(HttpRequest.newBuilder().uri(URI("$base$communityPath?page=1&perPage=30"))
                .header("Authorization", "Bearer ${recipient.token}").GET().build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, communityMessages.statusCode(), communityMessages.body())
            assertTrue(communityMessages.body().contains("hello community"))
            // Existing installations may still use the former anime-chat URL.
            val legacyCommunityMessages = client.send(HttpRequest.newBuilder()
                .uri(URI("$base/api/v1/chat/anime/21/messages?page=1&perPage=30"))
                .header("Authorization", "Bearer ${recipient.token}").GET().build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, legacyCommunityMessages.statusCode(), legacyCommunityMessages.body())
            assertTrue(legacyCommunityMessages.body().contains("hello community"))

            val direct = HttpRequest.newBuilder().uri(URI("$base/api/v1/messages/${recipient.user.id}"))
                .header("Authorization", "Bearer ${sender.token}").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"content\":\"hello dm\"}")).build()
            val directSent = client.send(direct, HttpResponse.BodyHandlers.ofString())
            assertEquals(201, directSent.statusCode())
            assertTrue(directSent.body().contains("hello dm"))

            val unsupportedDirectMessageMethod = client.send(
                HttpRequest.newBuilder().uri(URI("$base/api/v1/messages/${recipient.user.id}"))
                    .header("Authorization", "Bearer ${sender.token}")
                    .method("DELETE", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(405, unsupportedDirectMessageMethod.statusCode())
            assertEquals("GET, POST", unsupportedDirectMessageMethod.headers().firstValue("Allow").orElse(""))

            val unsupportedActivityMethod = client.send(
                HttpRequest.newBuilder().uri(URI("$base${SocialApiContract.ACTIVITIES}"))
                    .header("Authorization", "Bearer ${sender.token}")
                    .method("DELETE", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(405, unsupportedActivityMethod.statusCode())
            assertEquals("GET, POST", unsupportedActivityMethod.headers().firstValue("Allow").orElse(""))

            val unsupportedCommentsMethod = client.send(
                HttpRequest.newBuilder().uri(URI("$base${SocialApiContract.COMMENTS}"))
                    .header("Authorization", "Bearer ${sender.token}")
                    .method("PUT", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(405, unsupportedCommentsMethod.statusCode())
            assertEquals("GET, POST", unsupportedCommentsMethod.headers().firstValue("Allow").orElse(""))

            // Android's ShinigamiChatClient calls the chat unread-count route.
            val chatUnread = client.send(HttpRequest.newBuilder().uri(URI("$base/api/v1/messages/unread-count"))
                .header("Authorization", "Bearer ${recipient.token}").GET().build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, chatUnread.statusCode())
            assertTrue(chatUnread.body().contains("\"count\":1"))
            val unsupportedChatUnreadMethod = client.send(HttpRequest.newBuilder().uri(URI("$base/api/v1/messages/unread-count"))
                .header("Authorization", "Bearer ${recipient.token}")
                .POST(HttpRequest.BodyPublishers.ofString("{\"content\":\"must not become a direct message\"}")).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(405, unsupportedChatUnreadMethod.statusCode())

            val unread = client.send(HttpRequest.newBuilder().uri(URI("$base/api/v1/notifications/unread-count"))
                .header("Authorization", "Bearer ${recipient.token}").GET().build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, unread.statusCode())
            assertTrue(unread.body().contains("\"count\":1"))

            val recipientNotifications = client.send(HttpRequest.newBuilder().uri(URI("$base/api/v1/notifications?page=1&perPage=10"))
                .header("Authorization", "Bearer ${recipient.token}").GET().build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, recipientNotifications.statusCode(), recipientNotifications.body())
            val notificationItems = com.google.gson.JsonParser.parseString(recipientNotifications.body()).asJsonObject
                .getAsJsonArray("items")
            assertEquals(1, notificationItems.size())
            val notificationId = notificationItems[0].asJsonObject.get("id").asString

            val senderNotifications = client.send(HttpRequest.newBuilder().uri(URI("$base/api/v1/notifications?page=1&perPage=10"))
                .header("Authorization", "Bearer ${sender.token}").GET().build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, senderNotifications.statusCode(), senderNotifications.body())
            assertEquals(0, com.google.gson.JsonParser.parseString(senderNotifications.body()).asJsonObject
                .getAsJsonArray("items").size(), "A user's notification list must not expose another user's notifications")

            val crossUserRead = client.send(HttpRequest.newBuilder().uri(URI("$base${NotificationApiContract.READ.replace("{notificationId}", notificationId)}"))
                .header("Authorization", "Bearer ${sender.token}").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString())
            assertEquals(404, crossUserRead.statusCode(), crossUserRead.body())
            val unreadAfterCrossUserRead = client.send(HttpRequest.newBuilder().uri(URI("$base/api/v1/notifications/unread-count"))
                .header("Authorization", "Bearer ${recipient.token}").GET().build(), HttpResponse.BodyHandlers.ofString())
            assertTrue(unreadAfterCrossUserRead.body().contains("\"count\":1"),
                "Cross-user read attempts must not mutate the recipient's notification")

            val markOneNotificationRead = client.send(HttpRequest.newBuilder().uri(URI("$base/api/v1/notifications/$notificationId/read"))
                .header("Authorization", "Bearer ${recipient.token}").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString())
            assertEquals(200, markOneNotificationRead.statusCode(), markOneNotificationRead.body())
            val unreadAfterSingleRead = client.send(HttpRequest.newBuilder().uri(URI("$base/api/v1/notifications/unread-count"))
                .header("Authorization", "Bearer ${recipient.token}").GET().build(), HttpResponse.BodyHandlers.ofString())
            assertTrue(unreadAfterSingleRead.body().contains("\"count\":0"))

            // Leave a second notification unread so read-all verifies its mutation count too.
            auth.notificationService.create(
                userId = recipient.user.id,
                type = NotificationType.MESSAGE,
                title = "Second notification",
                body = "Read-all regression"
            )

            val markThreadReadPath = ChatApiContract.MESSAGE_READ.replace("{userId}", sender.user.id)
            val markThreadRead = client.send(HttpRequest.newBuilder().uri(URI("$base$markThreadReadPath"))
                .header("Authorization", "Bearer ${recipient.token}").POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, markThreadRead.statusCode())
            assertTrue(markThreadRead.body().contains("\"marked\":1"))
            val chatUnreadAfterRead = client.send(HttpRequest.newBuilder().uri(URI("$base/api/v1/messages/unread-count"))
                .header("Authorization", "Bearer ${recipient.token}").GET().build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, chatUnreadAfterRead.statusCode())
            assertTrue(chatUnreadAfterRead.body().contains("\"count\":0"))

            val markNotificationsRead = client.send(HttpRequest.newBuilder().uri(URI("$base/api/v1/notifications/read-all"))
                .header("Authorization", "Bearer ${recipient.token}").POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, markNotificationsRead.statusCode(), markNotificationsRead.body())
            assertTrue(markNotificationsRead.body().contains("\"marked\":1"))
            val unreadAfterReadAll = client.send(HttpRequest.newBuilder().uri(URI("$base/api/v1/notifications/unread-count"))
                .header("Authorization", "Bearer ${recipient.token}").GET().build(), HttpResponse.BodyHandlers.ofString())
            assertTrue(unreadAfterReadAll.body().contains("\"count\":0"))

            val follow = client.send(HttpRequest.newBuilder().uri(URI("$base/api/v1/users/${recipient.user.id}/follow?enabled=true"))
                .header("Authorization", "Bearer ${sender.token}").POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, follow.statusCode())

            val followers = client.send(HttpRequest.newBuilder().uri(URI("$base/api/v1/users/${recipient.user.id}/followers"))
                .header("Authorization", "Bearer ${recipient.token}").GET().build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, followers.statusCode())
            assertTrue(followers.body().contains("\"username\":\"${sender.user.username}\""))

            val following = client.send(HttpRequest.newBuilder().uri(URI("$base/api/v1/users/${sender.user.id}/following?page=1&perPage=20"))
                .header("Authorization", "Bearer ${sender.token}").GET().build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, following.statusCode())
            assertTrue(following.body().contains("\"username\":\"${recipient.user.username}\""))

            val anonymous = client.send(HttpRequest.newBuilder().uri(URI("$base/api/v1/chat/global/messages")).GET().build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(401, anonymous.statusCode())
        } finally {
            server.close()
            Files.walk(temp).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    @Test
    fun graphqlRouteForwardsAuthorizationAndReturnsUpstreamStatus() {
        val temp = Files.createTempDirectory("shinigami-graphql-contract")
        val registry = StreamixExtractorRegistry(emptyList())
        val catalog = CatalogService(
            repository = FileCatalogRepository(temp.resolve("catalog/episodes.json")),
            extractors = registry
        )
        val aniList = FakeAniListGateway()
        val server = StreamixHttpServer(
            service = StreamixService(aniList = aniList, catalog = catalog),
            extractorRegistry = registry,
            host = "127.0.0.1",
            port = 0,
            aniListProxy = aniList
        )
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

        try {
            server.start()
            val request = HttpRequest.newBuilder()
                .uri(URI("http://127.0.0.1:${server.boundPort()}/api/v1/anilist/graphql"))
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer fixture-token")
                .POST(HttpRequest.BodyPublishers.ofString("{\"query\":\"{ Media(id: 21) { id } }\"}"))
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())

            assertEquals(200, response.statusCode())
            assertTrue(response.body().contains("\"data\""))
            assertEquals("Bearer fixture-token", aniList.lastAuthorization)

            aniList.executeFailure = "sensitive upstream fixture detail"
            val failed = client.send(request, HttpResponse.BodyHandlers.ofString())
            assertEquals(502, failed.statusCode())
            assertTrue(failed.body().contains("AniList GraphQL request failed"))
            assertFalse(failed.body().contains("sensitive upstream fixture detail"))
        } finally {
            server.close()
            Files.walk(temp).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }


    @Test
    fun authenticatedRoutesReturnInternalServerErrorWhenPersistedUserStoreIsMalformed() {
        val temp = Files.createTempDirectory("shinigami-auth-malformed-user-store")
        val registry = StreamixExtractorRegistry(emptyList())
        val catalog = CatalogService(repository = FileCatalogRepository(temp.resolve("catalog/episodes.json")), extractors = registry)
        val auth = streamix.auth.AuthRuntime(temp.resolve("auth"))
        val session = auth.auth.signIn(streamix.auth.ExternalIdentity("test", "malformed-user-store", displayName = "Malformed User Store"))
        Files.writeString(temp.resolve("auth/users.json"), "{")
        val server = StreamixHttpServer(service = StreamixService(catalog = catalog), authRuntime = auth, catalog = catalog, host = "127.0.0.1", port = 0)
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        try {
            server.start()
            val response = client.send(
                HttpRequest.newBuilder().uri(URI("http://127.0.0.1:${server.boundPort()}/api/v1/users/me"))
                    .header("Authorization", "Bearer ${session.token}").GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(500, response.statusCode())
            assertTrue(response.body().contains("internal server error"))
        } finally {
            server.close()
            Files.walk(temp).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    @Test
    fun authenticatedProfileAndLibraryRoutesWorkAndRejectAnonymousRequests() {
        val temp = Files.createTempDirectory("shinigami-user-api-contract")
        val registry = StreamixExtractorRegistry(emptyList())
        val catalog = CatalogService(repository = FileCatalogRepository(temp.resolve("catalog/episodes.json")), extractors = registry)
        val auth = streamix.auth.AuthRuntime(temp.resolve("auth"))
        val session = auth.auth.signIn(streamix.auth.ExternalIdentity("test", "profile-library-user", displayName = "Contract User"))
        val server = StreamixHttpServer(service = StreamixService(catalog = catalog), authRuntime = auth, catalog = catalog, host = "127.0.0.1", port = 0)
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        try {
            server.start()
            val base = "http://127.0.0.1:${server.boundPort()}"
            val libraryItemPath = UserLibraryApiContract.LIST.replace("{mediaId}", "21")
            val libraryStatusPath = UserLibraryApiContract.STATUS.replace("{mediaId}", "21")
            val libraryProgressPath = UserLibraryApiContract.PROGRESS.replace("{mediaId}", "21")
            val libraryScorePath = UserLibraryApiContract.SCORE.replace("{mediaId}", "21")
            val me = client.send(HttpRequest.newBuilder().uri(URI("$base/api/v1/users/me")).header("Authorization", "Bearer ${session.token}").GET().build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, me.statusCode())
            assertTrue(me.body().contains("\"user\""))
            val write = client.send(HttpRequest.newBuilder().uri(URI("$base$libraryItemPath"))
                .header("Authorization", "Bearer ${session.token}").header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"status\":\"WATCHING\",\"progress\":3,\"score\":8.5}")).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, write.statusCode())
            assertTrue(write.body().contains("\"mediaId\":21"))
            assertTrue(write.body().contains("\"progress\":3"))
            val statusUpdate = client.send(HttpRequest.newBuilder().uri(URI("$base$libraryStatusPath"))
                .header("Authorization", "Bearer ${session.token}").header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"status\":\"COMPLETED\"}")).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, statusUpdate.statusCode())
            assertTrue(statusUpdate.body().contains("\"status\":\"COMPLETED\""))
            val scoreUpdate = client.send(HttpRequest.newBuilder().uri(URI("$base$libraryScorePath"))
                .header("Authorization", "Bearer ${session.token}").header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"score\":9.0}")).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, scoreUpdate.statusCode())
            assertTrue(scoreUpdate.body().contains("\"score\":9.0"))
            val list = client.send(HttpRequest.newBuilder().uri(URI("$base/api/v1/users/me/library?page=1&perPage=50")).header("Authorization", "Bearer ${session.token}").GET().build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, list.statusCode())
            assertTrue(list.body().contains("\"mediaId\":21"))
            assertEquals(401, get(client, "$base/api/v1/users/me").statusCode())
            val seedProfile = client.send(HttpRequest.newBuilder().uri(URI("$base/api/v1/users/me/profile"))
                .header("Authorization", "Bearer ${session.token}").header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("""{"username":"profile-user","displayName":"Profile Display","bio":"preserve this bio","avatarUrl":"https://example.com/avatar.png","bannerUrl":"https://example.com/banner.png"}""")).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, seedProfile.statusCode(), seedProfile.body())
            val partialProfile = client.send(HttpRequest.newBuilder().uri(URI("$base/api/v1/users/me/profile"))
                .header("Authorization", "Bearer ${session.token}").header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("""{"username":"profile-user-renamed"}""")).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, partialProfile.statusCode(), partialProfile.body())
            assertTrue(partialProfile.body().contains("\"displayName\":\"Profile Display\""))
            assertTrue(partialProfile.body().contains("\"bio\":\"preserve this bio\""))
            assertTrue(partialProfile.body().contains("\"avatarUrl\":\"https://example.com/avatar.png\""))
            assertTrue(partialProfile.body().contains("\"bannerUrl\":\"https://example.com/banner.png\""))

            val missingUsername = client.send(HttpRequest.newBuilder().uri(URI("$base/api/v1/users/me/profile"))
                .header("Authorization", "Bearer ${session.token}").header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"bio\":\"must not update without username\"}")).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(400, missingUsername.statusCode())
            val unknownNestedRoute = get(client, "$base/api/v1/users/me/not-a-route")
            assertEquals(404, unknownNestedRoute.statusCode())
            val unknownLibraryRoute = client.send(HttpRequest.newBuilder().uri(URI("$base$libraryItemPath/not-a-route"))
                .header("Authorization", "Bearer ${session.token}").GET().build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(404, unknownLibraryRoute.statusCode())
            val wrongMethodOnLibraryStatus = client.send(HttpRequest.newBuilder().uri(URI("$base$libraryStatusPath"))
                .header("Authorization", "Bearer ${session.token}").GET().build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(405, wrongMethodOnLibraryStatus.statusCode())
            assertEquals("PUT", wrongMethodOnLibraryStatus.headers().firstValue("Allow").orElse(""))
            val invalid = client.send(HttpRequest.newBuilder().uri(URI("$base$libraryProgressPath"))
                .header("Authorization", "Bearer ${session.token}").header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"progress\":-1}")).build(), HttpResponse.BodyHandlers.ofString())
            val missingProgress = client.send(HttpRequest.newBuilder().uri(URI("$base$libraryProgressPath"))
                .header("Authorization", "Bearer ${session.token}").header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(400, missingProgress.statusCode())
            assertEquals(400, invalid.statusCode())
        } finally {
            server.close()
            Files.walk(temp).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    @Test
    fun authenticatedSocialCommentRoutesPreserveTagAndEnforceAuthentication() {
        val temp = Files.createTempDirectory("shinigami-social-comment-api")
        val auth = streamix.auth.AuthRuntime(temp.resolve("auth"))
        val session = auth.auth.signIn(streamix.auth.ExternalIdentity("test", "social-comment-user", displayName = "Social Comment User"))
        val other = auth.auth.signIn(streamix.auth.ExternalIdentity("test", "social-comment-other", displayName = "Other User"))
        val registry = StreamixExtractorRegistry(emptyList())
        val catalog = CatalogService(repository = FileCatalogRepository(temp.resolve("catalog/episodes.json")), extractors = registry)
        val server = StreamixHttpServer(service = StreamixService(catalog = catalog), authRuntime = auth, catalog = catalog, host = "127.0.0.1", port = 0)
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        try {
            server.start()
            val base = "http://127.0.0.1:${server.boundPort()}"
            val create = HttpRequest.newBuilder().uri(URI("$base/api/v1/social/comments"))
                .header("Authorization", "Bearer ${session.token}").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"mediaId\":21,\"content\":\"contract comment\",\"tag\":7}")).build()
            val created = client.send(create, HttpResponse.BodyHandlers.ofString())
            assertEquals(201, created.statusCode())
            assertTrue(created.body().contains("\"tag\":7"))
            val list = client.send(HttpRequest.newBuilder().uri(URI("$base/api/v1/social/comments?mediaId=21&page=1&perPage=20"))
                .header("Authorization", "Bearer ${session.token}").GET().build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, list.statusCode())
            assertTrue(list.body().contains("\"tag\":7"))
            val commentId = Regex("\"id\":\"([^\"]+)\"").find(created.body())?.groupValues?.get(1)
                ?: error("created comment id missing")
            val forbiddenEdit = client.send(HttpRequest.newBuilder().uri(URI("$base/api/v1/social/comments/$commentId"))
                .header("Authorization", "Bearer ${other.token}").header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"content\":\"unauthorized edit\"}")).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(404, forbiddenEdit.statusCode())
            val forbiddenDelete = client.send(HttpRequest.newBuilder().uri(URI("$base/api/v1/social/comments/$commentId"))
                .header("Authorization", "Bearer ${other.token}").DELETE().build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(404, forbiddenDelete.statusCode())
            val unknownCommentSuffix = get(client, "$base/api/v1/social/comments/$commentId/not-a-route")
            assertEquals(404, unknownCommentSuffix.statusCode())
            val anonymousUnknownCommentDeepPath = get(client, "$base/api/v1/social/comments/$commentId/replies/extra")
            assertEquals(404, anonymousUnknownCommentDeepPath.statusCode())
            val wrongMethodOnCommentReplies = client.send(HttpRequest.newBuilder().uri(URI("$base/api/v1/social/comments/$commentId/replies"))
                .header("Authorization", "Bearer ${session.token}").POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString())
            assertEquals(405, wrongMethodOnCommentReplies.statusCode())
            assertEquals("GET", wrongMethodOnCommentReplies.headers().firstValue("Allow").orElse(""))
            val activity = client.send(HttpRequest.newBuilder().uri(URI("$base/api/v1/social/activities"))
                .header("Authorization", "Bearer ${session.token}").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"type":"watching","text":"contract activity"}""")).build(),
                HttpResponse.BodyHandlers.ofString())
            assertEquals(201, activity.statusCode())
            val activityId = Regex("\"id\":\"([^\"]+)\"").find(activity.body())?.groupValues?.get(1)
                ?: error("created activity id missing")
            val unknownActivitySuffix = get(client, "$base/api/v1/social/activities/$activityId/not-a-route")
            assertEquals(404, unknownActivitySuffix.statusCode())
            val anonymousUnknownActivityDeepPath = get(client, "$base/api/v1/social/activities/$activityId/replies/extra")
            assertEquals(404, anonymousUnknownActivityDeepPath.statusCode())
            // Social replies use the social notification contract/store, not the general app notification feed.
            val reply = client.send(HttpRequest.newBuilder().uri(URI("$base/api/v1/social/comments"))
                .header("Authorization", "Bearer ${other.token}").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"mediaId\":21,\"content\":\"reply to contract comment\",\"parentCommentId\":\"$commentId\"}"
                )).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(201, reply.statusCode(), reply.body())

            val socialNotifications = client.send(HttpRequest.newBuilder()
                .uri(URI("$base/api/v1/social/notifications?page=1&perPage=10"))
                .header("Authorization", "Bearer ${session.token}").GET().build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, socialNotifications.statusCode(), socialNotifications.body())
            val socialItems = com.google.gson.JsonParser.parseString(socialNotifications.body()).asJsonObject
                .getAsJsonArray("items")
            assertEquals(1, socialItems.size(), "A comment reply should notify the parent comment author")
            val socialNotificationId = socialItems[0].asJsonObject.get("id").asString

            val socialUnread = client.send(HttpRequest.newBuilder()
                .uri(URI("$base/api/v1/social/notifications/unread-count"))
                .header("Authorization", "Bearer ${session.token}").GET().build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, socialUnread.statusCode(), socialUnread.body())
            assertTrue(socialUnread.body().contains("\"count\":1"))

            val crossUserSocialRead = client.send(HttpRequest.newBuilder()
                .uri(URI("$base/api/v1/social/notifications/$socialNotificationId/read"))
                .header("Authorization", "Bearer ${other.token}").POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString())
            assertEquals(404, crossUserSocialRead.statusCode())

            val markSocialRead = client.send(HttpRequest.newBuilder()
                .uri(URI("$base/api/v1/social/notifications/$socialNotificationId/read"))
                .header("Authorization", "Bearer ${session.token}").POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString())
            assertEquals(200, markSocialRead.statusCode(), markSocialRead.body())

            val socialUnreadAfterRead = client.send(HttpRequest.newBuilder()
                .uri(URI("$base/api/v1/social/notifications/unread-count"))
                .header("Authorization", "Bearer ${session.token}").GET().build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, socialUnreadAfterRead.statusCode(), socialUnreadAfterRead.body())
            assertTrue(socialUnreadAfterRead.body().contains("\"count\":0"))
            val anonymous = get(client, "$base/api/v1/social/comments?mediaId=21")
            assertEquals(401, anonymous.statusCode())
        } finally {
            server.close()
            Files.walk(temp).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    @Test
    fun catalogMutationIsBlockedWhenWriteAheadAuditIntentCannotBePersisted() = kotlinx.coroutines.runBlocking {
        val temp = Files.createTempDirectory("shinigami-catalog-audit-intent-failure")
        val auth = streamix.auth.AuthRuntime(temp.resolve("auth"))
        val admin = auth.auth.signIn(
            streamix.auth.ExternalIdentity("test", "catalog-audit-intent-failure-admin", displayName = "Catalog Audit Intent Failure Admin")
        )
        auth.adminRoles.setRole(admin.user.id, AdminRole.FOUNDER)
        val auditDirectory = temp.resolve("auth/admin")
        Files.createDirectories(auditDirectory)
        Files.writeString(auditDirectory.resolve("audit-log.json"), "not-json")
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
            val url = "http://127.0.0.1:${server.boundPort()}/api/v1/admin/catalog/anime/21/import"
            val response = client.send(
                HttpRequest.newBuilder().uri(URI(url)).timeout(Duration.ofSeconds(3))
                    .header("Authorization", "Bearer ${admin.token}")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"sources":[{"url":"https://example.com/episode.m3u8","type":"direct","episode":1}]}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(500, response.statusCode(), response.body())
            assertTrue(catalog.episodes(21L).isEmpty(), "Catalog must remain unchanged when its audit intent cannot be persisted")
        } finally {
            server.close()
            Files.walk(temp).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }


    @Test
    fun catalogImportReportsCommittedMutationWhenCompletionAuditFails() = kotlinx.coroutines.runBlocking {
        val temp = Files.createTempDirectory("shinigami-catalog-audit-completion-failure")
        val auditStore = streamix.admin.FileAdminAuditRepository(temp.resolve("auth/admin"), com.google.gson.Gson())
        val failingAudit = object : streamix.admin.AdminAuditRepository {
            override fun append(entry: AdminAuditEntry) {
                if (entry.action == "CATALOG_BULK_IMPORT") {
                    throw IllegalStateException("simulated completion audit write failure")
                }
                auditStore.append(entry)
            }
            override fun recent(limit: Int): List<AdminAuditEntry> = auditStore.recent(limit)
            override fun findByOperationId(operationId: String): List<AdminAuditEntry> =
                auditStore.findByOperationId(operationId)
        }
        val auth = streamix.auth.AuthRuntime(temp.resolve("auth"), adminAuditRepository = failingAudit)
        val admin = auth.auth.signIn(
            streamix.auth.ExternalIdentity("test", "catalog-audit-completion-failure-admin", displayName = "Catalog Audit Failure Admin")
        )
        auth.adminRoles.setRole(admin.user.id, AdminRole.FOUNDER)
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
            val response = client.send(
                HttpRequest.newBuilder()
                    .uri(URI("http://127.0.0.1:${server.boundPort()}/api/v1/admin/catalog/anime/21/import"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer ${admin.token}")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                        """{"sources":[{"url":"https://example.com/persisted.m3u8","type":"direct","episode":1}]}"""
                    )).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(500, response.statusCode(), response.body())
            val body = com.google.gson.JsonParser.parseString(response.body()).asJsonObject
            assertTrue(body.get("mutationCommitted").asBoolean, response.body())
            assertFalse(body.get("auditRecorded").asBoolean, response.body())
            val operationId = body.get("operationId").asString
            assertTrue(operationId.isNotBlank())
            assertEquals(1, catalog.episodes(21L).single().sources.size,
                "The source must remain persisted when completion-audit recording fails")
            val entries = auditStore.recent(20)
            assertTrue(entries.any {
                it.action == "CATALOG_BULK_IMPORT_INTENT" && it.metadata["operationId"] == operationId
            }, "the correlated write-ahead intent should remain available")
            assertFalse(entries.any {
                it.action == "CATALOG_BULK_IMPORT" && it.metadata["operationId"] == operationId
            }, "the failed completion append must not appear as recorded")
        } finally {
            server.close()
            Files.walk(temp).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun catalogImportInfersQualityFromResolutionLabels() = kotlinx.coroutines.runBlocking {
        val temp = Files.createTempDirectory("shinigami-catalog-import-resolution")
        val auth = streamix.auth.AuthRuntime(temp.resolve("auth"))
        val admin = auth.auth.signIn(
            streamix.auth.ExternalIdentity("test", "catalog-import-admin", displayName = "Catalog Import Admin")
        )
        auth.adminRoles.setRole(admin.user.id, AdminRole.FOUNDER)
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
            val url = "http://127.0.0.1:${server.boundPort()}/api/v1/admin/catalog/anime/21/import"
            val body = """{"sources":[{"url":"https://example.com/4k.m3u8","type":"direct","resolution":"4K","episode":1},{"url":"https://example.com/1080.m3u8","type":"direct","resolution":"1920x1080","episode":2}]}"""
            val response = client.send(
                HttpRequest.newBuilder().uri(URI(url)).timeout(Duration.ofSeconds(3))
                    .header("Authorization", "Bearer ${admin.token}")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(200, response.statusCode(), response.body())
            assertEquals(2160, catalog.streams(21L, 1).single().quality)
            assertEquals(1080, catalog.streams(21L, 2).single().quality)
            val importAudit = auth.adminAudit.recent(10)
            val importIntent = importAudit.first { it.action == "CATALOG_BULK_IMPORT_INTENT" }
            val importCompletion = importAudit.first { it.action == "CATALOG_BULK_IMPORT" }
            assertTrue(importIntent.metadata["operationId"].orEmpty().isNotBlank())
            assertEquals(importIntent.metadata["operationId"], importCompletion.metadata["operationId"])
        } finally {
            server.close()
            Files.walk(temp).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    @Test
    fun catalogImportRejectsUnknownExplicitSourceTypeWithoutMutation() = kotlinx.coroutines.runBlocking {
        val temp = Files.createTempDirectory("shinigami-catalog-import-unknown-type")
        val auth = streamix.auth.AuthRuntime(temp.resolve("auth"))
        val admin = auth.auth.signIn(
            streamix.auth.ExternalIdentity("test", "catalog-import-unknown-type-admin", displayName = "Catalog Import Admin")
        )
        auth.adminRoles.setRole(admin.user.id, AdminRole.FOUNDER)
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
            val url = "http://127.0.0.1:${server.boundPort()}/api/v1/admin/catalog/anime/21/import"
            val body = """{"sources":[{"url":"https://example.com/episode","type":"resovler","episode":1}]}"""
            val response = client.send(
                HttpRequest.newBuilder().uri(URI(url)).timeout(Duration.ofSeconds(3))
                    .header("Authorization", "Bearer ${admin.token}")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(400, response.statusCode(), response.body())
            assertTrue(response.body().contains("unsupported catalog import source type"))
            assertTrue(catalog.episodes(21L).isEmpty())
        } finally {
            server.close()
            Files.walk(temp).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    @Test
    fun catalogImportWithNoUsableSourcesReturnsBadRequestWithoutMutation() = kotlinx.coroutines.runBlocking {
        val temp = Files.createTempDirectory("shinigami-catalog-import-empty")
        val auth = streamix.auth.AuthRuntime(temp.resolve("auth"))
        val admin = auth.auth.signIn(
            streamix.auth.ExternalIdentity("test", "catalog-import-empty-admin", displayName = "Catalog Import Admin")
        )
        auth.adminRoles.setRole(admin.user.id, AdminRole.FOUNDER)
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
            val url = "http://127.0.0.1:${server.boundPort()}/api/v1/admin/catalog/anime/21/import"
            val body = """{"sources":[{"url":"https://example.com/video.m3u8","type":"direct"}]}"""
            val response = client.send(
                HttpRequest.newBuilder().uri(URI(url)).timeout(Duration.ofSeconds(3))
                    .header("Authorization", "Bearer ${admin.token}")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(400, response.statusCode(), response.body())
            assertTrue(response.body().contains("catalog import contains no valid sources"))
            assertTrue(catalog.episodes(21L).isEmpty())
        } finally {
            server.close()
            Files.walk(temp).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    @Test
    fun catalogSourceRouteRejectsMissingRequiredFieldsAndMalformedHeaders() {
        val temp = Files.createTempDirectory("shinigami-catalog-source-validation")
        val auth = streamix.auth.AuthRuntime(temp.resolve("auth"))
        val admin = auth.auth.signIn(
            streamix.auth.ExternalIdentity("test", "catalog-source-admin", displayName = "Catalog Admin")
        )
        auth.adminRoles.setRole(admin.user.id, AdminRole.FOUNDER)
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
            val url = "http://127.0.0.1:${server.boundPort()}/api/v1/admin/catalog/anime/21/episode/1/sources"
            val invalidBodies = listOf(
                """{"anilistId":21,"episodeNumber":1,"sourceUrl":"https://example.com/video.m3u8","type":"DIRECT"}""",
                """{"id":"source-1","anilistId":21,"episodeNumber":1,"type":"DIRECT"}""",
                """{"id":"source-1","anilistId":21,"episodeNumber":1,"sourceUrl":"https://example.com/video.m3u8"}""",
                """{"id":"source-1","anilistId":21,"episodeNumber":1,"sourceUrl":"https://example.com/video.m3u8","type":"DIRECT","headers":[]}""",
                """{"id":"source-1","anilistId":21,"episodeNumber":1.5,"sourceUrl":"https://example.com/video.m3u8","type":"DIRECT"}""",
                """{"id":"source-1","anilistId":21,"episodeNumber":1,"sourceUrl":"https://example.com/video.m3u8","type":"DIRECT","quality":480.5}""",
                """{"id":"source-1","anilistId":21,"episodeNumber":1,"sourceUrl":"https://example.com/video.m3u8","type":"DIRECT","priority":2147483648}"""
            )
            invalidBodies.forEach { body ->
                val response = client.send(
                    HttpRequest.newBuilder().uri(URI(url)).timeout(Duration.ofSeconds(3))
                        .header("Authorization", "Bearer ${admin.token}")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString()
                )
                assertEquals(400, response.statusCode(), "Expected invalid catalog source payload to be rejected")
            }

            val missingSourceRoutes = listOf(
                "http://127.0.0.1:${server.boundPort()}/api/v1/admin/catalog/anime/21/episode/1/sources/missing/check" to "POST",
                "http://127.0.0.1:${server.boundPort()}/api/v1/admin/catalog/anime/21/episode/1/sources/missing/enabled?value=true" to "POST",
                "http://127.0.0.1:${server.boundPort()}/api/v1/admin/catalog/anime/21/episode/1/sources/missing" to "DELETE"
            )
            missingSourceRoutes.forEach { (route, method) ->
                val builder = HttpRequest.newBuilder().uri(URI(route)).timeout(Duration.ofSeconds(3))
                    .header("Authorization", "Bearer ${admin.token}")
                val request = when (method) {
                    "DELETE" -> builder.DELETE().build()
                    else -> builder.POST(HttpRequest.BodyPublishers.noBody()).build()
                }
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                assertEquals(404, response.statusCode(), "Expected missing catalog source to return 404 for $method $route")
            }

            val wrongMethodRoutes = listOf(
                "http://127.0.0.1:${server.boundPort()}/api/v1/admin/catalog/anime/21/import" to "GET",
                "http://127.0.0.1:${server.boundPort()}/api/v1/admin/catalog/anime/21/episodes" to "POST"
            )
            wrongMethodRoutes.forEach { (route, method) ->
                val builder = HttpRequest.newBuilder().uri(URI(route)).timeout(Duration.ofSeconds(3))
                    .header("Authorization", "Bearer ${admin.token}")
                val request = when (method) {
                    "GET" -> builder.GET().build()
                    else -> builder.POST(HttpRequest.BodyPublishers.noBody()).build()
                }
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                assertEquals(405, response.statusCode(), "Expected wrong catalog method to return 405 for $method $route")
            }
        } finally {
            server.close()
            Files.walk(temp).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    @Test
    fun catalogSourceDeleteExceptionProducesCorrelatedFailureAudit() {
        val temp = Files.createTempDirectory("shinigami-catalog-source-delete-failure")
        val auth = streamix.auth.AuthRuntime(temp.resolve("auth"))
        val admin = auth.auth.signIn(
            streamix.auth.ExternalIdentity("test", "catalog-delete-failure-admin", displayName = "Catalog Delete Failure Admin")
        )
        auth.adminRoles.setRole(admin.user.id, AdminRole.FOUNDER)
        val backing = FileCatalogRepository(temp.resolve("catalog/episodes.json"))
        val source = backing.upsertSource(
            EpisodeSource(
                id = "delete-failure-source",
                anilistId = 21L,
                episodeNumber = 1,
                sourceUrl = "https://example.com/video.m3u8",
                type = SourceType.DIRECT
            )
        )
        val failingRepository = object : streamix.catalog.CatalogRepository {
            override fun listEpisodes(anilistId: Long) = backing.listEpisodes(anilistId)
            override fun getEpisode(anilistId: Long, number: Int) = backing.getEpisode(anilistId, number)
            override fun upsertSource(source: EpisodeSource) = backing.upsertSource(source)
            override fun upsertSources(sources: List<EpisodeSource>) = backing.upsertSources(sources)
            override fun deleteSource(anilistId: Long, episodeNumber: Int, sourceId: String): Boolean {
                throw IllegalStateException("simulated repository write failure")
            }
            override fun updateSourceHealth(
                anilistId: Long,
                episodeNumber: Int,
                sourceId: String,
                health: streamix.catalog.SourceHealth,
                checkedAt: Long,
                httpStatus: Int?,
                failureReason: String?
            ) = backing.updateSourceHealth(anilistId, episodeNumber, sourceId, health, checkedAt, httpStatus, failureReason)
        }
        val catalog = CatalogService(failingRepository, StreamixExtractorRegistry(emptyList()))
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
            val response = client.send(
                HttpRequest.newBuilder()
                    .uri(URI("http://127.0.0.1:${server.boundPort()}/api/v1/admin/catalog/anime/21/episode/1/sources/${source.id}"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer ${admin.token}")
                    .DELETE()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(500, response.statusCode(), response.body())
            val entries = auth.adminAudit.recent(20)
            val intent = entries.firstOrNull { it.action == "CATALOG_SOURCE_DELETE_INTENT" }
            assertTrue(intent != null, "delete intent must be persisted before mutation")
            val operationId = intent?.metadata?.get("operationId")
            assertTrue(operationId.orEmpty().isNotBlank(), "delete intent must have an operationId")
            val failure = entries.firstOrNull {
                it.action == "CATALOG_SOURCE_DELETE_FAILED" && it.metadata["operationId"] == operationId
            }
            assertEquals("FAILED", failure?.metadata?.get("phase"))
            assertEquals("500", failure?.metadata?.get("httpStatus"))
            assertEquals("IllegalStateException", failure?.metadata?.get("errorType"))
            assertFalse(entries.any {
                it.action == "CATALOG_SOURCE_DELETE" && it.metadata["operationId"] == operationId
            }, "failed delete must not emit a success completion")
        } finally {
            server.close()
            Files.walk(temp).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    @Test
    fun catalogSourceUpsertExceptionProducesCorrelatedFailureAudit() {
        val temp = Files.createTempDirectory("shinigami-catalog-source-upsert-failure")
        val auth = streamix.auth.AuthRuntime(temp.resolve("auth"))
        val admin = auth.auth.signIn(
            streamix.auth.ExternalIdentity("test", "catalog-upsert-failure-admin", displayName = "Catalog Upsert Failure Admin")
        )
        auth.adminRoles.setRole(admin.user.id, AdminRole.FOUNDER)
        val backing = FileCatalogRepository(temp.resolve("catalog/episodes.json"))
        val failingRepository = object : streamix.catalog.CatalogRepository {
            override fun listEpisodes(anilistId: Long) = backing.listEpisodes(anilistId)
            override fun getEpisode(anilistId: Long, number: Int) = backing.getEpisode(anilistId, number)
            override fun upsertSource(source: EpisodeSource): EpisodeSource {
                throw IllegalStateException("simulated repository write failure")
            }
            override fun upsertSources(sources: List<EpisodeSource>) = backing.upsertSources(sources)
            override fun deleteSource(anilistId: Long, episodeNumber: Int, sourceId: String) =
                backing.deleteSource(anilistId, episodeNumber, sourceId)
            override fun updateSourceHealth(
                anilistId: Long,
                episodeNumber: Int,
                sourceId: String,
                health: streamix.catalog.SourceHealth,
                checkedAt: Long,
                httpStatus: Int?,
                failureReason: String?
            ) = backing.updateSourceHealth(anilistId, episodeNumber, sourceId, health, checkedAt, httpStatus, failureReason)
        }
        val catalog = CatalogService(failingRepository, StreamixExtractorRegistry(emptyList()))
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
            val response = client.send(
                HttpRequest.newBuilder()
                    .uri(URI("http://127.0.0.1:${server.boundPort()}/api/v1/admin/catalog/anime/21/episode/1/sources"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer ${admin.token}")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                        """{"id":"upsert-failure-source","anilistId":21,"episodeNumber":1,"sourceUrl":"https://example.com/video.m3u8","type":"DIRECT","quality":720}"""
                    ))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(500, response.statusCode(), response.body())
            val entries = auth.adminAudit.recent(20)
            val intent = entries.firstOrNull { it.action == "CATALOG_SOURCE_UPSERT_INTENT" }
            assertTrue(intent != null, "upsert intent must be persisted before mutation")
            val operationId = intent?.metadata?.get("operationId")
            assertTrue(operationId.orEmpty().isNotBlank(), "upsert intent must have an operationId")
            val failure = entries.firstOrNull {
                it.action == "CATALOG_SOURCE_UPSERT_FAILED" && it.metadata["operationId"] == operationId
            }
            assertEquals("FAILED", failure?.metadata?.get("phase"))
            assertEquals("500", failure?.metadata?.get("httpStatus"))
            assertEquals("IllegalStateException", failure?.metadata?.get("errorType"))
            assertFalse(entries.any {
                it.action == "CATALOG_SOURCE_UPSERT" && it.metadata["operationId"] == operationId
            }, "failed upsert must not emit a success completion")
        } finally {
            server.close()
            Files.walk(temp).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    @Test
    fun catalogSourceCheckExceptionProducesCorrelatedFailureAudit() {
        val temp = Files.createTempDirectory("shinigami-catalog-check-failure-audit")
        val auth = streamix.auth.AuthRuntime(temp.resolve("auth"))
        val admin = auth.auth.signIn(
            streamix.auth.ExternalIdentity("test", "catalog-check-failure-admin", displayName = "Catalog Check Failure Admin")
        )
        auth.adminRoles.setRole(admin.user.id, AdminRole.FOUNDER)
        val backing = FileCatalogRepository(temp.resolve("catalog/episodes.json"))
        val source = backing.upsertSource(
            EpisodeSource(
                id = "check-failure-source",
                anilistId = 21L,
                episodeNumber = 1,
                sourceUrl = "https://example.com/video.m3u8",
                type = SourceType.EXTRACTOR
            )
        )
        val failingRepository = object : streamix.catalog.CatalogRepository {
            override fun listEpisodes(anilistId: Long) = backing.listEpisodes(anilistId)
            override fun getEpisode(anilistId: Long, number: Int) = backing.getEpisode(anilistId, number)
            override fun upsertSource(source: EpisodeSource) = backing.upsertSource(source)
            override fun upsertSources(sources: List<EpisodeSource>) = backing.upsertSources(sources)
            override fun deleteSource(anilistId: Long, episodeNumber: Int, sourceId: String) =
                backing.deleteSource(anilistId, episodeNumber, sourceId)
            override fun updateSourceHealth(
                anilistId: Long,
                episodeNumber: Int,
                sourceId: String,
                health: streamix.catalog.SourceHealth,
                checkedAt: Long,
                httpStatus: Int?,
                failureReason: String?
            ): EpisodeSource? {
                throw IllegalStateException("simulated health persistence failure")
            }
        }
        val catalog = CatalogService(failingRepository, StreamixExtractorRegistry(emptyList()))
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
            val response = client.send(
                HttpRequest.newBuilder()
                    .uri(URI("http://127.0.0.1:${server.boundPort()}/api/v1/admin/catalog/anime/21/episode/1/sources/${source.id}/check"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer ${admin.token}")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(500, response.statusCode(), response.body())

            val entries = auth.adminAudit.recent(30)
            val intent = entries.firstOrNull { it.action == "CATALOG_SOURCE_CHECK_INTENT" }
            assertTrue(intent != null, "source check intent must be persisted before checking")
            val operationId = intent?.metadata?.get("operationId")
            val failure = entries.firstOrNull {
                it.action == "CATALOG_SOURCE_CHECK_FAILED" && it.metadata["operationId"] == operationId
            }
            assertEquals("FAILED", failure?.metadata?.get("phase"))
            assertEquals("500", failure?.metadata?.get("httpStatus"))
            assertEquals("IllegalStateException", failure?.metadata?.get("errorType"))
            assertFalse(entries.any {
                it.action == "CATALOG_SOURCE_CHECK" && it.metadata["operationId"] == operationId
            }, "failed source check must not emit a success completion")
        } finally {
            server.close()
            Files.walk(temp).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    @Test
    fun catalogBulkImportAndSourceToggleExceptionsProduceCorrelatedFailureAudits() {
        val temp = Files.createTempDirectory("shinigami-catalog-mutation-failure-audit")
        val auth = streamix.auth.AuthRuntime(temp.resolve("auth"))
        val admin = auth.auth.signIn(
            streamix.auth.ExternalIdentity("test", "catalog-mutation-failure-admin", displayName = "Catalog Mutation Failure Admin")
        )
        auth.adminRoles.setRole(admin.user.id, AdminRole.FOUNDER)
        val backing = FileCatalogRepository(temp.resolve("catalog/episodes.json"))
        val source = backing.upsertSource(
            EpisodeSource(
                id = "toggle-failure-source",
                anilistId = 21L,
                episodeNumber = 1,
                sourceUrl = "https://example.com/video.m3u8",
                type = SourceType.DIRECT
            )
        )
        val failingRepository = object : streamix.catalog.CatalogRepository {
            override fun listEpisodes(anilistId: Long) = backing.listEpisodes(anilistId)
            override fun getEpisode(anilistId: Long, number: Int) = backing.getEpisode(anilistId, number)
            override fun upsertSource(source: EpisodeSource): EpisodeSource {
                throw IllegalStateException("simulated source update failure")
            }
            override fun upsertSources(sources: List<EpisodeSource>): List<EpisodeSource> {
                throw IllegalStateException("simulated bulk import failure")
            }
            override fun deleteSource(anilistId: Long, episodeNumber: Int, sourceId: String) =
                backing.deleteSource(anilistId, episodeNumber, sourceId)
            override fun updateSourceHealth(
                anilistId: Long,
                episodeNumber: Int,
                sourceId: String,
                health: streamix.catalog.SourceHealth,
                checkedAt: Long,
                httpStatus: Int?,
                failureReason: String?
            ) = backing.updateSourceHealth(anilistId, episodeNumber, sourceId, health, checkedAt, httpStatus, failureReason)
        }
        val catalog = CatalogService(failingRepository, StreamixExtractorRegistry(emptyList()))
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
            val base = "http://127.0.0.1:${server.boundPort()}/api/v1/admin/catalog/anime/21"
            val toggleResponse = client.send(
                HttpRequest.newBuilder()
                    .uri(URI("$base/episode/1/sources/${source.id}/enabled?value=false"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer ${admin.token}")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(500, toggleResponse.statusCode(), toggleResponse.body())
            val importResponse = client.send(
                HttpRequest.newBuilder()
                    .uri(URI("$base/import"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer ${admin.token}")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                        """{"sources":[{"url":"https://example.com/import.m3u8","type":"direct","episode":2}]}"""
                    ))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(500, importResponse.statusCode(), importResponse.body())

            val entries = auth.adminAudit.recent(30)
            val toggleIntent = entries.firstOrNull { it.action == "CATALOG_SOURCE_DISABLE_INTENT" }
            assertTrue(toggleIntent != null, "toggle intent must be persisted before mutation")
            val toggleOperationId = toggleIntent?.metadata?.get("operationId")
            val toggleFailure = entries.firstOrNull {
                it.action == "CATALOG_SOURCE_DISABLE_FAILED" && it.metadata["operationId"] == toggleOperationId
            }
            assertEquals("FAILED", toggleFailure?.metadata?.get("phase"))
            assertEquals("500", toggleFailure?.metadata?.get("httpStatus"))
            assertEquals("IllegalStateException", toggleFailure?.metadata?.get("errorType"))
            assertFalse(entries.any {
                it.action == "CATALOG_SOURCE_DISABLE" && it.metadata["operationId"] == toggleOperationId
            }, "failed toggle must not emit a success completion")

            val importIntent = entries.firstOrNull { it.action == "CATALOG_BULK_IMPORT_INTENT" }
            assertTrue(importIntent != null, "bulk import intent must be persisted before mutation")
            val importOperationId = importIntent?.metadata?.get("operationId")
            val importFailure = entries.firstOrNull {
                it.action == "CATALOG_BULK_IMPORT_FAILED" && it.metadata["operationId"] == importOperationId
            }
            assertEquals("FAILED", importFailure?.metadata?.get("phase"))
            assertEquals("500", importFailure?.metadata?.get("httpStatus"))
            assertEquals("IllegalStateException", importFailure?.metadata?.get("errorType"))
            assertFalse(entries.any {
                it.action == "CATALOG_BULK_IMPORT" && it.metadata["operationId"] == importOperationId
            }, "failed import must not emit a success completion")
        } finally {
            server.close()
            Files.walk(temp).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    @Test
    fun catalogSourceCheckReturnsNotFoundWhenSourceDisappearsAfterInitialListing() {
        val temp = Files.createTempDirectory("shinigami-catalog-source-check-race")
        val auth = streamix.auth.AuthRuntime(temp.resolve("auth"))
        val admin = auth.auth.signIn(
            streamix.auth.ExternalIdentity("test", "catalog-source-race-admin", displayName = "Catalog Source Race Admin")
        )
        auth.adminRoles.setRole(admin.user.id, AdminRole.FOUNDER)
        val backing = FileCatalogRepository(temp.resolve("catalog/episodes.json"))
        val source = backing.upsertSource(
            EpisodeSource(
                id = "race-source",
                anilistId = 21L,
                episodeNumber = 1,
                sourceUrl = "https://example.com/video.m3u8",
                type = SourceType.DIRECT
            )
        )
        val racingRepository = object : streamix.catalog.CatalogRepository {
            override fun listEpisodes(anilistId: Long) = backing.listEpisodes(anilistId)
            override fun getEpisode(anilistId: Long, number: Int): streamix.catalog.CatalogEpisode? = null
            override fun upsertSource(source: EpisodeSource) = backing.upsertSource(source)
            override fun upsertSources(sources: List<EpisodeSource>) = backing.upsertSources(sources)
            override fun deleteSource(anilistId: Long, episodeNumber: Int, sourceId: String) =
                backing.deleteSource(anilistId, episodeNumber, sourceId)
            override fun updateSourceHealth(
                anilistId: Long,
                episodeNumber: Int,
                sourceId: String,
                health: streamix.catalog.SourceHealth,
                checkedAt: Long,
                httpStatus: Int?,
                failureReason: String?
            ) = backing.updateSourceHealth(anilistId, episodeNumber, sourceId, health, checkedAt, httpStatus, failureReason)
        }
        val catalog = CatalogService(
            repository = racingRepository,
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
            val response = client.send(
                HttpRequest.newBuilder()
                    .uri(URI("http://127.0.0.1:${server.boundPort()}/api/v1/admin/catalog/anime/21/episode/1/sources/${source.id}/check"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer ${admin.token}")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(404, response.statusCode(), response.body())
            assertTrue(response.body().contains("source not found"))
            val failedCheckAudit = auth.adminAudit.recent(20)
            assertFalse(failedCheckAudit.any { it.action == "CATALOG_SOURCE_CHECK" })
            val failedCheckIntent = failedCheckAudit.firstOrNull { it.action == "CATALOG_SOURCE_CHECK_INTENT" }
            assertTrue(failedCheckIntent?.metadata?.get("operationId").orEmpty().isNotBlank(), "failed checks must leave a durable correlated intent")
        } finally {
            server.close()
            Files.walk(temp).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    @Test
    fun catalogAdminMutationsRecordAuditEventsAndReturnNotFoundForDeletedSource() {
        val temp = Files.createTempDirectory("shinigami-catalog-admin-audit")
        val auth = streamix.auth.AuthRuntime(temp.resolve("auth"))
        val admin = auth.auth.signIn(
            streamix.auth.ExternalIdentity("test", "catalog-audit-admin", displayName = "Catalog Audit Admin")
        )
        auth.adminRoles.setRole(admin.user.id, AdminRole.FOUNDER)
        val catalog = CatalogService(
            repository = FileCatalogRepository(temp.resolve("catalog/episodes.json")),
            extractors = StreamixExtractorRegistry(emptyList()),
            resolvers = streamix.catalog.CatalogResolverRegistry(listOf(
                object : streamix.catalog.CatalogResolver {
                    override val id = "catalog-audit-test"
                    override val domains = setOf("example.com")
                    override suspend fun resolve(source: EpisodeSource) =
                        streamix.catalog.CatalogResolverResult(
                            streams = listOf(
                                streamix.api.StreamRef(
                                    providerId = "catalog-audit-test",
                                    url = "https://example.com/resolved.m3u8",
                                    quality = 720,
                                    type = "m3u8"
                                )
                            )
                        )
                }
            ))
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
            val base = "http://127.0.0.1:${server.boundPort()}/api/v1/admin/catalog/anime/21/episode/1/sources"
            val member = auth.auth.signIn(
                streamix.auth.ExternalIdentity("test", "catalog-audit-member", displayName = "Catalog Audit Member")
            )
            val forbidden = client.send(
                HttpRequest.newBuilder().uri(URI("http://127.0.0.1:${server.boundPort()}/api/v1/admin/catalog/anime/21/episodes"))
                    .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer ${member.token}").GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(403, forbidden.statusCode(), forbidden.body())

            val unauthorized = client.send(
                HttpRequest.newBuilder().uri(URI("http://127.0.0.1:${server.boundPort()}/api/v1/admin/catalog/anime/21/episodes"))
                    .timeout(Duration.ofSeconds(15)).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(401, unauthorized.statusCode(), unauthorized.body())

            val create = client.send(
                HttpRequest.newBuilder().uri(URI(base)).timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer ${admin.token}")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                        """{"id":"client-source-id","anilistId":21,"episodeNumber":1,"sourceUrl":"https://example.com/video.m3u8","type":"RESOLVER","quality":720}"""
                    )).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(200, create.statusCode(), create.body())
            val saved = com.google.gson.JsonParser.parseString(create.body()).asJsonObject
            val sourceId = saved.get("id").asString
            assertTrue(sourceId.isNotBlank())
            assertTrue(sourceId != "client-source-id")

            val check = client.send(
                HttpRequest.newBuilder().uri(URI("$base/${java.net.URLEncoder.encode(sourceId, Charsets.UTF_8)}/check"))
                    .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer ${admin.token}")
                    .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(200, check.statusCode(), check.body())
            assertTrue(check.body().contains("\"health\":\"STREAMING\""))

            val enableTrue = client.send(
                HttpRequest.newBuilder().uri(URI("$base/${java.net.URLEncoder.encode(sourceId, Charsets.UTF_8)}/enabled?value=true"))
                    .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer ${admin.token}")
                    .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(200, enableTrue.statusCode(), enableTrue.body())
            assertEquals(true, com.google.gson.JsonParser.parseString(enableTrue.body()).asJsonObject.get("enabled").asBoolean)

            val enable = client.send(
                HttpRequest.newBuilder().uri(URI("$base/${java.net.URLEncoder.encode(sourceId, Charsets.UTF_8)}/enabled?value=false"))
                    .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer ${admin.token}")
                    .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(200, enable.statusCode(), enable.body())
            assertEquals(false, com.google.gson.JsonParser.parseString(enable.body()).asJsonObject.get("enabled").asBoolean)

            val delete = client.send(
                HttpRequest.newBuilder().uri(URI("$base/${java.net.URLEncoder.encode(sourceId, Charsets.UTF_8)}"))
                    .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer ${admin.token}")
                    .DELETE().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(200, delete.statusCode(), delete.body())
            assertEquals(true, com.google.gson.JsonParser.parseString(delete.body()).asJsonObject.get("deleted").asBoolean)

            val missingDelete = client.send(
                HttpRequest.newBuilder().uri(URI("$base/${java.net.URLEncoder.encode(sourceId, Charsets.UTF_8)}"))
                    .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer ${admin.token}")
                    .DELETE().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(404, missingDelete.statusCode(), missingDelete.body())

            val auditEntries = auth.adminAudit.recent(40)
            val actions = auditEntries.map { it.action }
            fun assertIntentCorrelated(intentAction: String, completionAction: String) {
                val intents = auditEntries.filter { it.action == intentAction }
                val completions = auditEntries.filter { it.action == completionAction }
                assertTrue(intents.isNotEmpty(), "expected at least one $intentAction audit event")
                assertTrue(completions.isNotEmpty(), "expected at least one $completionAction audit event")
                completions.forEach { completion ->
                    val operationId = completion.metadata["operationId"]
                    assertTrue(
                        operationId.orEmpty().isNotBlank(),
                        "$completionAction must include a nonblank operationId; metadata=${completion.metadata}"
                    )
                    assertTrue(
                        intents.any { it.metadata["operationId"] == operationId },
                        "$completionAction must have a $intentAction event with the same operationId; completion=${completion.metadata}, intents=${intents.map { it.metadata }}"
                    )
                }
            }
            assertIntentCorrelated("CATALOG_SOURCE_UPSERT_INTENT", "CATALOG_SOURCE_UPSERT")
            assertIntentCorrelated("CATALOG_SOURCE_CHECK_INTENT", "CATALOG_SOURCE_CHECK")
            assertIntentCorrelated("CATALOG_SOURCE_DISABLE_INTENT", "CATALOG_SOURCE_DISABLE")
            assertIntentCorrelated("CATALOG_SOURCE_ENABLE_INTENT", "CATALOG_SOURCE_ENABLE")
            assertIntentCorrelated("CATALOG_SOURCE_DELETE_INTENT", "CATALOG_SOURCE_DELETE")
            
            // Exercise the HTTP query parameter, not only the repository lookup implementation.
            val upsertOperationId = auditEntries.first {
                it.action == "CATALOG_SOURCE_UPSERT"
            }.metadata["operationId"]!!
            val operationAuditResponse = client.send(
                HttpRequest.newBuilder()
                    .uri(URI("http://127.0.0.1:${server.boundPort()}/api/v1/admin/audit-log?operationId=${java.net.URLEncoder.encode(upsertOperationId, Charsets.UTF_8)}"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer ${admin.token}")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(200, operationAuditResponse.statusCode(), operationAuditResponse.body())
            val operationAuditEntries = com.google.gson.JsonParser.parseString(operationAuditResponse.body()).asJsonArray
            assertTrue(operationAuditEntries.size() > 0, operationAuditResponse.body())
            assertTrue(
                operationAuditEntries.all {
                    it.asJsonObject.getAsJsonObject("metadata").get("operationId")?.asString == upsertOperationId
                },
                "operationId lookup must return only correlated events: ${operationAuditResponse.body()}"
            )
            val returnedActions = operationAuditEntries.map { it.asJsonObject.get("action").asString }.toSet()
            assertTrue("CATALOG_SOURCE_UPSERT_INTENT" in returnedActions, operationAuditResponse.body())
            assertTrue("CATALOG_SOURCE_UPSERT" in returnedActions, operationAuditResponse.body())
            val completedDeleteOperationId = auditEntries
                .first { it.action == "CATALOG_SOURCE_DELETE" }
                .metadata["operationId"]
            val missingDeleteIntent = auditEntries.firstOrNull {
                it.action == "CATALOG_SOURCE_DELETE_INTENT" &&
                    it.metadata["operationId"] != completedDeleteOperationId
            }
            val missingDeleteOperationId = missingDeleteIntent?.metadata?.get("operationId")
            assertTrue(
                missingDeleteOperationId.orEmpty().isNotBlank(),
                "a 404 delete must leave a durable intent with its own operationId"
            )
            assertFalse(
                auditEntries.any {
                    it.action == "CATALOG_SOURCE_DELETE" &&
                        it.metadata["operationId"] == missingDeleteOperationId
                },
                "a failed delete must not emit a success completion event"
            )
            val failedDelete = auditEntries.firstOrNull {
                it.action == "CATALOG_SOURCE_DELETE_FAILED" &&
                    it.metadata["operationId"] == missingDeleteOperationId
            }
            assertEquals("FAILED", failedDelete?.metadata?.get("phase"))
            assertEquals("404", failedDelete?.metadata?.get("httpStatus"))
            assertTrue(
                auditEntries.any {
                    it.action == "CATALOG_SOURCE_DELETE_INTENT" &&
                        it.metadata["operationId"] == failedDelete?.metadata?.get("operationId")
                },
                "the failed terminal event must correlate to its durable write-ahead intent"
            )
            assertTrue("CATALOG_SOURCE_UPSERT" in actions)
            assertTrue("CATALOG_SOURCE_UPSERT_INTENT" in actions)
            assertTrue("CATALOG_SOURCE_CHECK" in actions)
            assertTrue("CATALOG_SOURCE_CHECK_INTENT" in actions)
            assertTrue("CATALOG_SOURCE_DISABLE" in actions)
            assertTrue("CATALOG_SOURCE_ENABLE" in actions)
            assertTrue("CATALOG_SOURCE_DISABLE_INTENT" in actions)
            assertTrue("CATALOG_SOURCE_ENABLE_INTENT" in actions)
            assertTrue("CATALOG_SOURCE_DELETE" in actions)
            assertTrue("CATALOG_SOURCE_DELETE_INTENT" in actions)
        } finally {
            server.close()
            Files.walk(temp).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    @Test
    fun adminRoutesRequireSessionAndPermissionSpecificRoles() {
        val temp = Files.createTempDirectory("shinigami-admin-api-contract")
        val auth = streamix.auth.AuthRuntime(temp.resolve("auth"))
        val member = auth.auth.signIn(
            streamix.auth.ExternalIdentity("test", "admin-contract-member", displayName = "Admin Contract Member")
        )
        val founder = auth.auth.signIn(
            streamix.auth.ExternalIdentity("test", "admin-contract-founder", displayName = "Admin Contract Founder")
        )
        val admin = auth.auth.signIn(
            streamix.auth.ExternalIdentity("test", "admin-contract-admin", displayName = "Admin Contract Admin")
        )
        auth.adminRoles.setRole(founder.user.id, AdminRole.FOUNDER)
        auth.adminRoles.setRole(admin.user.id, AdminRole.ADMIN)
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

            assertEquals(401, get(client, "$base/api/v1/admin/dashboard").statusCode())
            val invalidSession = client.send(
                HttpRequest.newBuilder().uri(URI("$base/api/v1/admin/dashboard"))
                    .header("Authorization", "Bearer invalid-session-token").GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(401, invalidSession.statusCode(), invalidSession.body())

            val memberDashboard = client.send(
                HttpRequest.newBuilder().uri(URI("$base/api/v1/admin/dashboard"))
                    .header("Authorization", "Bearer ${member.token}").GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(403, memberDashboard.statusCode(), memberDashboard.body())

            val founderDashboard = client.send(
                HttpRequest.newBuilder().uri(URI("$base/api/v1/admin/dashboard"))
                    .header("Authorization", "Bearer ${founder.token}").GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(200, founderDashboard.statusCode(), founderDashboard.body())

            val memberRoles = client.send(
                HttpRequest.newBuilder().uri(URI("$base/api/v1/admin/roles"))
                    .header("Authorization", "Bearer ${member.token}").GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(403, memberRoles.statusCode(), memberRoles.body())

            // ADMIN may manage ordinary roles, but must not grant BACKEND_ADMIN without backend-control permission.
            val forbiddenBackendPromotion = client.send(
                HttpRequest.newBuilder().uri(URI("$base/api/v1/admin/users/${member.user.id}/role"))
                    .header("Authorization", "Bearer ${admin.token}")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"role":"BACKEND_ADMIN"}""")).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(403, forbiddenBackendPromotion.statusCode(), forbiddenBackendPromotion.body())
            assertEquals(AdminRole.MEMBER, auth.adminRoles.role(member.user.id), "Forbidden promotion must not mutate persisted role")

            val forbiddenFounderDemotion = client.send(
                HttpRequest.newBuilder().uri(URI("$base/api/v1/admin/users/${founder.user.id}/role"))
                    .header("Authorization", "Bearer ${admin.token}")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"role":"MEMBER"}""")).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(403, forbiddenFounderDemotion.statusCode(), forbiddenFounderDemotion.body())
            assertEquals(AdminRole.FOUNDER, auth.adminRoles.role(founder.user.id), "Forbidden demotion must not mutate elevated role")

            val founderRoles = client.send(
                HttpRequest.newBuilder().uri(URI("$base/api/v1/admin/roles"))
                    .header("Authorization", "Bearer ${founder.token}").GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(200, founderRoles.statusCode(), founderRoles.body())

            val unsupportedRolesMethod = client.send(
                HttpRequest.newBuilder().uri(URI("$base/api/v1/admin/roles"))
                    .header("Authorization", "Bearer ${founder.token}")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(405, unsupportedRolesMethod.statusCode(), unsupportedRolesMethod.body())

            val unsupportedAnnouncementsMethod = client.send(
                HttpRequest.newBuilder().uri(URI("$base/api/v1/admin/announcements"))
                    .header("Authorization", "Bearer ${founder.token}")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(405, unsupportedAnnouncementsMethod.statusCode(), unsupportedAnnouncementsMethod.body())
            assertEquals("GET, POST", unsupportedAnnouncementsMethod.headers().firstValue("Allow").orElse(""))

            val unsupportedPublishMethod = client.send(
                HttpRequest.newBuilder().uri(URI("$base/api/v1/admin/announcements/test-announcement/publish"))
                    .header("Authorization", "Bearer ${founder.token}")
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(405, unsupportedPublishMethod.statusCode(), unsupportedPublishMethod.body())

            val unsupportedUserRoleMethod = client.send(
                HttpRequest.newBuilder().uri(URI("$base/api/v1/admin/users/${member.user.id}/role"))
                    .header("Authorization", "Bearer ${founder.token}")
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(405, unsupportedUserRoleMethod.statusCode(), unsupportedUserRoleMethod.body())

            val unsupportedUserActionMethod = client.send(
                HttpRequest.newBuilder().uri(URI("$base/api/v1/admin/users/${member.user.id}/actions"))
                    .header("Authorization", "Bearer ${founder.token}")
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(405, unsupportedUserActionMethod.statusCode(), unsupportedUserActionMethod.body())

            val unsupportedReportMethod = client.send(
                HttpRequest.newBuilder().uri(URI("$base/api/v1/admin/reports/test-report"))
                    .header("Authorization", "Bearer ${founder.token}")
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(405, unsupportedReportMethod.statusCode(), unsupportedReportMethod.body())

            val unsupportedAuditLogMethod = client.send(
                HttpRequest.newBuilder().uri(URI("$base/api/v1/admin/audit-log"))
                    .header("Authorization", "Bearer ${founder.token}")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(405, unsupportedAuditLogMethod.statusCode(), unsupportedAuditLogMethod.body())
        } finally {
            server.close()
            Files.walk(temp).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    @Test
    fun adminRouteReturnsUnavailableWhenAuthRuntimeIsMissing() {
        val temp = Files.createTempDirectory("shinigami-admin-no-auth-runtime")
        val catalog = CatalogService(
            repository = FileCatalogRepository(temp.resolve("catalog/episodes.json")),
            extractors = StreamixExtractorRegistry(emptyList())
        )
        val server = StreamixHttpServer(
            service = StreamixService(catalog = catalog),
            catalog = catalog,
            host = "127.0.0.1",
            port = 0
        )
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

        try {
            server.start()
            val response = get(client, "http://127.0.0.1:${server.boundPort()}/api/v1/admin/dashboard")
            assertEquals(503, response.statusCode(), response.body())
        } finally {
            server.close()
            Files.walk(temp).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun memberReadingCatalogRoutesExposeMetadataAndResolveChapterContentWithoutLeakingSources() {
        val temp = Files.createTempDirectory("shinigami-reading-member-routes")
        val auth = streamix.auth.AuthRuntime(temp.resolve("auth"))
        val member = auth.auth.signIn(
            streamix.auth.ExternalIdentity("test", "reading-catalog-member", displayName = "Reading Member")
        )
        var runtimeCalls = 0
        val runtimeProvider = object : streamix.readingcatalog.ReadingRuntimeProvider {
            override val id = "test-reader"

            override suspend fun chapter(
                entry: streamix.readingcatalog.ReadingCatalogEntry,
                chapter: streamix.readingcatalog.ReadingChapterEntry
            ): streamix.readingcatalog.ReadingContent? {
                runtimeCalls++
                return streamix.readingcatalog.ReadingContent(
                    entry.kind,
                    "reading-provider:$id",
                    pageUrls = listOf("https://cdn.reader.example/page-1.jpg", "https://cdn.reader.example/page-2.jpg"),
                    format = "image-pages"
                )
            }
        }
        val reading = streamix.readingcatalog.ReadingCatalogService(
            streamix.readingcatalog.FileReadingCatalogRepository(temp.resolve("reading.json")),
            providerRuntime = streamix.readingcatalog.ReadingProviderRuntimeRace(listOf(runtimeProvider))
        )
        val entry = reading.putManual(
            streamix.readingcatalog.ReadingCatalogEntry(
                id = "ignored",
                kind = streamix.readingcatalog.ReadingKind.MANGA,
                title = "Fixture Manga",
                url = "https://catalog.example/fixture-manga",
                origin = streamix.readingcatalog.ReadingEntryOrigin.MANUAL,
                coverUrl = "https://cdn.catalog.example/cover.jpg",
                chapters = listOf(
                    streamix.readingcatalog.ReadingChapterEntry(
                        id = "chapter-1",
                        number = "1",
                        title = "First Chapter",
                        sources = listOf(
                            streamix.readingcatalog.ReadingChapterSource(
                                id = "source-1",
                                providerId = "test-reader",
                                url = "https://reader.example/chapter/1",
                                type = streamix.readingcatalog.ReadingSourceType.EXTRACTOR,
                                origin = streamix.readingcatalog.ReadingEntryOrigin.MANUAL
                            )
                        )
                    )
                )
            )
        )
        val animeCatalog = CatalogService(
            repository = FileCatalogRepository(temp.resolve("anime-catalog.json")),
            extractors = StreamixExtractorRegistry(emptyList())
        )
        val server = StreamixHttpServer(
            service = StreamixService(catalog = animeCatalog),
            authRuntime = auth,
            catalog = animeCatalog,
            readingCatalog = reading,
            host = "127.0.0.1",
            port = 0
        )
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

        try {
            server.start()
            val base = "http://127.0.0.1:${server.boundPort()}"
            val searchPath = "$base/api/v1/reading/catalog?q=Fixture&kind=MANGA"
            assertEquals(401, get(client, searchPath).statusCode())

            fun getMember(path: String) = client.send(
                HttpRequest.newBuilder().uri(URI(path))
                    .header("Authorization", "Bearer ${member.token}")
                    .timeout(Duration.ofSeconds(3)).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )

            val search = getMember(searchPath)
            assertEquals(200, search.statusCode(), search.body())
            assertTrue(search.body().contains("Fixture Manga"))
            assertTrue(search.body().contains("\"chapterCount\":1"))
            assertFalse(search.body().contains("https://reader.example/chapter/1"))

            val details = getMember("$base/api/v1/reading/items/${entry.id}")
            assertEquals(200, details.statusCode(), details.body())
            assertTrue(details.body().contains("https://cdn.catalog.example/cover.jpg"))
            assertFalse(details.body().contains("https://reader.example/chapter/1"))

            val chapters = getMember("$base/api/v1/reading/items/${entry.id}/chapters")
            assertEquals(200, chapters.statusCode(), chapters.body())
            assertTrue(chapters.body().contains("chapter-1"))
            assertTrue(chapters.body().contains("First Chapter"))
            assertFalse(chapters.body().contains("https://reader.example/chapter/1"))

            val content = getMember("$base/api/v1/reading/items/${entry.id}/chapter-content?chapterId=chapter-1")
            assertEquals(200, content.statusCode(), content.body())
            assertTrue(content.body().contains("https://cdn.reader.example/page-1.jpg"))
            assertTrue(content.body().contains("\"referer\":\"https://reader.example/chapter/1\""))
            assertEquals(1, runtimeCalls)

            val invalidKind = getMember("$base/api/v1/reading/catalog?kind=VIDEO")
            assertEquals(400, invalidKind.statusCode(), invalidKind.body())
            val unsupportedMethod = client.send(
                HttpRequest.newBuilder().uri(URI("$base/api/v1/reading/catalog"))
                    .header("Authorization", "Bearer ${member.token}")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(405, unsupportedMethod.statusCode())
            assertEquals("GET", unsupportedMethod.headers().firstValue("Allow").orElse(""))
        } finally {
            server.close()
            Files.walk(temp).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private fun get(client: HttpClient, url: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder().uri(URI(url)).timeout(Duration.ofSeconds(3)).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        )

    private class FakeAniListGateway : AniListGateway {
        @Volatile var lastSearchPage: Int? = null
        @Volatile var lastAuthorization: String? = null
        @Volatile var executeFailure: String? = null
        @Volatile var searchFailure: String? = null
        @Volatile var searchUpstreamStatus: Int? = null
        @Volatile var detailFailure: String? = null
        val detailCalls = java.util.concurrent.atomic.AtomicInteger()

        override fun search(query: String, page: Int): List<ProviderAnime> {
            searchFailure?.let { throw IllegalStateException(it) }
            searchUpstreamStatus?.let { throw AniListUpstreamException(it) }
            lastSearchPage = page
            return listOf(
                ProviderAnime(
                    providerId = "anilist",
                    id = "21",
                    title = "One Piece",
                    url = "https://anilist.co/anime/21"
                )
            )
        }

        override fun detail(anilistId: Long): ProviderAnime? {
            detailFailure?.let { throw IllegalStateException(it) }
            detailCalls.incrementAndGet()
            return if (anilistId == 21L) {
                ProviderAnime(
                    providerId = "anilist",
                    id = "21",
                    title = "One Piece",
                    url = "https://anilist.co/anime/21"
                )
            } else null
        }

        override fun execute(body: ByteArray, authorization: String?): AniListProxy.Response {
            lastAuthorization = authorization
            executeFailure?.let { throw IllegalStateException(it) }
            return AniListProxy.Response(
                code = 200,
                body = """{"data":{"ok":true}}""".toByteArray(Charsets.UTF_8),
                contentType = "application/json; charset=utf-8"
            )
        }
    }
}
