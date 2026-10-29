package streamix.api

import com.google.gson.Gson
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import streamix.auth.AuthRuntime
import streamix.catalog.CatalogService
import streamix.catalog.EpisodeSource
import streamix.catalog.SourceOrigin
import streamix.catalog.SourceType
import streamix.core.StreamixExtractorRegistry
import streamix.runtime.StreamixService
import streamix.readingcatalog.ReadingCatalogService
import streamix.readingcatalog.ReadingCatalogEntry
import streamix.readingcatalog.ReadingChapterEntry
import streamix.readingcatalog.ReadingKind
import streamix.readingcatalog.ReadingChapterSourceHealthUpdate
import streamix.readingcatalog.ReadingChapterSourceEnabledUpdate
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class StreamixHttpServer(
    private val service: StreamixService,
    private val extractorRegistry: StreamixExtractorRegistry? = null,
    private val authRuntime: AuthRuntime? = null,
    private val catalog: CatalogService? = null,
    private val readingCatalog: ReadingCatalogService? = null,
    private val host: String = "0.0.0.0",
    private val port: Int = 8080,
    private val gson: Gson = Gson(),
    private val aniListProxy: AniListGateway = AniListProxy(),
    private val readingMetadata: ReadingMetadataGateway = AniListReadingMetadataProxy(),
    private val readingMetadata: ReadingMetadataGateway = AniListReadingMetadataProxy(),
    private val externalIdentityVerifier: streamix.auth.ExternalIdentityVerifier? = null,
    private val viewCounts: EpisodeViewCountStore = EpisodeViewCountStore()
) : AutoCloseable {
    private var server: HttpServer? = null
    private var executor: ExecutorService? = null

    fun boundPort(): Int = requireNotNull(server) { "HTTP server is not started" }.address.port

    fun start() {
        check(server == null) { "HTTP server is already started" }
        val http = HttpServer.create(InetSocketAddress(host, port), 0)
        val requestExecutor = Executors.newCachedThreadPool()
        http.executor = requestExecutor
        executor = requestExecutor
        http.createContext(BackendApiContract.HEALTH) { exchange ->
            if (!method(exchange, "GET")) return@createContext
            respond(exchange, 200, mapOf("status" to "ok"))
        }
        http.createContext(BackendApiContract.ANILIST_GRAPHQL) { exchange ->
            if (!method(exchange, "POST")) return@createContext
            val body = readBody(exchange, 2 * 1024 * 1024)
                ?: return@createContext respond(exchange, 413, mapOf("error" to "AniList GraphQL request body is too large"))
            try {
                val authorization = exchange.requestHeaders.getFirst("Authorization")
                val result = aniListProxy.execute(body, authorization)
                val contentType = result.contentType?.takeIf { it.isNotBlank() }
                    ?: "application/json; charset=utf-8"
                val responseHeaders = exchange.responseHeaders
                responseHeaders.set("Content-Type", contentType)
                exchange.sendResponseHeaders(result.code, result.body.size.toLong())
                exchange.responseBody.use { it.write(result.body) }
            } catch (_: Exception) {
                respond(exchange, 502, mapOf("error" to "AniList GraphQL request failed"))
            }
        }

        http.createContext(UserApiContract.ME) { exchange ->
            if (exchange.requestURI.path != UserApiContract.ME) return@createContext respond(exchange, 404, mapOf("error" to "route not found"))
            if (!method(exchange, "GET")) return@createContext
            withUser(exchange) { runtime, user -> respond(exchange, 200, runtime.userService.me(user.id)) }
        }
        http.createContext(UserApiContract.UPDATE_ME) { exchange ->
            if (exchange.requestURI.path != UserApiContract.UPDATE_ME) return@createContext respond(exchange, 404, mapOf("error" to "route not found"))
            if (!method(exchange, "PUT")) return@createContext
            withUser(exchange) { runtime, user ->
                val body = readBody(exchange, 64 * 1024) ?: return@withUser respond(exchange, 413, mapOf("error" to "request body is too large"))
                val request = runCatching { gson.fromJson(body.toString(StandardCharsets.UTF_8), UpdateProfileRequest::class.java) }.getOrNull()
                    ?: return@withUser respond(exchange, 400, mapOf("error" to "invalid profile request"))
                val username = request.username?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@withUser respond(exchange, 400, mapOf("error" to "username is required"))
                respond(exchange, 200, runtime.userService.updateProfile(user.id, username,
                    request.displayName ?: user.displayName, request.bio ?: user.bio, request.avatarUrl ?: user.avatarUrl, request.bannerUrl ?: user.bannerUrl))
            }
        }
        http.createContext(UserApiContract.SEARCH) { exchange ->
            if (exchange.requestURI.path != UserApiContract.SEARCH) return@createContext respond(exchange, 404, mapOf("error" to "route not found"))
            if (!method(exchange, "GET")) return@createContext
            withUser(exchange) { runtime, _ ->
                val q = query(exchange, "q")?.trim().orEmpty()
                if (q.isBlank()) return@withUser respond(exchange, 400, mapOf("error" to "q is required"))
                val page = query(exchange, "page")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                val perPage = query(exchange, "perPage")?.toIntOrNull()?.coerceIn(1, 100) ?: 20
                respond(exchange, 200, runtime.userService.search(q, page, perPage))
            }
        }
        val userRoutePrefix = UserApiContract.PROFILE.substringBefore("{userId}")
        http.createContext(userRoutePrefix) { exchange ->
            val suffix = exchange.requestURI.path.removePrefix(userRoutePrefix)
            when {
                suffix.endsWith("/followers") || suffix.endsWith("/following") -> {
                    if (!method(exchange, "GET")) return@createContext
                    val targetId = suffix.substringBeforeLast("/").takeIf { it.isNotBlank() && !it.contains("/") }?.let(::decode)
                        ?: return@createContext respond(exchange, 400, mapOf("error" to "userId is required"))
                    withUser(exchange) { runtime, viewer ->
                        val page = query(exchange, "page")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                        val perPage = query(exchange, "perPage")?.toIntOrNull()?.coerceIn(1, 100) ?: 20
                        val result = if (suffix.endsWith("/followers"))
                            runtime.userService.followers(targetId, viewer.id, page, perPage)
                        else runtime.userService.following(targetId, viewer.id, page, perPage)
                        respond(exchange, 200, result)
                    }
                }
                suffix.endsWith("/follow") || suffix.endsWith("/block") -> {
                    if (!method(exchange, "POST")) return@createContext
                    val targetId = suffix.substringBeforeLast("/").takeIf { it.isNotBlank() && !it.contains("/") }?.let(::decode)
                        ?: return@createContext respond(exchange, 400, mapOf("error" to "userId is required"))
                    val enabled = query(exchange, "enabled")?.toBooleanStrictOrNull()
                        ?: return@createContext respond(exchange, 400, mapOf("error" to "enabled must be true or false"))
                    withUser(exchange) { runtime, user ->
                        if (targetId == user.id) return@withUser respond(exchange, 400, mapOf("error" to "cannot change relationship with yourself"))
                        val updated = if (suffix.endsWith("/follow")) runtime.users.setRelationship(user.id, targetId, following = enabled)
                            else runtime.users.setRelationship(user.id, targetId, blocked = enabled)
                        respond(exchange, 200, updated)
                    }
                }
                suffix.isNotBlank() && !suffix.contains("/") -> {
                    if (!method(exchange, "GET")) return@createContext
                    withUser(exchange) { runtime, viewer -> respond(exchange, 200, runtime.userService.profile(decode(suffix), viewer.id)) }
                }
                else -> respond(exchange, 404, mapOf("error" to "user route not found"))
            }
        }
        http.createContext(UserLibraryApiContract.LISTS) { exchange ->
            if (exchange.requestURI.path != UserLibraryApiContract.LISTS) return@createContext respond(exchange, 404, mapOf("error" to "route not found"))
            if (!method(exchange, "GET")) return@createContext
            withUser(exchange) { runtime, user ->
                val page = query(exchange, "page")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                val perPage = query(exchange, "perPage")?.toIntOrNull()?.coerceIn(1, 100) ?: 50
                respond(exchange, 200, runtime.libraryService.list(user.id, page, perPage))
            }
        }
        val libraryItemPrefix = UserLibraryApiContract.LIST.substringBefore("{mediaId}")
        http.createContext(libraryItemPrefix) { exchange ->
            val parts = exchange.requestURI.path.removePrefix(libraryItemPrefix).split("/")
            val mediaId = parts.firstOrNull()?.toLongOrNull()
                ?: return@createContext respond(exchange, 400, mapOf("error" to "mediaId must be a positive integer"))
            if (mediaId <= 0L) return@createContext respond(exchange, 400, mapOf("error" to "mediaId must be a positive integer"))
            withUser(exchange) { runtime, user ->
                when {
                    parts.size == 1 && exchange.requestMethod.equals("PUT", true) -> {
                        val body = readBody(exchange, 64 * 1024) ?: return@withUser respond(exchange, 413, mapOf("error" to "request body is too large"))
                        val req = runCatching { gson.fromJson(body.toString(StandardCharsets.UTF_8), UpsertLibraryRequest::class.java) }.getOrNull()
                            ?: return@withUser respond(exchange, 400, mapOf("error" to "invalid library request"))
                        if (req.progress < 0 || (req.score != null && (!req.score.isFinite() || req.score < 0.0)))
                            return@withUser respond(exchange, 400, mapOf("error" to "progress and score must be non-negative"))
                        respond(exchange, 200, runtime.libraryService.upsert(user.id,
                            UserAnimeState(mediaId, req.status, req.progress, req.score, req.isFavorite, req.notes)))
                    }
                    parts.size == 1 && exchange.requestMethod.equals("DELETE", true) -> {
                        runtime.libraryService.delete(user.id, mediaId)
                        respond(exchange, 200, mapOf("deleted" to true))
                    }
                    parts.size == 2 && parts[1] == "status" && exchange.requestMethod.equals("PUT", true) -> {
                        val body = readBody(exchange, 64 * 1024) ?: return@withUser respond(exchange, 413, mapOf("error" to "request body is too large"))
                        val req = runCatching { gson.fromJson(body.toString(StandardCharsets.UTF_8), LibraryStatusRequest::class.java) }.getOrNull()
                            ?: return@withUser respond(exchange, 400, mapOf("error" to "invalid status request"))
                        val current = runtime.libraryService.find(user.id, mediaId) ?: UserAnimeState(mediaId)
                        respond(exchange, 200, runtime.libraryService.upsert(user.id, current.copy(status = req.status)))
                    }
                    parts.size == 2 && parts[1] == "progress" && exchange.requestMethod.equals("PUT", true) -> {
                        val body = readBody(exchange, 64 * 1024) ?: return@withUser respond(exchange, 413, mapOf("error" to "request body is too large"))
                        val req = runCatching { gson.fromJson(body.toString(StandardCharsets.UTF_8), LibraryProgressRequest::class.java) }.getOrNull()
                            ?: return@withUser respond(exchange, 400, mapOf("error" to "invalid progress request"))
                        val progress = req.progress
                            ?: return@withUser respond(exchange, 400, mapOf("error" to "progress is required"))
                        if (progress < 0) return@withUser respond(exchange, 400, mapOf("error" to "progress must be non-negative"))
                        val current = runtime.libraryService.find(user.id, mediaId) ?: UserAnimeState(mediaId)
                        respond(exchange, 200, runtime.libraryService.upsert(user.id, current.copy(progress = progress)))
                    }
                    parts.size == 2 && parts[1] == "score" && exchange.requestMethod.equals("PUT", true) -> {
                        val body = readBody(exchange, 64 * 1024) ?: return@withUser respond(exchange, 413, mapOf("error" to "request body is too large"))
                        val req = runCatching { gson.fromJson(body.toString(StandardCharsets.UTF_8), LibraryScoreRequest::class.java) }.getOrNull()
                            ?: return@withUser respond(exchange, 400, mapOf("error" to "invalid score request"))
                        if (req.score != null && (!req.score.isFinite() || req.score < 0.0))
                            return@withUser respond(exchange, 400, mapOf("error" to "score must be a finite non-negative number"))
                        val current = runtime.libraryService.find(user.id, mediaId) ?: UserAnimeState(mediaId)
                        respond(exchange, 200, runtime.libraryService.upsert(user.id, current.copy(score = req.score)))
                    }
                    parts.size == 1 -> methodNotAllowed(exchange, "PUT, DELETE")
                    parts.size == 2 && parts[1] in setOf("status", "progress", "score") ->
                        methodNotAllowed(exchange, "PUT")
                    else -> respond(exchange, 404, mapOf("error" to "library route not found"))
                }
            }
        }

        http.createContext(NotificationApiContract.NOTIFICATIONS) { exchange ->
            if (exchange.requestURI.path != NotificationApiContract.NOTIFICATIONS) return@createContext respond(exchange, 404, mapOf("error" to "route not found"))
            if (!method(exchange, "GET")) return@createContext
            withUser(exchange) { runtime, user ->
                val page = query(exchange, "page")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                val perPage = query(exchange, "perPage")?.toIntOrNull()?.coerceIn(1, 100) ?: 30
                respond(exchange, 200, runtime.notificationService.list(user.id, page, perPage))
            }
        }
        http.createContext(NotificationApiContract.UNREAD_COUNT) { exchange ->
            if (exchange.requestURI.path != NotificationApiContract.UNREAD_COUNT) return@createContext respond(exchange, 404, mapOf("error" to "route not found"))
            if (!method(exchange, "GET")) return@createContext
            withUser(exchange) { runtime, user ->
                respond(exchange, 200, mapOf("count" to runtime.notificationService.unreadCount(user.id)))
            }
        }
        http.createContext(NotificationApiContract.READ_ALL) { exchange ->
            if (exchange.requestURI.path != NotificationApiContract.READ_ALL) return@createContext respond(exchange, 404, mapOf("error" to "route not found"))
            if (!method(exchange, "POST")) return@createContext
            withUser(exchange) { runtime, user ->
                respond(exchange, 200, mapOf("marked" to runtime.notificationService.markAllRead(user.id)))
            }
        }
        http.createContext(NotificationApiContract.READ.substringBefore("{notificationId}")) { exchange ->
            if (!method(exchange, "POST")) return@createContext
            val suffix = exchange.requestURI.path.removePrefix(NotificationApiContract.READ.substringBefore("{notificationId}"))
            val notificationId = suffix.removeSuffix("/read").takeIf { suffix.endsWith("/read") && it.isNotBlank() && !it.contains("/") }
                ?.let(::decode) ?: return@createContext respond(exchange, 404, mapOf("error" to "notification route not found"))
            withUser(exchange) { runtime, user ->
                val updated = runtime.notificationService.markRead(user.id, notificationId)
                if (!updated) respond(exchange, 404, mapOf("error" to "notification not found"))
                else respond(exchange, 200, mapOf("updated" to true))
            }
        }
        http.createContext(ChatApiContract.GLOBAL_MESSAGES) { exchange ->
            if (exchange.requestURI.path != ChatApiContract.GLOBAL_MESSAGES) return@createContext respond(exchange, 404, mapOf("error" to "route not found"))
            withUser(exchange) { runtime, user ->
                when (exchange.requestMethod.uppercase()) {
                    "GET" -> {
                        val page = query(exchange, "page")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                        val perPage = query(exchange, "perPage")?.toIntOrNull()?.coerceIn(1, 100) ?: 30
                        respond(exchange, 200, runtime.chatService.global(user.id, page, perPage))
                    }
                    "POST" -> {
                        val body = readBody(exchange, 64 * 1024) ?: return@withUser respond(exchange, 413, mapOf("error" to "request body is too large"))
                        val request = runCatching { gson.fromJson(body.toString(StandardCharsets.UTF_8), SendChatMessageRequest::class.java) }.getOrNull()
                            ?: return@withUser respond(exchange, 400, mapOf("error" to "invalid chat message"))
                        val content = request.content?.trim().orEmpty()
                        if (content.isBlank() || content.length > 4000) return@withUser respond(exchange, 400, mapOf("error" to "content must contain 1 to 4000 characters"))
                        respond(exchange, 201, runtime.chatService.sendGlobal(user.id, content))
                    }
                    else -> methodNotAllowed(exchange, "GET, POST")
                }
            }
        }
        val communityChatPrefix = ChatApiContract.COMMUNITY_MESSAGES.substringBefore("{mediaId}")
        http.createContext(communityChatPrefix) { exchange -> handleCommunityChatRoute(exchange, communityChatPrefix) }
        // Keep the old path as a compatibility alias for already-installed clients.
        http.createContext("/api/v1/chat/anime/") { exchange ->
            handleCommunityChatRoute(exchange, "/api/v1/chat/anime/")
        }
        http.createContext(ChatApiContract.MESSAGE_UNREAD_COUNT) { exchange ->
            if (exchange.requestURI.path != ChatApiContract.MESSAGE_UNREAD_COUNT) return@createContext respond(exchange, 404, mapOf("error" to "route not found"))
            if (!method(exchange, "GET")) return@createContext
            withUser(exchange) { runtime, user ->
                respond(exchange, 200, mapOf("count" to runtime.chatService.unreadCount(user.id)))
            }
        }
        http.createContext(ChatApiContract.MESSAGES) { exchange ->
            if (exchange.requestURI.path != ChatApiContract.MESSAGES) return@createContext respond(exchange, 404, mapOf("error" to "route not found"))
            if (!method(exchange, "GET")) return@createContext
            withUser(exchange) { runtime, user ->
                val page = query(exchange, "page")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                val perPage = query(exchange, "perPage")?.toIntOrNull()?.coerceIn(1, 100) ?: 30
                respond(exchange, 200, runtime.chatService.conversations(user.id, page, perPage))
            }
        }
        val directMessagePrefix = ChatApiContract.MESSAGE_THREAD.substringBefore("{userId}")
        http.createContext(directMessagePrefix) { exchange ->
            val suffix = exchange.requestURI.path.removePrefix(directMessagePrefix)
            withUser(exchange) { runtime, user ->
                when {
                    suffix == "unread-count" -> {
                        if (!method(exchange, "GET")) return@withUser
                        respond(exchange, 200, mapOf("count" to runtime.chatService.unreadCount(user.id)))
                    }
                    suffix.endsWith("/read") -> {
                        if (!method(exchange, "POST")) return@withUser
                        val otherId = suffix.removeSuffix("/read").takeIf { it.isNotBlank() && !it.contains("/") }?.let(::decode)
                            ?: return@withUser respond(exchange, 400, mapOf("error" to "userId is required"))
                        if (otherId == user.id) return@withUser respond(exchange, 400, mapOf("error" to "cannot mark own thread as read"))
                        respond(exchange, 200, mapOf("marked" to runtime.chatService.markRead(user.id, otherId)))
                    }
                    suffix.isNotBlank() && !suffix.contains("/") -> {
                        val otherId = decode(suffix)
                        when (exchange.requestMethod.uppercase()) {
                            "GET" -> {
                                val page = query(exchange, "page")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                                val perPage = query(exchange, "perPage")?.toIntOrNull()?.coerceIn(1, 100) ?: 30
                                respond(exchange, 200, runtime.chatService.messages(user.id, otherId, page, perPage))
                            }
                            "POST" -> {
                                val body = readBody(exchange, 64 * 1024) ?: return@withUser respond(exchange, 413, mapOf("error" to "request body is too large"))
                                val request = runCatching { gson.fromJson(body.toString(StandardCharsets.UTF_8), SendMessageRequest::class.java) }.getOrNull()
                                    ?: return@withUser respond(exchange, 400, mapOf("error" to "invalid message request"))
                                val content = request.content?.trim().orEmpty()
                                if (content.isBlank() || content.length > 4000) return@withUser respond(exchange, 400, mapOf("error" to "content must contain 1 to 4000 characters"))
                                respond(exchange, 201, runtime.chatService.sendMessage(user.id, otherId, content))
                            }
                            else -> methodNotAllowed(exchange, "GET, POST")
                        }
                    }
                    else -> respond(exchange, 404, mapOf("error" to "message route not found"))
                }
            }
        }

        http.createContext(SocialApiContract.FEED) { exchange ->
            if (exchange.requestURI.path != SocialApiContract.FEED) return@createContext respond(exchange, 404, mapOf("error" to "route not found"))
            if (!method(exchange, "GET")) return@createContext
            withUser(exchange) { runtime, user ->
                val page = query(exchange, "page")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                val perPage = query(exchange, "perPage")?.toIntOrNull()?.coerceIn(1, 100) ?: 20
                respond(exchange, 200, runtime.socialService.feed(user.id, page, perPage))
            }
        }
        http.createContext(SocialApiContract.ACTIVITIES) { exchange ->
            if (exchange.requestURI.path != SocialApiContract.ACTIVITIES) return@createContext respond(exchange, 404, mapOf("error" to "route not found"))
            withUser(exchange) { runtime, user ->
                when (exchange.requestMethod.uppercase()) {
                    "GET" -> {
                        val page = query(exchange, "page")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                        val perPage = query(exchange, "perPage")?.toIntOrNull()?.coerceIn(1, 100) ?: 20
                        respond(exchange, 200, runtime.socialService.activities(user.id, page, perPage))
                    }
                    "POST" -> {
                        val body = readBody(exchange, 64 * 1024) ?: return@withUser respond(exchange, 413, mapOf("error" to "request body is too large"))
                        val request = runCatching { gson.fromJson(body.toString(StandardCharsets.UTF_8), CreateActivityRequest::class.java) }.getOrNull()
                            ?: return@withUser respond(exchange, 400, mapOf("error" to "invalid activity request"))
                        val type = request.type?.trim()?.takeIf { it.isNotBlank() && it.length <= 40 }
                            ?: return@withUser respond(exchange, 400, mapOf("error" to "activity type is required"))
                        val text = request.text?.trim()
                        if (text != null && text.length > 5000) return@withUser respond(exchange, 400, mapOf("error" to "activity text is too long"))
                        if (request.mediaId != null && request.mediaId <= 0L) return@withUser respond(exchange, 400, mapOf("error" to "mediaId must be positive"))
                        respond(exchange, 201, runtime.socialService.createActivity(user.id, type, text, request.mediaId, request.mediaTitle?.trim()))
                    }
                    else -> methodNotAllowed(exchange, "GET, POST")
                }
            }
        }
        val socialActivityPrefix = SocialApiContract.ACTIVITY.substringBefore("{activityId}")
        http.createContext(socialActivityPrefix) { exchange ->
            val parts = exchange.requestURI.path.removePrefix(socialActivityPrefix).split("/")
            val activityId = parts.firstOrNull()?.takeIf { it.isNotBlank() && it.length <= 200 }?.let(::decode)
                ?: return@createContext respond(exchange, 400, mapOf("error" to "activityId is required"))
            // Reject unknown path shapes before authentication; authentication must not
            // turn an invalid route into a misleading 401 response.
            if (parts.size > 2 || (parts.size == 2 && parts[1] !in setOf("replies", "like", "subscribe"))) {
                return@createContext respond(exchange, 404, mapOf("error" to "activity route not found"))
            }
            withUser(exchange) { runtime, user ->
                when {
                    parts.size == 1 && exchange.requestMethod.equals("GET", true) -> {
                        val item = runtime.socialService.activity(activityId, user.id)
                        if (item == null) respond(exchange, 404, mapOf("error" to "activity not found")) else respond(exchange, 200, item)
                    }
                    parts.size == 1 && exchange.requestMethod.equals("DELETE", true) -> {
                        val deleted = runtime.socialService.deleteActivity(activityId, user.id)
                        if (deleted) respond(exchange, 200, mapOf("deleted" to true)) else respond(exchange, 404, mapOf("error" to "activity not found"))
                    }
                    parts.size == 2 && parts[1] == "replies" && exchange.requestMethod.equals("GET", true) -> {
                        if (runtime.socialService.activity(activityId, user.id) == null) {
                            return@withUser respond(exchange, 404, mapOf("error" to "activity not found"))
                        }
                        val page = query(exchange, "page")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                        val perPage = query(exchange, "perPage")?.toIntOrNull()?.coerceIn(1, 100) ?: 20
                        respond(exchange, 200, runtime.socialService.replies(activityId, user.id, page, perPage))
                    }
                    parts.size == 2 && parts[1] == "replies" && exchange.requestMethod.equals("POST", true) -> {
                        val body = readBody(exchange, 64 * 1024) ?: return@withUser respond(exchange, 413, mapOf("error" to "request body is too large"))
                        val request = runCatching { gson.fromJson(body.toString(StandardCharsets.UTF_8), CreateReplyRequest::class.java) }.getOrNull()
                            ?: return@withUser respond(exchange, 400, mapOf("error" to "invalid reply request"))
                        val content = request.text?.trim().orEmpty()
                        if (content.isBlank() || content.length > 5000) return@withUser respond(exchange, 400, mapOf("error" to "reply text must contain 1 to 5000 characters"))
                        if (runtime.socialService.activity(activityId, user.id) == null) {
                            return@withUser respond(exchange, 404, mapOf("error" to "activity not found"))
                        }
                        respond(exchange, 201, runtime.socialService.createReply(activityId, user.id, content))
                    }
                    parts.size == 2 && parts[1] == "like" && exchange.requestMethod.equals("POST", true) -> {
                        val result = runtime.socialService.likeActivity(activityId, user.id)
                        if (result == null) respond(exchange, 404, mapOf("error" to "activity not found")) else respond(exchange, 200, result)
                    }
                    parts.size == 2 && parts[1] == "subscribe" && exchange.requestMethod.equals("POST", true) -> {
                        val result = runtime.socialService.subscribeActivity(activityId, user.id)
                        if (result == null) respond(exchange, 404, mapOf("error" to "activity not found")) else respond(exchange, 200, result)
                    }
                    parts.size == 1 -> methodNotAllowed(exchange, "GET, DELETE")
                    parts.size == 2 && parts[1] == "replies" -> methodNotAllowed(exchange, "GET, POST")
                    parts.size == 2 && parts[1] in setOf("like", "subscribe") -> methodNotAllowed(exchange, "POST")
                    else -> respond(exchange, 404, mapOf("error" to "activity route not found"))
                }
            }
        }
        http.createContext(SocialApiContract.COMMENTS) { exchange ->
            if (exchange.requestURI.path != SocialApiContract.COMMENTS) return@createContext respond(exchange, 404, mapOf("error" to "route not found"))
            withUser(exchange) { runtime, user ->
                when (exchange.requestMethod.uppercase()) {
                    "GET" -> {
                        val mediaId = query(exchange, "mediaId")?.toLongOrNull()?.takeIf { it > 0L }
                            ?: return@withUser respond(exchange, 400, mapOf("error" to "mediaId must be a positive integer"))
                        val page = query(exchange, "page")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                        val perPage = query(exchange, "perPage")?.toIntOrNull()?.coerceIn(1, 100) ?: 20
                        respond(exchange, 200, runtime.socialService.comments(mediaId, user.id, query(exchange, "parentCommentId"), page, perPage))
                    }
                    "POST" -> {
                        val body = readBody(exchange, 64 * 1024) ?: return@withUser respond(exchange, 413, mapOf("error" to "request body is too large"))
                        val request = runCatching { gson.fromJson(body.toString(StandardCharsets.UTF_8), CreateCommentRequest::class.java) }.getOrNull()
                            ?: return@withUser respond(exchange, 400, mapOf("error" to "invalid comment request"))
                        val content = request.content?.trim().orEmpty()
                        if (request.mediaId <= 0L) return@withUser respond(exchange, 400, mapOf("error" to "mediaId must be positive"))
                        if (content.isBlank() || content.length > 5000) return@withUser respond(exchange, 400, mapOf("error" to "comment content must contain 1 to 5000 characters"))
                        val parentCommentId = request.parentCommentId?.trim()?.takeIf { it.isNotEmpty() }
                        if (parentCommentId != null) {
                            val parent = runtime.socialService.comment(parentCommentId, user.id)
                                ?: return@withUser respond(exchange, 404, mapOf("error" to "parent comment not found"))
                            if (parent.mediaId != request.mediaId)
                                return@withUser respond(exchange, 400, mapOf("error" to "parent comment belongs to a different anime"))
                        }
                        respond(exchange, 201, runtime.socialService.createComment(request.mediaId, user.id, content, parentCommentId, request.tag))
                    }
                    else -> methodNotAllowed(exchange, "GET, POST")
                }
            }
        }
        http.createContext(SocialApiContract.GIVEAWAYS) { exchange ->
            val path = exchange.requestURI.path
            withUser(exchange) { runtime, user ->
                val suffix = path.removePrefix(SocialApiContract.GIVEAWAYS).trim('/')
                when {
                    suffix.isEmpty() && exchange.requestMethod.equals("POST", true) -> {
                        val body = readBody(exchange, 64 * 1024)
                            ?: return@withUser respond(exchange, 413, mapOf("error" to "request body is too large"))
                        val request = runCatching {
                            gson.fromJson(body.toString(StandardCharsets.UTF_8), streamix.giveaway.CreateGiveawayRequest::class.java)
                        }.getOrNull() ?: return@withUser respond(exchange, 400, mapOf("error" to "invalid giveaway request"))
                        val created = runCatching { runtime.giveawayService.create(user.id, request) }
                            .getOrElse { error ->
                                return@withUser respond(exchange, 400, mapOf("error" to (error.message ?: "invalid giveaway request")))
                            }
                        respond(exchange, 201, created)
                    }
                    suffix.isEmpty() && exchange.requestMethod.equals("GET", true) -> {
                        val mediaId = query(exchange, "mediaId")?.toLongOrNull()?.takeIf { it > 0 }
                            ?: return@withUser respond(exchange, 400, mapOf("error" to "mediaId must be a positive integer"))
                        respond(exchange, 200, mapOf("items" to runtime.giveawayService.list(mediaId)))
                    }
                    suffix.endsWith("/claim") && exchange.requestMethod.equals("POST", true) -> {
                        val giveawayId = suffix.removeSuffix("/claim").trim('/')
                            .takeIf { it.isNotBlank() && it.length <= 100 }
                            ?: return@withUser respond(exchange, 400, mapOf("error" to "giveawayId is required"))
                        val result = runCatching { runtime.giveawayService.claim(decode(giveawayId), user.id) }
                            .getOrElse { error ->
                                val message = error.message ?: "giveaway claim failed"
                                val status = if (message == "giveaway not found") 404 else 409
                                return@withUser respond(exchange, status, mapOf("error" to message))
                            }
                        respond(exchange, 200, result)
                    }
                    suffix == "premium-status" && exchange.requestMethod.equals("GET", true) -> {
                        respond(exchange, 200, runtime.giveawayService.premiumStatus(user.id))
                    }
                    else -> methodNotAllowed(exchange, "GET, POST")
                }
            }
        }
        val socialCommentPrefix = SocialApiContract.COMMENT.substringBefore("{commentId}")
        http.createContext(socialCommentPrefix) { exchange ->
            val parts = exchange.requestURI.path.removePrefix(socialCommentPrefix).split("/")
            val commentId = parts.firstOrNull()?.takeIf { it.isNotBlank() && it.length <= 200 }?.let(::decode)
                ?: return@createContext respond(exchange, 400, mapOf("error" to "commentId is required"))
            // Reject unknown path shapes before authentication; authentication must not
            // turn an invalid route into a misleading 401 response.
            if (parts.size > 2 || (parts.size == 2 && parts[1] !in setOf("replies", "vote"))) {
                return@createContext respond(exchange, 404, mapOf("error" to "comment route not found"))
            }
            withUser(exchange) { runtime, user ->
                when {
                    parts.size == 1 && exchange.requestMethod.equals("GET", true) -> {
                        val comment = runtime.socialService.comment(commentId, user.id)
                        if (comment == null) respond(exchange, 404, mapOf("error" to "comment not found")) else respond(exchange, 200, comment)
                    }
                    parts.size == 1 && exchange.requestMethod.equals("PUT", true) -> {
                        val body = readBody(exchange, 64 * 1024) ?: return@withUser respond(exchange, 413, mapOf("error" to "request body is too large"))
                        val request = runCatching { gson.fromJson(body.toString(StandardCharsets.UTF_8), EditCommentRequest::class.java) }.getOrNull()
                            ?: return@withUser respond(exchange, 400, mapOf("error" to "invalid comment request"))
                        val content = request.content?.trim().orEmpty()
                        if (content.isBlank() || content.length > 5000) return@withUser respond(exchange, 400, mapOf("error" to "comment content must contain 1 to 5000 characters"))
                        val updated = runtime.socialService.editComment(commentId, user.id, content)
                        if (updated == null) respond(exchange, 404, mapOf("error" to "comment not found")) else respond(exchange, 200, updated)
                    }
                    parts.size == 1 && exchange.requestMethod.equals("DELETE", true) -> {
                        val deleted = runtime.socialService.deleteComment(commentId, user.id)
                        if (deleted) respond(exchange, 200, mapOf("deleted" to true)) else respond(exchange, 404, mapOf("error" to "comment not found"))
                    }
                    parts.size == 2 && parts[1] == "replies" && exchange.requestMethod.equals("GET", true) -> {
                        if (runtime.socialService.comment(commentId, user.id) == null) {
                            return@withUser respond(exchange, 404, mapOf("error" to "comment not found"))
                        }
                        val page = query(exchange, "page")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                        val perPage = query(exchange, "perPage")?.toIntOrNull()?.coerceIn(1, 100) ?: 20
                        respond(exchange, 200, runtime.socialService.commentReplies(commentId, user.id, page, perPage))
                    }
                    parts.size == 2 && parts[1] == "vote" && exchange.requestMethod.equals("POST", true) -> {
                        val body = readBody(exchange, 64 * 1024) ?: return@withUser respond(exchange, 413, mapOf("error" to "request body is too large"))
                        val request = runCatching { gson.fromJson(body.toString(StandardCharsets.UTF_8), VoteCommentRequest::class.java) }.getOrNull()
                            ?: return@withUser respond(exchange, 400, mapOf("error" to "invalid vote request"))
                        if (request.vote != null && request.vote !in setOf(-1, 1)) return@withUser respond(exchange, 400, mapOf("error" to "vote must be -1, 1, or null"))
                        val result = runtime.socialService.voteComment(commentId, user.id, request.vote)
                        if (result == null) respond(exchange, 404, mapOf("error" to "comment not found")) else respond(exchange, 200, result)
                    }
                    parts.size == 1 -> methodNotAllowed(exchange, "GET, PUT, DELETE")
                    parts.size == 2 && parts[1] == "replies" -> methodNotAllowed(exchange, "GET")
                    parts.size == 2 && parts[1] == "vote" -> methodNotAllowed(exchange, "POST")
                    else -> respond(exchange, 404, mapOf("error" to "comment route not found"))
                }
            }
        }
        http.createContext(SocialApiContract.NOTIFICATIONS) { exchange ->
            if (exchange.requestURI.path != SocialApiContract.NOTIFICATIONS) return@createContext respond(exchange, 404, mapOf("error" to "route not found"))
            if (!method(exchange, "GET")) return@createContext
            withUser(exchange) { runtime, user ->
                val page = query(exchange, "page")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                val perPage = query(exchange, "perPage")?.toIntOrNull()?.coerceIn(1, 100) ?: 20
                respond(exchange, 200, runtime.socialService.notifications(user.id, page, perPage))
            }
        }
        http.createContext(SocialApiContract.NOTIFICATION_COUNT) { exchange ->
            if (exchange.requestURI.path != SocialApiContract.NOTIFICATION_COUNT) return@createContext respond(exchange, 404, mapOf("error" to "route not found"))
            if (!method(exchange, "GET")) return@createContext
            withUser(exchange) { runtime, user -> respond(exchange, 200, mapOf("count" to runtime.socialService.unreadNotificationCount(user.id))) }
        }
        val socialNotificationReadPrefix =
            SocialApiContract.NOTIFICATION_READ.substringBefore("{notificationId}")
        http.createContext(socialNotificationReadPrefix) { exchange ->
            if (!method(exchange, "POST")) return@createContext
            val suffix = exchange.requestURI.path.removePrefix(socialNotificationReadPrefix)
            val id = suffix.removeSuffix("/read").takeIf { suffix.endsWith("/read") && it.isNotBlank() && !it.contains("/") }?.let(::decode)
                ?: return@createContext respond(exchange, 404, mapOf("error" to "notification route not found"))
            withUser(exchange) { runtime, user ->
                if (runtime.socialService.markNotificationRead(id, user.id)) respond(exchange, 200, mapOf("updated" to true))
                else respond(exchange, 404, mapOf("error" to "notification not found"))
            }
        }
        http.createContext(SocialApiContract.REPORTS) { exchange ->
            if (exchange.requestURI.path != SocialApiContract.REPORTS) return@createContext respond(exchange, 404, mapOf("error" to "route not found"))
            if (!method(exchange, "POST")) return@createContext
            withUser(exchange) { runtime, user ->
                val body = readBody(exchange, 64 * 1024) ?: return@withUser respond(exchange, 413, mapOf("error" to "request body is too large"))
                val request = runCatching { gson.fromJson(body.toString(StandardCharsets.UTF_8), CreateSocialReportRequest::class.java) }.getOrNull()
                    ?: return@withUser respond(exchange, 400, mapOf("error" to "invalid report request"))
                val type = runCatching { ReportType.valueOf(request.type?.trim()?.uppercase().orEmpty()) }.getOrNull()
                    ?: return@withUser respond(exchange, 400, mapOf("error" to "unsupported report type"))
                val description = request.description?.trim().orEmpty()
                if (description.isBlank() || description.length > 2000) return@withUser respond(exchange, 400, mapOf("error" to "description must contain 1 to 2000 characters"))
                val targetUserId = request.targetUserId?.trim()?.takeIf { it.isNotEmpty() }
                val targetContentId = request.targetContentId?.trim()?.takeIf { it.isNotEmpty() }
                if (targetUserId == null && targetContentId == null) return@withUser respond(exchange, 400, mapOf("error" to "report target is required"))
                if (targetUserId != null && runtime.users.findById(targetUserId) == null) return@withUser respond(exchange, 404, mapOf("error" to "target user not found"))
                val report = runtime.reports.create(AdminReport(
                    id = UUID.randomUUID().toString(), reporterId = user.id, targetUserId = targetUserId,
                    targetContentId = targetContentId, type = type, description = description, createdAt = Instant.now().toString()
                ))
                respond(exchange, 201, mapOf("id" to report.id, "status" to report.status.name))
            }
        }

        http.createContext(UserApiContract.SESSION) { exchange ->
            if (exchange.requestURI.path != UserApiContract.SESSION) {
                return@createContext respond(exchange, 404, mapOf("error" to "route not found"))
            }
            when {
                exchange.requestMethod.equals("POST", ignoreCase = true) -> {
                    val runtime = authRuntime
                        ?: return@createContext respond(exchange, 503, mapOf("error" to "authentication backend is not configured"))
                    val verifier = externalIdentityVerifier
                        ?: return@createContext respond(exchange, 503, mapOf("error" to "external identity verifier is not configured"))
                    val body = readBody(exchange, 64 * 1024)
                        ?: return@createContext respond(exchange, 413, mapOf("error" to "request body is too large"))
                    val request = runCatching {
                        gson.fromJson(body.toString(StandardCharsets.UTF_8), AuthSessionRequest::class.java)
                    }.getOrNull() ?: return@createContext respond(exchange, 400, mapOf("error" to "invalid authentication request"))
                    val provider = request.provider?.trim()?.takeIf { it.isNotEmpty() }
                        ?: return@createContext respond(exchange, 400, mapOf("error" to "provider is required"))
                    val credential = request.credential?.trim()?.takeIf { it.isNotEmpty() }
                        ?: return@createContext respond(exchange, 400, mapOf("error" to "credential is required"))
                    val identity = try {
                        verifier.verify(provider, credential)
                    } catch (_: Exception) {
                        // The adapter must return null for rejected credentials. Exceptions
                        // indicate an adapter/upstream failure, even if the exception type is
                        // IllegalArgumentException.
                        return@createContext respond(exchange, 500, mapOf("error" to "internal server error"))
                    } ?: return@createContext respond(exchange, 401, mapOf("error" to "invalid authentication credential"))
                    if (identity.provider != provider || identity.subject.isBlank()) {
                        // A trusted adapter must return a complete identity for the provider
                        // that was requested; never silently sign in under another provider.
                        return@createContext respond(exchange, 500, mapOf("error" to "identity verifier returned an invalid identity"))
                    }
                    val session = try {
                        // Keep identity verification failures separate from persistence failures:
                        // an invalid trusted identity/configuration must not masquerade as bad credentials.
                        runtime.auth.signIn(identity)
                    } catch (_: Exception) {
                        return@createContext respond(exchange, 500, mapOf("error" to "internal server error"))
                    }
                    respond(exchange, 200, session)
                }
                exchange.requestMethod.equals("GET", ignoreCase = true) -> {
                    val runtime = authRuntime
                        ?: return@createContext respond(exchange, 503, mapOf("error" to "authentication backend is not configured"))
                    val token = bearerToken(exchange)
                        ?: return@createContext respond(exchange, 401, mapOf("error" to "authorization token is required"))
                    val session = try {
                        runtime.auth.currentSession(token)
                    } catch (_: Exception) {
                        return@createContext respond(exchange, 500, mapOf("error" to "internal server error"))
                    } ?: return@createContext respond(exchange, 401, mapOf("error" to "session is invalid or expired"))
                    respond(exchange, 200, session)
                }
                else -> methodNotAllowed(exchange, "GET, POST")
            }
        }
        http.createContext(UserApiContract.LOGOUT) { exchange ->
            if (exchange.requestURI.path != UserApiContract.LOGOUT) {
                return@createContext respond(exchange, 404, mapOf("error" to "route not found"))
            }
            if (!method(exchange, "POST")) return@createContext
            val runtime = authRuntime
                ?: return@createContext respond(exchange, 503, mapOf("error" to "authentication backend is not configured"))
            val token = bearerToken(exchange)
                ?: return@createContext respond(exchange, 401, mapOf("error" to "authorization token is required"))
            try {
                runtime.auth.logout(token)
            } catch (_: Exception) {
                return@createContext respond(exchange, 500, mapOf("error" to "internal server error"))
            }
            respond(exchange, 200, mapOf("status" to "ok"))
        }
        http.createContext(AdminApiContract.DASHBOARD) { exchange ->
            if (!method(exchange, "GET")) return@createContext
            withAdminUser(exchange) { runtime, user ->
                respond(exchange, 200, runtime.adminService.dashboard(user.id))
            }
        }
        http.createContext(AdminApiContract.USERS) { exchange ->
            if (!method(exchange, "GET")) return@createContext
            withAdminUser(exchange) { runtime, user ->
                val search = query(exchange, "q").orEmpty()
                val page = query(exchange, "page")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                val perPage = query(exchange, "perPage")?.toIntOrNull()?.coerceIn(1, 100) ?: 30
                respond(exchange, 200, runtime.adminService.users(user.id, search, page, perPage))
            }
        }
        val adminUserPrefix = AdminApiContract.USER.substringBefore("{userId}")
        http.createContext(adminUserPrefix) { exchange ->
            val prefix = adminUserPrefix
            val path = exchange.requestURI.path
            val suffix = path.removePrefix(prefix)
            when {
                suffix.endsWith("/actions") -> {
                    if (!method(exchange, "POST")) return@createContext
                    val targetId = suffix.removeSuffix("/actions").takeIf { it.isNotBlank() && !it.contains("/") }
                        ?.let(::decode)
                        ?: return@createContext respond(exchange, 400, mapOf("error" to "userId is required"))
                    withAdminUser(exchange) { runtime, user ->
                        val body = readBody(exchange, 64 * 1024)
                            ?: return@withAdminUser respond(exchange, 413, mapOf("error" to "request body is too large"))
                        val request = runCatching {
                            gson.fromJson(body.toString(StandardCharsets.UTF_8), AdminUserActionRequest::class.java)
                        }.getOrNull() ?: return@withAdminUser respond(exchange, 400, mapOf("error" to "invalid user action request"))
                        val action = request.action?.trim()?.uppercase().orEmpty()
                        when (action) {
                            "WARN" -> respond(exchange, 200, runtime.adminService.warn(user.id, targetId, request.reason))
                            "ACTIVE", "SUSPENDED", "BANNED" -> {
                                val status = ModerationStatus.valueOf(action)
                                respond(exchange, 200, runtime.adminService.setStatus(user.id, targetId, status, request.reason, request.suspendedUntil))
                            }
                            else -> respond(exchange, 400, mapOf("error" to "unsupported user action"))
                        }
                    }
                }
                suffix.endsWith("/role") -> {
                    if (!method(exchange, "POST")) return@createContext
                    val targetId = suffix.removeSuffix("/role").takeIf { it.isNotBlank() && !it.contains("/") }
                        ?.let(::decode)
                        ?: return@createContext respond(exchange, 400, mapOf("error" to "userId is required"))
                    withAdminUser(exchange) { runtime, user ->
                        val body = readBody(exchange, 64 * 1024)
                            ?: return@withAdminUser respond(exchange, 413, mapOf("error" to "request body is too large"))
                        val request = runCatching {
                            gson.fromJson(body.toString(StandardCharsets.UTF_8), AdminRoleRequest::class.java)
                        }.getOrNull() ?: return@withAdminUser respond(exchange, 400, mapOf("error" to "invalid role request"))
                        val role = runCatching { AdminRole.valueOf(request.role?.trim()?.uppercase().orEmpty()) }.getOrNull()
                            ?: return@withAdminUser respond(exchange, 400, mapOf("error" to "invalid admin role"))
                        respond(exchange, 200, runtime.adminService.setRole(user.id, targetId, role))
                    }
                }
                suffix.isNotBlank() && !suffix.contains("/") -> {
                    if (!method(exchange, "GET")) return@createContext
                    val targetId = decode(suffix)
                    withAdminUser(exchange) { runtime, user ->
                        respond(exchange, 200, runtime.adminService.user(user.id, targetId))
                    }
                }
                else -> respond(exchange, 404, mapOf("error" to "admin user route not found"))
            }
        }
        http.createContext(AdminApiContract.REPORTS) { exchange ->
            if (!method(exchange, "GET")) return@createContext
            withAdminUser(exchange) { runtime, user ->
                val page = query(exchange, "page")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                val perPage = query(exchange, "perPage")?.toIntOrNull()?.coerceIn(1, 100) ?: 30
                respond(exchange, 200, runtime.adminService.reports(user.id, page, perPage))
            }
        }
        val adminReportPrefix = AdminApiContract.REPORT.substringBefore("{reportId}")
        http.createContext(adminReportPrefix) { exchange ->
            if (!method(exchange, "PUT")) return@createContext
            val prefix = adminReportPrefix
            val reportId = exchange.requestURI.path.removePrefix(prefix).takeIf { it.isNotBlank() && !it.contains("/") }
                ?.let(::decode)
                ?: return@createContext respond(exchange, 400, mapOf("error" to "reportId is required"))
            withAdminUser(exchange) { runtime, user ->
                val body = readBody(exchange, 64 * 1024)
                    ?: return@withAdminUser respond(exchange, 413, mapOf("error" to "request body is too large"))
                val request = runCatching {
                    gson.fromJson(body.toString(StandardCharsets.UTF_8), AdminReportUpdateRequest::class.java)
                }.getOrNull() ?: return@withAdminUser respond(exchange, 400, mapOf("error" to "invalid report request"))
                val id = request.id?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@withAdminUser respond(exchange, 400, mapOf("error" to "report id is required"))
                if (id != reportId) return@withAdminUser respond(exchange, 400, mapOf("error" to "report id mismatch"))
                val reporterId = request.reporterId?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@withAdminUser respond(exchange, 400, mapOf("error" to "reporterId is required"))
                val type = request.type?.trim()?.uppercase()?.let { value ->
                    runCatching { ReportType.valueOf(value) }.getOrNull()
                } ?: return@withAdminUser respond(exchange, 400, mapOf("error" to "valid report type is required"))
                val description = request.description?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@withAdminUser respond(exchange, 400, mapOf("error" to "description is required"))
                val status = request.status?.trim()?.uppercase()?.let { value ->
                    runCatching { ReportStatus.valueOf(value) }.getOrNull()
                } ?: return@withAdminUser respond(exchange, 400, mapOf("error" to "valid report status is required"))
                val createdAt = request.createdAt?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@withAdminUser respond(exchange, 400, mapOf("error" to "createdAt is required"))
                val report = AdminReport(
                    id = id,
                    reporterId = reporterId,
                    targetUserId = request.targetUserId?.trim()?.takeIf { it.isNotEmpty() },
                    targetContentId = request.targetContentId?.trim()?.takeIf { it.isNotEmpty() },
                    type = type,
                    description = description,
                    status = status,
                    assignedTo = request.assignedTo?.trim()?.takeIf { it.isNotEmpty() },
                    createdAt = createdAt,
                    resolvedAt = request.resolvedAt?.trim()?.takeIf { it.isNotEmpty() }
                )
                respond(exchange, 200, runtime.adminService.updateReport(user.id, report))
            }
        }
        http.createContext(AdminApiContract.ROLES) { exchange ->
            if (!method(exchange, "GET")) return@createContext
            withAdminUser(exchange) { runtime, user ->
                runtime.adminAccess.require(
                    runtime.users.findById(user.id) ?: error("user not found"),
                    AdminPermission.MANAGE_ROLES
                )
                respond(exchange, 200, AdminRole.values().toList())
            }
        }
        http.createContext(AdminApiContract.AUDIT_LOG) { exchange ->
            if (!method(exchange, "GET")) return@createContext
            withAdminUser(exchange) { runtime, user ->
                val limit = query(exchange, "limit")?.toIntOrNull()?.coerceIn(1, 500) ?: 100
                val operationId = query(exchange, "operationId")
                respond(exchange, 200, runtime.adminService.auditLog(user.id, limit, operationId))
            }
        }
        http.createContext(AdminApiContract.ANNOUNCEMENTS) { exchange ->
            withAdminUser(exchange) { runtime, user ->
                when {
                    exchange.requestMethod.equals("GET", ignoreCase = true) -> {
                        val page = query(exchange, "page")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                        val perPage = query(exchange, "perPage")?.toIntOrNull()?.coerceIn(1, 100) ?: 30
                        respond(exchange, 200, runtime.announcementService.list(user.id, page, perPage))
                    }
                    exchange.requestMethod.equals("POST", ignoreCase = true) -> {
                        val bodyBytes = readBody(exchange, 64 * 1024)
                            ?: return@withAdminUser respond(exchange, 413, mapOf("error" to "request body is too large"))
                        val request = runCatching {
                            gson.fromJson(bodyBytes.toString(StandardCharsets.UTF_8), AdminAnnouncementRequest::class.java)
                        }.getOrNull() ?: return@withAdminUser respond(exchange, 400, mapOf("error" to "invalid announcement request"))
                        val title = request.title?.trim()?.takeIf { it.isNotEmpty() }
                            ?: return@withAdminUser respond(exchange, 400, mapOf("error" to "announcement title is required"))
                        val body = request.body?.trim()?.takeIf { it.isNotEmpty() }
                            ?: return@withAdminUser respond(exchange, 400, mapOf("error" to "announcement body is required"))
                        respond(exchange, 200, runtime.announcementService.create(user.id, title, body, request.imageUrl))
                    }
                    else -> methodNotAllowed(exchange, "GET, POST")
                }
            }
        }
        val adminAnnouncementPrefix = AdminApiContract.ANNOUNCEMENT.substringBefore("{announcementId}")
        http.createContext(adminAnnouncementPrefix) { exchange ->
            val prefix = adminAnnouncementPrefix
            val suffix = exchange.requestURI.path.removePrefix(prefix)
            withAdminUser(exchange) { runtime, user ->
                when {
                    suffix.endsWith("/publish") -> {
                        if (!method(exchange, "POST")) return@withAdminUser
                        val id = suffix.removeSuffix("/publish").takeIf { it.isNotBlank() && !it.contains("/") }
                            ?.let(::decode)
                            ?: return@withAdminUser respond(exchange, 400, mapOf("error" to "announcementId is required"))
                        respond(exchange, 200, runtime.announcementService.publish(user.id, id))
                    }
                    suffix.isNotBlank() && !suffix.contains("/") -> {
                        if (!method(exchange, "DELETE")) return@withAdminUser
                        respond(exchange, 200, mapOf("deleted" to runtime.announcementService.delete(user.id, decode(suffix))))
                    }
                    else -> respond(exchange, 404, mapOf("error" to "announcement route not found"))
                }
            }
        }
        // AniList reading metadata is public and remains separate from the manual source catalog.
        http.createContext(BackendApiContract.READING_METADATA_SEARCH) { exchange ->
            if (!method(exchange, "GET")) return@createContext
            val q = query(exchange, "q")?.trim().orEmpty()
            if (q.isBlank()) return@createContext respond(exchange, 400, mapOf("error" to "q is required"))
            val rawKind = query(exchange, "kind")?.trim()?.uppercase(java.util.Locale.ROOT)
                ?: return@createContext respond(exchange, 400, mapOf("error" to "kind must be MANGA, MANHWA, MANHUA, or NOVEL"))
            val kind = runCatching { ReadingMetadataKind.valueOf(rawKind) }.getOrNull()
                ?: return@createContext respond(exchange, 400, mapOf("error" to "kind must be MANGA, MANHWA, MANHUA, or NOVEL"))
            val page = query(exchange, "page")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            try {
                respond(exchange, 200, mapOf("items" to readingMetadata.search(q, kind, page), "page" to page))
            } catch (_: AniListReadingMetadataException) {
                respond(exchange, 502, mapOf("error" to "AniList reading metadata request failed"))
            }
        }
        http.createContext(BackendApiContract.READING_METADATA_DETAIL.substringBefore("{id}")) { exchange ->
            if (!method(exchange, "GET")) return@createContext
            val id = exchange.requestURI.path.removePrefix(BackendApiContract.READING_METADATA_DETAIL.substringBefore("{id}"))
                .toLongOrNull()?.takeIf { it > 0L }
                ?: return@createContext respond(exchange, 400, mapOf("error" to "AniList metadata ID must be positive"))
            val rawKind = query(exchange, "kind")?.trim()?.uppercase(java.util.Locale.ROOT)
            val kind = rawKind?.let { runCatching { ReadingMetadataKind.valueOf(it) }.getOrNull() }
            if (rawKind != null && kind == null) {
                return@createContext respond(exchange, 400, mapOf("error" to "kind must be MANGA, MANHWA, MANHUA, or NOVEL"))
            }
            try {
                val item = readingMetadata.detail(id)?.takeIf { kind == null || it.kind == kind }
                    ?: return@createContext respond(exchange, 404, mapOf("error" to "AniList reading metadata not found"))
                respond(exchange, 200, item)
            } catch (_: AniListReadingMetadataException) {
                respond(exchange, 502, mapOf("error" to "AniList reading metadata request failed"))
            }
        }
        // Member-facing read-only access to the normalized reading catalog.
        // Catalog writes remain behind the admin-only route below.
        http.createContext(BackendApiContract.READING) { exchange ->
            val base = BackendApiContract.READING
            val path = exchange.requestURI.path
            if (path != base && !path.startsWith(base + "/")) {
                return@createContext respond(exchange, 404, mapOf("error" to "reading route not found"))
            }
            if (!method(exchange, "GET")) return@createContext
            val parts = path.removePrefix(base).trim('/').split('/').filter { it.isNotBlank() }
            withUser(exchange) { _, _ ->
                val reading = readingCatalog
                    ?: return@withUser respond(exchange, 503, mapOf("error" to "reading catalog is not configured"))
                when {
                    parts.size == 2 && parts[0] == "catalog" && parts[1] == "visible-metadata" -> {
                        val rawKind = query(exchange, "kind")
                        val kind = rawKind?.trim()?.uppercase(java.util.Locale.ROOT)
                            ?.let { runCatching { ReadingKind.valueOf(it) }.getOrNull() }
                            ?: return@withUser respond(exchange, 400, mapOf("error" to "kind must be MANGA, MANHWA, MANHUA, or NOVEL"))
                        val rawIds = query(exchange, "ids")
                            ?: return@withUser respond(exchange, 400, mapOf("error" to "ids is required"))
                        val idParts = rawIds.split(',').map(String::trim)
                        if (idParts.isEmpty() || idParts.size > 100 || idParts.any { it.toLongOrNull()?.let { id -> id > 0L } != true }) {
                            return@withUser respond(exchange, 400, mapOf("error" to "ids must contain 1 to 100 positive AniList IDs"))
                        }
                        val requestedIds = idParts.mapNotNull(String::toLongOrNull).toSet()
                        val visibleIds = reading.list(kind).asSequence()
                            .filter { it.enabled }
                            .mapNotNull { it.metadataId }
                            .filter { it in requestedIds }
                            .distinct()
                            .toList()
                        respond(exchange, 200, visibleIds)
                    }
                    parts.size == 1 && parts[0] == "catalog" -> {
                        val rawKind = query(exchange, "kind")
                        val kind = rawKind?.trim()?.uppercase(java.util.Locale.ROOT)
                            ?.let { runCatching { ReadingKind.valueOf(it) }.getOrNull() }
                        if (rawKind != null && kind == null) {
                            return@withUser respond(exchange, 400, mapOf("error" to "kind must be MANGA, MANHWA, MANHUA, or NOVEL"))
                        }
                        val rawQuery = query(exchange, "q")?.trim().orEmpty()
                        val terms = rawQuery.lowercase(java.util.Locale.ROOT)
                            .split(Regex("\\s+")).filter(String::isNotBlank)
                        val allMatches = reading.list(kind).filter { entry ->
                            if (!entry.enabled) false
                            else if (terms.isEmpty()) true
                            else {
                                val searchable = listOf(
                                    entry.title, entry.author.orEmpty(), entry.description.orEmpty(),
                                    entry.genres.joinToString(" "), entry.status.orEmpty()
                                ).joinToString(" ").lowercase(java.util.Locale.ROOT)
                                terms.all { it in searchable }
                            }
                        }
                        val page = query(exchange, "page")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                        val perPage = query(exchange, "perPage")?.toIntOrNull()?.coerceIn(1, 100) ?: 30
                        val offset = (page - 1).toLong().let { it * perPage }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                        val items = allMatches.drop(offset).take(perPage).map(::readingBrowseItem)
                        respond(exchange, 200, ReadingCatalogBrowsePage(
                            items = items,
                            page = page,
                            perPage = perPage,
                            total = allMatches.size,
                            hasNextPage = offset + items.size < allMatches.size
                        ))
                    }
                    parts.size == 3 && parts[0] == "items" && parts[1] == "by-metadata" -> {
                        val metadataId = parts[2].toLongOrNull()?.takeIf { it > 0L }
                            ?: return@withUser respond(exchange, 400, mapOf("error" to "AniList metadata ID must be positive"))
                        val rawKind = query(exchange, "kind")
                        val kind = rawKind?.trim()?.uppercase(java.util.Locale.ROOT)
                            ?.let { runCatching { ReadingKind.valueOf(it) }.getOrNull() }
                            ?: return@withUser respond(exchange, 400, mapOf("error" to "kind must be MANGA, MANHWA, MANHUA, or NOVEL"))
                        val entry = reading.list(kind).firstOrNull { it.enabled && it.metadataId == metadataId }
                            ?: return@withUser respond(exchange, 404, mapOf("error" to "reading catalog entry not found"))
                        respond(exchange, 200, readingBrowseItem(entry))
                    }
                    parts.size == 2 && parts[0] == "items" -> {
                        val id = decode(parts[1])
                        val entry = reading.get(id)
                            ?.takeIf { it.enabled }
                            ?: return@withUser respond(exchange, 404, mapOf("error" to "reading catalog entry not found"))
                        respond(exchange, 200, readingBrowseItem(entry))
                    }
                    parts.size == 3 && parts[0] == "items" && parts[2] == "chapters" -> {
                        val id = decode(parts[1])
                        val entry = reading.get(id)
                            ?.takeIf { it.enabled }
                            ?: return@withUser respond(exchange, 404, mapOf("error" to "reading catalog entry not found"))
                        val requestedLanguage = query(exchange, "language")?.trim()?.lowercase()
                        if (requestedLanguage != null && requestedLanguage !in setOf("id", "en")) {
                            return@withUser respond(exchange, 400, mapOf("error" to "language must be id or en"))
                        }
                        val chapters = if (requestedLanguage == null) entry.chapters else entry.chapters.filter { chapter ->
                            chapter.sources.any { source ->
                                source.enabled && source.language?.trim()?.lowercase() == requestedLanguage
                            }
                        }
                        respond(exchange, 200, chapters.map(::readingChapterSummary))
                    }
                    parts.size == 3 && parts[0] == "items" && parts[2] == "chapter-content" -> {
                        val entryId = decode(parts[1])
                        val chapterId = query(exchange, "chapterId")?.takeIf { it.isNotBlank() }
                            ?: return@withUser respond(exchange, 400, mapOf("error" to "chapterId is required"))
                        val entry = reading.get(entryId)
                            ?.takeIf { it.enabled }
                            ?: return@withUser respond(exchange, 404, mapOf("error" to "reading catalog entry not found"))
                        // The requested chapter may come from AniList/UI selection and not exist in the manual URL catalog yet.
                        // Let discovery-capable runtime providers resolve it without persisting a synthetic catalog chapter.
                        val requestedLanguage = query(exchange, "language")?.trim()?.lowercase()
                        if (requestedLanguage != null && requestedLanguage !in setOf("id", "en")) {
                            return@withUser respond(exchange, 400, mapOf("error" to "language must be id or en"))
                        }
                        val chapter = entry.chapters.firstOrNull { it.id == chapterId || it.number == chapterId }
                        val content = kotlinx.coroutines.runBlocking {
                            reading.resolveChapter(entryId, chapterId, language = requestedLanguage)
                        }
                            ?: return@withUser respond(exchange, 502, mapOf("error" to "reading chapter source could not be resolved"))
                        if (content.kind != entry.kind || !content.isUsable()) {
                            return@withUser respond(exchange, 502, mapOf("error" to "reading chapter source returned unusable content"))
                        }
                        val resultProviderId = content.sourceId.removePrefix("reading-provider:")
                        val source = chapter?.sources.orEmpty().firstOrNull {
                            it.id == content.sourceId ||
                                it.providerId.equals(content.sourceId, ignoreCase = true) ||
                                it.providerId.equals(resultProviderId, ignoreCase = true)
                        }
                        respond(exchange, 200, content.copy(referer = content.referer ?: source?.url))
                    }
                    else -> respond(exchange, 404, mapOf("error" to "reading route not found"))
                }
            }
        }
        http.createContext(BackendApiContract.READING_CATALOG) { exchange ->
            val base = BackendApiContract.READING_CATALOG
            val path = exchange.requestURI.path
            if (path != base && !path.startsWith(base + "/")) return@createContext respond(exchange, 404, mapOf("error" to "reading catalog route not found"))
            val parts = path.removePrefix(base).trim('/').split('/').filter { it.isNotBlank() }
            withAdminUser(exchange) { runtime, user ->
                runtime.adminAccess.require(user, AdminPermission.MANAGE_CATALOG)
                val reading = readingCatalog ?: return@withAdminUser respond(exchange, 503, mapOf("error" to "reading catalog is not configured"))
                when {
                    parts.isEmpty() && method(exchange, "GET") -> {
                        val rawKind = query(exchange, "kind")
                        val kind = rawKind?.trim()?.uppercase()?.let { runCatching { ReadingKind.valueOf(it) }.getOrNull() }
                        if (rawKind != null && kind == null) return@withAdminUser respond(exchange, 400, mapOf("error" to "kind must be MANGA, MANHWA, MANHUA, or NOVEL"))
                        respond(exchange, 200, reading.list(kind))
                    }
                    parts.size == 3 && parts[0] == "items" && parts[2] == "enabled" && method(exchange, "POST") -> {
                        val body = readBody(exchange, 16 * 1024) ?: return@withAdminUser respond(exchange, 413, mapOf("error" to "request body is too large"))
                        val payload = runCatching { gson.fromJson(body.toString(StandardCharsets.UTF_8), ReadingChapterSourceEnabledUpdate::class.java) }.getOrNull()
                            ?: return@withAdminUser respond(exchange, 400, mapOf("error" to "invalid catalog enabled payload"))
                        val updated = reading.setEntryEnabled(decode(parts[1]), payload.enabled)
                            ?: return@withAdminUser respond(exchange, 404, mapOf("error" to "reading catalog entry not found"))
                        respond(exchange, 200, updated)
                    }
                    parts.size == 1 && parts[0] == "items" && method(exchange, "POST") -> {
                        val body = readBody(exchange, 2 * 1024 * 1024) ?: return@withAdminUser respond(exchange, 413, mapOf("error" to "request body is too large"))
                        val entry = runCatching { gson.fromJson(body.toString(StandardCharsets.UTF_8), ReadingCatalogEntry::class.java) }.getOrNull()
                            ?: return@withAdminUser respond(exchange, 400, mapOf("error" to "invalid reading catalog entry"))
                        if (entry.title.isBlank() || (entry.url.isBlank() && entry.metadataId == null && entry.catalogKey.isNullOrBlank())) return@withAdminUser respond(exchange, 400, mapOf("error" to "title is required; url or AniList identity is required"))
                        val saved = runCatching { reading.putManual(entry) }.getOrElse { error ->
                            return@withAdminUser respond(exchange, 400, mapOf("error" to (error.message ?: "invalid reading catalog entry")))
                        }
                        respond(exchange, 200, saved)
                    }
                    parts.size == 3 && parts[0] == "items" && parts[2] == "chapters" -> {
                        val id = decode(parts[1])
                        when (exchange.requestMethod.uppercase()) {
                            "GET" -> {
                                val entry = reading.get(id) ?: return@withAdminUser respond(exchange, 404, mapOf("error" to "reading catalog entry not found"))
                                respond(exchange, 200, entry.chapters)
                            }
                            "POST" -> {
                                val body = readBody(exchange, 4 * 1024 * 1024) ?: return@withAdminUser respond(exchange, 413, mapOf("error" to "request body is too large"))
                                val chapter = runCatching { gson.fromJson(body.toString(StandardCharsets.UTF_8), ReadingChapterEntry::class.java) }.getOrNull()
                                    ?: return@withAdminUser respond(exchange, 400, mapOf("error" to "invalid reading chapter payload"))
                                if (chapter.number.isBlank()) return@withAdminUser respond(exchange, 400, mapOf("error" to "chapter number is required"))
                                if (reading.get(id) == null) return@withAdminUser respond(exchange, 404, mapOf("error" to "reading catalog entry not found"))
                                val saved = runCatching { reading.putChapter(id, chapter) }.getOrElse { error ->
                                    return@withAdminUser respond(exchange, 400, mapOf("error" to (error.message ?: "invalid reading chapter")))
                                } ?: return@withAdminUser respond(exchange, 404, mapOf("error" to "reading catalog entry not found"))
                                respond(exchange, 200, saved)
                            }
                            else -> methodNotAllowed(exchange, "GET, POST")
                        }
                    }
                    parts.size == 7 && parts[0] == "items" && parts[2] == "chapters" && parts[4] == "sources" && parts[6] == "enabled" && exchange.requestMethod.equals("POST", true) -> {
                        val body = readBody(exchange, 16 * 1024) ?: return@withAdminUser respond(exchange, 413, mapOf("error" to "request body is too large"))
                        val payload = runCatching { gson.fromJson(body.toString(StandardCharsets.UTF_8), ReadingChapterSourceEnabledUpdate::class.java) }.getOrNull()
                            ?: return@withAdminUser respond(exchange, 400, mapOf("error" to "invalid source enabled payload"))
                        val updated = reading.setChapterSourceEnabled(decode(parts[1]), decode(parts[3]), decode(parts[5]), payload.enabled)
                            ?: return@withAdminUser respond(exchange, 404, mapOf("error" to "reading chapter source not found"))
                        respond(exchange, 200, updated)
                    }
                    parts.size == 6 && parts[0] == "items" && parts[2] == "chapters" && parts[4] == "sources" && exchange.requestMethod.equals("DELETE", true) -> {
                        val deleted = reading.deleteChapterSource(decode(parts[1]), decode(parts[3]), decode(parts[5]))
                        if (!deleted) return@withAdminUser respond(exchange, 404, mapOf("error" to "reading chapter source not found"))
                        respond(exchange, 200, mapOf("deleted" to true))
                    }
                    parts.size == 7 && parts[0] == "items" && parts[2] == "chapters" && parts[4] == "sources" && parts[6] == "health" && exchange.requestMethod.equals("POST", true) -> {
                        val body = readBody(exchange, 64 * 1024) ?: return@withAdminUser respond(exchange, 413, mapOf("error" to "request body is too large"))
                        val payload = runCatching { gson.fromJson(body.toString(StandardCharsets.UTF_8), ReadingChapterSourceHealthUpdate::class.java) }.getOrNull()
                            ?: return@withAdminUser respond(exchange, 400, mapOf("error" to "invalid source health payload"))
                        val checkedAt = payload.checkedAt ?: System.currentTimeMillis()
                        if (checkedAt <= 0L) return@withAdminUser respond(exchange, 400, mapOf("error" to "checkedAt must be positive"))
                        if (payload.httpStatus != null && payload.httpStatus !in 100..599) return@withAdminUser respond(exchange, 400, mapOf("error" to "httpStatus must be between 100 and 599"))
                        val updated = reading.updateChapterSourceHealth(
                            decode(parts[1]), decode(parts[3]), decode(parts[5]),
                            payload.health, checkedAt, payload.httpStatus, payload.failureReason
                        ) ?: return@withAdminUser respond(exchange, 404, mapOf("error" to "reading chapter source not found"))
                        respond(exchange, 200, updated)
                    }
                    parts.size == 4 && parts[0] == "items" && parts[2] == "chapters" && exchange.requestMethod.equals("DELETE", true) -> {
                        val id = decode(parts[1])
                        if (reading.get(id) == null) return@withAdminUser respond(exchange, 404, mapOf("error" to "reading catalog entry not found"))
                        if (!reading.deleteChapter(id, decode(parts[3]))) {
                            return@withAdminUser respond(exchange, 404, mapOf("error" to "reading chapter not found"))
                        }
                        respond(exchange, 200, mapOf("deleted" to true))
                    }
                    parts.size == 2 && parts[0] == "items" -> {
                        val id = decode(parts[1])
                        when (exchange.requestMethod.uppercase()) {
                            "GET" -> {
                                val entry = reading.get(id) ?: return@withAdminUser respond(exchange, 404, mapOf("error" to "reading catalog entry not found"))
                                respond(exchange, 200, entry)
                            }
                            "DELETE" -> respond(exchange, 200, mapOf("deleted" to reading.delete(id)))
                            else -> methodNotAllowed(exchange, "GET, DELETE")
                        }
                    }
                    else -> respond(exchange, 404, mapOf("error" to "reading catalog route not found"))
                }
            }
        }
        val adminCatalogPrefix = AdminApiContract.CATALOG_EPISODES.substringBefore("{anilistId}")
        http.createContext(adminCatalogPrefix) { exchange ->
            val parts = exchange.requestURI.path.removePrefix(adminCatalogPrefix).split("/")
            val anilistId = parts.firstOrNull()?.toLongOrNull()
                ?: return@createContext respond(exchange, 400, mapOf("error" to "anilistId is required"))
            withAdminUser(exchange) { runtime, user ->
                runtime.adminAccess.require(user, AdminPermission.MANAGE_CATALOG)
                val serviceCatalog = catalog
                    ?: return@withAdminUser respond(exchange, 503, mapOf("error" to "catalog is not configured"))
                when {
                    parts.size == 2 && parts[1] == "import" -> {
                        if (!method(exchange, "POST")) return@withAdminUser
                        val body = readBody(exchange, 32 * 1024 * 1024)
                            ?: return@withAdminUser respond(exchange, 413, mapOf("error" to "request body is too large"))
                        val result = runCatching {
                            gson.fromJson(body.toString(StandardCharsets.UTF_8), CatalogImportPayload::class.java)
                        }.getOrNull() ?: return@withAdminUser respond(exchange, 400, mapOf("error" to "invalid catalog import payload"))
                        val items = result.sources.orEmpty().ifEmpty {
                            result.directLinks.orEmpty().map { it.copy(type = "direct") } +
                                result.resolvers.orEmpty().map { it.copy(type = "resolver") } +
                                result.extractors.orEmpty().map { it.copy(type = "player") }
                        }
                        if (items.isEmpty()) return@withAdminUser respond(exchange, 400, mapOf("error" to "catalog import contains no sources"))
                        if (items.size > 50000) return@withAdminUser respond(exchange, 413, mapOf("error" to "catalog import contains too many sources"))
                        val prepared = mutableListOf<EpisodeSource>()
                        var skipped = 0
                        items.forEach { item ->
                            val episode = item.episode ?: run { skipped++; return@forEach }
                            if (episode <= 0) { skipped++; return@forEach }
                            val url = item.url?.trim().orEmpty()
                            if (url.isBlank()) { skipped++; return@forEach }
                            val explicitType = item.type?.trim()?.lowercase().orEmpty()
                            val type = when (explicitType) {
                                "" -> if (item.role.equals("player", true)) SourceType.EXTRACTOR else SourceType.RESOLVER
                                "direct", "hls", "mp4" -> SourceType.DIRECT
                                "resolver" -> SourceType.RESOLVER
                                "player", "extractor", "embed" -> SourceType.EXTRACTOR
                                "auto" -> streamix.catalog.legacySourceType("AUTO", url)
                                else -> return@withAdminUser respond(
                                    exchange, 400,
                                    mapOf("error" to "unsupported catalog import source type")
                                )
                            }
                            val quality = item.quality?.trim()?.toIntOrNull()
                            prepared += EpisodeSource(
                                id = "",
                                anilistId = anilistId,
                                episodeNumber = episode,
                                sourceUrl = url,
                                origin = SourceOrigin.ADMIN,
                                type = type,
                                provider = item.provider?.trim()?.takeIf { it.isNotEmpty() },
                                role = item.role?.trim()?.takeIf { it.isNotEmpty() },
                                resolution = item.resolution?.trim()?.takeIf { it.isNotEmpty() },
                                label = item.label?.trim()?.takeIf { it.isNotEmpty() },
                                sourcePage = item.sourcePage?.trim()?.takeIf { it.isNotEmpty() },
                                source = item.source?.trim()?.takeIf { it.isNotEmpty() },
                                quality = quality
                            )
                        }
                        if (prepared.isEmpty()) {
                            return@withAdminUser respond(exchange, 400, mapOf("error" to "catalog import contains no valid sources"))
                        }
                        val operationId = UUID.randomUUID().toString()
                        // Write-ahead intent: if the following mutation succeeds but the completion
                        // audit write fails, the durable intent still records the attempted operation.
                        runtime.adminAudit.append(
                            AdminAuditEntry(
                                id = UUID.randomUUID().toString(), actorId = user.id,
                                action = "CATALOG_BULK_IMPORT_INTENT", targetType = "CATALOG", targetId = anilistId.toString(),
                                metadata = mapOf("phase" to "INTENT", "operationId" to operationId, "sourceCount" to prepared.size.toString()),
                                createdAt = Instant.now().toString()
                            )
                        )
                        val imported = try {
                            serviceCatalog.bulkUpsertSources(prepared).size
                        } catch (error: Exception) {
                            val status = if (error is IllegalArgumentException) 400 else 500
                            runtime.adminAudit.append(
                                AdminAuditEntry(
                                    id = UUID.randomUUID().toString(), actorId = user.id,
                                    action = "CATALOG_BULK_IMPORT_FAILED", targetType = "CATALOG",
                                    targetId = anilistId.toString(),
                                    metadata = mapOf(
                                        "phase" to "FAILED", "operationId" to operationId,
                                        "sourceCount" to prepared.size.toString(),
                                        "reason" to "catalog bulk import failed",
                                        "errorType" to (error::class.simpleName ?: "Exception"),
                                        "httpStatus" to status.toString()
                                    ),
                                    createdAt = Instant.now().toString()
                                )
                            )
                            return@withAdminUser respond(
                                exchange, status,
                                mapOf("error" to if (status == 400) (error.message ?: "invalid catalog import") else "internal server error")
                            )
                        }
                        try {
                            runtime.adminAudit.append(
                                AdminAuditEntry(
                                    id = UUID.randomUUID().toString(), actorId = user.id,
                                    action = "CATALOG_BULK_IMPORT", targetType = "CATALOG", targetId = anilistId.toString(),
                                    metadata = mapOf("operationId" to operationId, "imported" to imported.toString(), "skipped" to skipped.toString()),
                                    createdAt = Instant.now().toString()
                                )
                            )
                        } catch (_: Exception) {
                            return@withAdminUser respond(exchange, 500, mapOf(
                                "error" to "catalog import committed but audit recording failed",
                                "mutationCommitted" to true,
                                "auditRecorded" to false,
                                "operationId" to operationId,
                                "imported" to imported,
                                "skipped" to skipped
                            ))
                        }
                        respond(exchange, 200, mapOf("anilistId" to anilistId, "imported" to imported, "skipped" to skipped))
                    }
                    parts.size == 2 && parts[1] == "episodes" -> {
                        if (!method(exchange, "GET")) return@withAdminUser
                        respond(exchange, 200, serviceCatalog.episodes(anilistId))
                    }
                    parts.size == 4 && parts[1] == "episode" && parts[3] == "sources" -> {
                        val number = parts[2].toIntOrNull()
                            ?: return@withAdminUser respond(exchange, 400, mapOf("error" to "episode number is invalid"))
                        when (exchange.requestMethod.uppercase()) {
                            "GET" -> respond(
                                exchange, 200,
                                serviceCatalog.episodes(anilistId).firstOrNull { it.number == number }
                                    ?: streamix.catalog.CatalogEpisode(anilistId, number)
                            )
                            "POST" -> {
                                val body = readBody(exchange, 64 * 1024)
                                    ?: return@withAdminUser respond(exchange, 413, mapOf("error" to "request body is too large"))
                                val request = runCatching {
                                    val json = com.google.gson.JsonParser.parseString(body.toString(StandardCharsets.UTF_8))
                                    require(json.isJsonObject) { "source request must be a JSON object" }
                                    val obj = json.asJsonObject
                                    fun requiredString(name: String): String {
                                        val value = obj.get(name)
                                        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) { "$name is required" }
                                        return value.asString.trim().also { require(it.isNotEmpty()) { "$name is required" } }
                                    }
                                    fun optionalString(name: String): String? {
                                        val value = obj.get(name) ?: return null
                                        if (value.isJsonNull) return null
                                        require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "$name must be a string" }
                                        return value.asString
                                    }
                                    fun exactLong(value: com.google.gson.JsonElement, name: String): Long {
                                        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "$name must be an integer" }
                                        return value.asJsonPrimitive.asBigDecimal.longValueExact()
                                    }
                                    fun exactInt(value: com.google.gson.JsonElement, name: String): Int =
                                        Math.toIntExact(exactLong(value, name))
                                    fun optionalInt(name: String, default: Int): Int {
                                        val value = obj.get(name) ?: return default
                                        return exactInt(value, name)
                                    }
                                    fun optionalLong(name: String, default: Long): Long {
                                        val value = obj.get(name) ?: return default
                                        return exactLong(value, name)
                                    }
                                    fun optionalBoolean(name: String, default: Boolean): Boolean {
                                        val value = obj.get(name) ?: return default
                                        require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) { "$name must be a boolean" }
                                        return value.asBoolean
                                    }
                                    val headersElement = obj.get("headers")
                                    val headers = if (headersElement == null) emptyMap() else {
                                        require(headersElement.isJsonObject) { "headers must be an object" }
                                        headersElement.asJsonObject.entrySet().associate { (key, value) ->
                                            require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "header values must be strings" }
                                            key to value.asString
                                        }
                                    }
                                    EpisodeSource(
                                        id = requiredString("id"),
                                        anilistId = optionalLong("anilistId", 0L),
                                        episodeNumber = optionalInt("episodeNumber", 0),
                                        sourceUrl = requiredString("sourceUrl"),
                                        origin = SourceOrigin.ADMIN,
                                        type = SourceType.valueOf(requiredString("type").uppercase()),
                                        provider = optionalString("provider"),
                                        role = optionalString("role"),
                                        resolution = optionalString("resolution"),
                                        label = optionalString("label"),
                                        sourcePage = optionalString("sourcePage"),
                                        source = optionalString("source"),
                                        quality = obj.get("quality")?.takeUnless { it.isJsonNull }?.let {
                                            exactInt(it, "quality")
                                        },
                                        language = optionalString("language"),
                                        subtitleLanguage = optionalString("subtitleLanguage"),
                                        headers = headers,
                                        referer = optionalString("referer"),
                                        priority = optionalInt("priority", 0),
                                        enabled = optionalBoolean("enabled", true),
                                        health = obj.get("health")?.takeUnless { it.isJsonNull }?.let {
                                            require(it.isJsonPrimitive && it.asJsonPrimitive.isString) { "health must be a string" }
                                            streamix.catalog.SourceHealth.valueOf(it.asString.uppercase())
                                        } ?: streamix.catalog.SourceHealth.UNKNOWN,
                                        lastCheckedAt = obj.get("lastCheckedAt")?.takeUnless { it.isJsonNull }?.let {
                                            exactLong(it, "lastCheckedAt")
                                        },
                                        lastHttpStatus = obj.get("lastHttpStatus")?.takeUnless { it.isJsonNull }?.let {
                                            exactInt(it, "lastHttpStatus")
                                        },
                                        lastFailureReason = optionalString("lastFailureReason")
                                    )
                                }.getOrElse {
                                    return@withAdminUser respond(exchange, 400, mapOf("error" to "invalid source request"))
                                }
                                if (request.anilistId != anilistId || request.episodeNumber != number) {
                                    return@withAdminUser respond(exchange, 400, mapOf("error" to "anime/episode mismatch"))
                                }
                                val operationId = UUID.randomUUID().toString()
                                runtime.adminAudit.append(
                                    AdminAuditEntry(
                                        id = UUID.randomUUID().toString(), actorId = user.id,
                                        action = "CATALOG_SOURCE_UPSERT_INTENT", targetType = "CATALOG_SOURCE",
                                        targetId = request.id.takeIf { it.isNotBlank() },
                                        metadata = mapOf(
                                            "phase" to "INTENT",
                                            "operationId" to operationId,
                                            "anilistId" to anilistId.toString(),
                                            "episodeNumber" to number.toString(),
                                            "sourceType" to request.type.name
                                        ),
                                        createdAt = Instant.now().toString()
                                    )
                                )
                                val saved = try {
                                    serviceCatalog.upsertSource(request.copy(origin = SourceOrigin.ADMIN))
                                } catch (error: Exception) {
                                    runtime.adminAudit.append(
                                        AdminAuditEntry(
                                            id = UUID.randomUUID().toString(), actorId = user.id,
                                            action = "CATALOG_SOURCE_UPSERT_FAILED", targetType = "CATALOG_SOURCE",
                                            targetId = request.id.takeIf { it.isNotBlank() },
                                            metadata = mapOf(
                                                "phase" to "FAILED",
                                                "operationId" to operationId,
                                                "anilistId" to anilistId.toString(),
                                                "episodeNumber" to number.toString(),
                                                "sourceType" to request.type.name,
                                                "reason" to "catalog source upsert failed",
                                                "errorType" to (error::class.simpleName ?: "Exception"),
                                                "httpStatus" to "500"
                                            ),
                                            createdAt = Instant.now().toString()
                                        )
                                    )
                                    return@withAdminUser respond(exchange, 500, mapOf("error" to "catalog source upsert failed"))
                                }
                                try {
                                    runtime.adminAudit.append(
                                        AdminAuditEntry(
                                            id = UUID.randomUUID().toString(),
                                            actorId = user.id,
                                            action = "CATALOG_SOURCE_UPSERT",
                                            targetType = "CATALOG_SOURCE",
                                            targetId = saved.id,
                                            metadata = mapOf(
                                                "operationId" to operationId,
                                                "anilistId" to saved.anilistId.toString(),
                                                "episodeNumber" to saved.episodeNumber.toString(),
                                                "sourceType" to saved.type.name
                                            ),
                                            createdAt = Instant.now().toString()
                                        )
                                    )
                                } catch (_: Exception) {
                                    return@withAdminUser respond(exchange, 500, mapOf(
                                        "error" to "catalog source saved but audit recording failed",
                                        "mutationCommitted" to true,
                                        "auditRecorded" to false,
                                        "operationId" to operationId
                                    ))
                                }
                                respond(exchange, 200, saved)
                            }
                            else -> methodNotAllowed(exchange, "GET, POST")
                        }
                    }
                    parts.size == 6 && parts[1] == "episode" && parts[3] == "sources" && parts[5] == "check" -> {
                        if (!method(exchange, "POST")) return@withAdminUser
                        val number = parts[2].toIntOrNull()
                            ?: return@withAdminUser respond(exchange, 400, mapOf("error" to "episode number is invalid"))
                        val sourceId = decode(parts[4]).takeIf { it.isNotBlank() }
                            ?: return@withAdminUser respond(exchange, 400, mapOf("error" to "sourceId is required"))
                        val operationId = UUID.randomUUID().toString()
                        runSuspend(exchange) {
                            runtime.adminAudit.append(
                                AdminAuditEntry(
                                    id = UUID.randomUUID().toString(), actorId = user.id,
                                    action = "CATALOG_SOURCE_CHECK_INTENT", targetType = "CATALOG_SOURCE",
                                    targetId = sourceId,
                                    metadata = mapOf(
                                        "phase" to "INTENT",
                                        "operationId" to operationId,
                                        "anilistId" to anilistId.toString(),
                                        "episodeNumber" to number.toString()
                                    ),
                                    createdAt = Instant.now().toString()
                                )
                            )
                            val checked = try {
                                serviceCatalog.checkSource(anilistId, number, sourceId)
                            } catch (error: Exception) {
                                runtime.adminAudit.append(
                                    AdminAuditEntry(
                                        id = UUID.randomUUID().toString(), actorId = user.id,
                                        action = "CATALOG_SOURCE_CHECK_FAILED", targetType = "CATALOG_SOURCE",
                                        targetId = sourceId,
                                        metadata = mapOf(
                                            "phase" to "FAILED", "operationId" to operationId,
                                            "anilistId" to anilistId.toString(), "episodeNumber" to number.toString(),
                                            "reason" to "catalog source check failed",
                                            "errorType" to (error::class.simpleName ?: "Exception"), "httpStatus" to "500"
                                        ),
                                        createdAt = Instant.now().toString()
                                    )
                                )
                                return@runSuspend HttpResult(500, mapOf("error" to "catalog source check failed"))
                            } ?: run {
                                runtime.adminAudit.append(
                                    AdminAuditEntry(
                                        id = UUID.randomUUID().toString(), actorId = user.id,
                                        action = "CATALOG_SOURCE_CHECK_FAILED", targetType = "CATALOG_SOURCE",
                                        targetId = sourceId,
                                        metadata = mapOf(
                                            "phase" to "FAILED", "operationId" to operationId,
                                            "anilistId" to anilistId.toString(), "episodeNumber" to number.toString(),
                                            "reason" to "source not found", "httpStatus" to "404"
                                        ),
                                        createdAt = Instant.now().toString()
                                    )
                                )
                                return@runSuspend HttpResult(404, mapOf("error" to "source not found"))
                            }
                            try {
                                runtime.adminAudit.append(
                                    AdminAuditEntry(
                                        id = UUID.randomUUID().toString(),
                                        actorId = user.id,
                                        action = "CATALOG_SOURCE_CHECK",
                                        targetType = "CATALOG_SOURCE",
                                        targetId = sourceId,
                                        metadata = mapOf(
                                            "operationId" to operationId,
                                            "anilistId" to anilistId.toString(),
                                            "episodeNumber" to number.toString(),
                                            "health" to checked.health.name,
                                            "httpStatus" to (checked.lastHttpStatus?.toString() ?: "")
                                        ),
                                        createdAt = Instant.now().toString()
                                    )
                                )
                            } catch (_: Exception) {
                                return@runSuspend HttpResult(500, mapOf(
                                    "error" to "source health updated but audit recording failed",
                                    "mutationCommitted" to true,
                                    "auditRecorded" to false,
                                    "operationId" to operationId
                                ))
                            }
                            checked
                        }
                    }
                    parts.size == 6 && parts[1] == "episode" && parts[3] == "sources" && parts[5] == "enabled" -> {
                        if (!method(exchange, "POST")) return@withAdminUser
                        val number = parts[2].toIntOrNull()
                            ?: return@withAdminUser respond(exchange, 400, mapOf("error" to "episode number is invalid"))
                        val sourceId = decode(parts[4]).takeIf { it.isNotBlank() }
                            ?: return@withAdminUser respond(exchange, 400, mapOf("error" to "sourceId is required"))
                        val enabled = query(exchange, "value")?.toBooleanStrictOrNull()
                            ?: return@withAdminUser respond(exchange, 400, mapOf("error" to "value must be true or false"))
                        val operationId = UUID.randomUUID().toString()
                        runtime.adminAudit.append(
                            AdminAuditEntry(
                                id = UUID.randomUUID().toString(), actorId = user.id,
                                action = if (enabled) "CATALOG_SOURCE_ENABLE_INTENT" else "CATALOG_SOURCE_DISABLE_INTENT",
                                targetType = "CATALOG_SOURCE", targetId = sourceId,
                                metadata = mapOf(
                                    "phase" to "INTENT",
                                    "operationId" to operationId,
                                    "anilistId" to anilistId.toString(),
                                    "episodeNumber" to number.toString(),
                                    "enabled" to enabled.toString()
                                ),
                                createdAt = Instant.now().toString()
                            )
                        )
                        val updated = try {
                            serviceCatalog.setSourceEnabled(anilistId, number, sourceId, enabled)
                        } catch (error: Exception) {
                            runtime.adminAudit.append(
                                AdminAuditEntry(
                                    id = UUID.randomUUID().toString(), actorId = user.id,
                                    action = if (enabled) "CATALOG_SOURCE_ENABLE_FAILED" else "CATALOG_SOURCE_DISABLE_FAILED",
                                    targetType = "CATALOG_SOURCE", targetId = sourceId,
                                    metadata = mapOf(
                                        "phase" to "FAILED", "operationId" to operationId,
                                        "anilistId" to anilistId.toString(), "episodeNumber" to number.toString(),
                                        "enabled" to enabled.toString(), "reason" to "catalog source state update failed",
                                        "errorType" to (error::class.simpleName ?: "Exception"), "httpStatus" to "500"
                                    ),
                                    createdAt = Instant.now().toString()
                                )
                            )
                            return@withAdminUser respond(exchange, 500, mapOf("error" to "catalog source state update failed"))
                        } ?: run {
                            runtime.adminAudit.append(
                                AdminAuditEntry(
                                    id = UUID.randomUUID().toString(), actorId = user.id,
                                    action = if (enabled) "CATALOG_SOURCE_ENABLE_FAILED" else "CATALOG_SOURCE_DISABLE_FAILED",
                                    targetType = "CATALOG_SOURCE", targetId = sourceId,
                                    metadata = mapOf(
                                        "phase" to "FAILED", "operationId" to operationId,
                                        "anilistId" to anilistId.toString(), "episodeNumber" to number.toString(),
                                        "enabled" to enabled.toString(), "reason" to "source not found", "httpStatus" to "404"
                                    ),
                                    createdAt = Instant.now().toString()
                                )
                            )
                            return@withAdminUser respond(exchange, 404, mapOf("error" to "source not found"))
                        }
                        try {
                            runtime.adminAudit.append(
                                AdminAuditEntry(
                                    id = UUID.randomUUID().toString(),
                                    actorId = user.id,
                                    action = if (enabled) "CATALOG_SOURCE_ENABLE" else "CATALOG_SOURCE_DISABLE",
                                    targetType = "CATALOG_SOURCE",
                                    targetId = sourceId,
                                    metadata = mapOf(
                                        "operationId" to operationId,
                                        "anilistId" to anilistId.toString(),
                                        "episodeNumber" to number.toString(),
                                        "enabled" to enabled.toString()
                                    ),
                                    createdAt = Instant.now().toString()
                                )
                            )
                        } catch (_: Exception) {
                            return@withAdminUser respond(exchange, 500, mapOf(
                                "error" to "source state updated but audit recording failed",
                                "mutationCommitted" to true,
                                "auditRecorded" to false,
                                "operationId" to operationId
                            ))
                        }
                        respond(exchange, 200, updated)
                    }
                    parts.size == 5 && parts[1] == "episode" && parts[3] == "sources" -> {
                        if (!method(exchange, "DELETE")) return@withAdminUser
                        val number = parts[2].toIntOrNull()
                            ?: return@withAdminUser respond(exchange, 400, mapOf("error" to "episode number is invalid"))
                        val sourceId = decode(parts[4]).takeIf { it.isNotBlank() }
                            ?: return@withAdminUser respond(exchange, 400, mapOf("error" to "sourceId is required"))
                        val operationId = UUID.randomUUID().toString()
                        runtime.adminAudit.append(
                            AdminAuditEntry(
                                id = UUID.randomUUID().toString(), actorId = user.id,
                                action = "CATALOG_SOURCE_DELETE_INTENT", targetType = "CATALOG_SOURCE",
                                targetId = sourceId,
                                metadata = mapOf(
                                    "phase" to "INTENT",
                                    "operationId" to operationId,
                                    "anilistId" to anilistId.toString(),
                                    "episodeNumber" to number.toString()
                                ),
                                createdAt = Instant.now().toString()
                            )
                        )
                        val deleted = try {
                            serviceCatalog.deleteSource(anilistId, number, sourceId)
                        } catch (error: Exception) {
                            runtime.adminAudit.append(
                                AdminAuditEntry(
                                    id = UUID.randomUUID().toString(),
                                    actorId = user.id,
                                    action = "CATALOG_SOURCE_DELETE_FAILED",
                                    targetType = "CATALOG_SOURCE",
                                    targetId = sourceId,
                                    metadata = mapOf(
                                        "phase" to "FAILED",
                                        "operationId" to operationId,
                                        "anilistId" to anilistId.toString(),
                                        "episodeNumber" to number.toString(),
                                        "reason" to "catalog delete operation failed",
                                        "errorType" to (error::class.simpleName ?: "Exception"),
                                        "httpStatus" to "500"
                                    ),
                                    createdAt = Instant.now().toString()
                                )
                            )
                            return@withAdminUser respond(exchange, 500, mapOf("error" to "catalog source delete failed"))
                        }
                        if (!deleted) {
                            runtime.adminAudit.append(
                                AdminAuditEntry(
                                    id = UUID.randomUUID().toString(),
                                    actorId = user.id,
                                    action = "CATALOG_SOURCE_DELETE_FAILED",
                                    targetType = "CATALOG_SOURCE",
                                    targetId = sourceId,
                                    metadata = mapOf(
                                        "phase" to "FAILED",
                                        "operationId" to operationId,
                                        "anilistId" to anilistId.toString(),
                                        "episodeNumber" to number.toString(),
                                        "reason" to "source not found",
                                        "httpStatus" to "404"
                                    ),
                                    createdAt = Instant.now().toString()
                                )
                            )
                            return@withAdminUser respond(exchange, 404, mapOf("error" to "source not found"))
                        }
                        if (deleted) {
                            try {
                                runtime.adminAudit.append(
                                    AdminAuditEntry(
                                        id = UUID.randomUUID().toString(),
                                        actorId = user.id,
                                        action = "CATALOG_SOURCE_DELETE",
                                        targetType = "CATALOG_SOURCE",
                                        targetId = sourceId,
                                        metadata = mapOf(
                                            "operationId" to operationId,
                                            "anilistId" to anilistId.toString(),
                                            "episodeNumber" to number.toString()
                                        ),
                                        createdAt = Instant.now().toString()
                                    )
                                )
                            } catch (_: Exception) {
                                return@withAdminUser respond(exchange, 500, mapOf(
                                    "error" to "source deleted but audit recording failed",
                                    "mutationCommitted" to true,
                                    "auditRecorded" to false,
                                    "operationId" to operationId
                                ))
                            }
                        }
                        respond(exchange, 200, mapOf("deleted" to deleted))
                    }
                    else -> respond(exchange, 404, mapOf("error" to "catalog route not found"))
                }
            }
        }
        http.createContext(BackendApiContract.READING_METADATA) { exchange ->
            if (!method(exchange, "GET")) return@createContext
            val suffix = exchange.requestURI.path.removePrefix(BackendApiContract.READING_METADATA).removePrefix("/")
            when {
                suffix == "search" -> {
                    val searchQuery = query(exchange, "q")?.trim().orEmpty()
                    if (searchQuery.isBlank()) return@createContext respond(exchange, 400, mapOf("error" to "q is required"))
                    val rawKind = query(exchange, "kind")?.trim()?.uppercase(java.util.Locale.ROOT)
                        ?: return@createContext respond(exchange, 400, mapOf("error" to "kind is required"))
                    val kind = try {
                        ReadingMetadataKind.valueOf(rawKind)
                    } catch (_: IllegalArgumentException) {
                        return@createContext respond(exchange, 400, mapOf("error" to "kind must be MANGA, MANHWA, MANHUA, or NOVEL"))
                    }
                    val page = query(exchange, "page")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    try {
                        respond(exchange, 200, readingMetadata.search(searchQuery, kind, page))
                    } catch (_: AniListReadingMetadataException) {
                        respond(exchange, 502, mapOf("error" to "AniList reading metadata request failed"))
                    } catch (_: Exception) {
                        respond(exchange, 500, mapOf("error" to "internal server error"))
                    }
                }
                suffix.toLongOrNull() != null -> {
                    val id = suffix.toLong()
                    try {
                        val metadata = readingMetadata.detail(id)
                        if (metadata == null) respond(exchange, 404, mapOf("error" to "reading metadata not found"))
                        else respond(exchange, 200, metadata)
                    } catch (_: AniListReadingMetadataException) {
                        respond(exchange, 502, mapOf("error" to "AniList reading metadata request failed"))
                    } catch (_: Exception) {
                        respond(exchange, 500, mapOf("error" to "internal server error"))
                    }
                }
                else -> respond(exchange, 404, mapOf("error" to "route not found"))
            }
        }
        http.createContext(BackendApiContract.SEARCH) { exchange ->
            if (!method(exchange, "GET")) return@createContext
            val query = query(exchange, "q")?.trim().orEmpty()
            if (query.isBlank()) return@createContext respond(exchange, 400, mapOf("error" to "q is required"))
            val page = query(exchange, "page")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            runSuspend(exchange) { service.search(query, page) }
        }
        http.createContext(BackendApiContract.EXTRACTORS) { exchange ->
            if (!method(exchange, "GET")) return@createContext
            val registry = extractorRegistry
                ?: return@createContext respond(exchange, 503, mapOf("error" to "extractor registry is not configured"))
            val entries = registry.all()
                .map { mapOf("extractorId" to it.id, "domains" to it.domains.sorted()) }
                .sortedBy { it["extractorId"].toString().lowercase() }
            respond(exchange, 200, mapOf("total" to entries.size, "extractors" to entries))
        }
        val animeRoutePrefix = BackendApiContract.DETAIL.substringBefore("{id}")
        http.createContext(animeRoutePrefix) { exchange ->
            val parts = exchange.requestURI.path.removePrefix(animeRoutePrefix).split("/")
            val isPlaybackRoute = parts.size == 4 && parts[1] == "episode" && parts[3] == "playback"
            if (isPlaybackRoute) {
                if (!method(exchange, "POST")) return@createContext
            } else if (!method(exchange, "GET")) return@createContext
            when {
                parts.size == 1 && parts[0].toLongOrNull() != null -> runSuspend(exchange) {
                    service.detail(parts[0].toLong())
                }
                parts.size == 2 && parts[1] == "episodes" && parts[0].toLongOrNull() != null -> runSuspend(exchange) {
                    service.episodes(parts[0].toLong())
                }
                parts.size == 2 && parts[1] == "views" && parts[0].toLongOrNull() != null -> {
                    if (!method(exchange, "GET")) return@createContext
                    respond(exchange, 200, viewCounts.list(parts[0].toLong()))
                }
                parts.size == 4 && parts[1] == "episode" && parts[3] == "playback" && parts[0].toLongOrNull() != null -> {
                    if (!method(exchange, "POST")) return@createContext
                    val animeId = parts[0].toLong()
                    val number = parts[2].toIntOrNull()?.takeIf { it > 0 }
                        ?: return@createContext respond(exchange, 400, mapOf("error" to "episode number is invalid"))
                    val body = readBody(exchange, 16 * 1024)
                        ?: return@createContext respond(exchange, 413, mapOf("error" to "request body is too large"))
                    val playbackId = runCatching {
                        gson.fromJson(body.toString(StandardCharsets.UTF_8), PlaybackStartedRequest::class.java)?.playbackId?.trim()
                    }.getOrNull()?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{16,100}")) }
                        ?: return@createContext respond(exchange, 400, mapOf("error" to "valid playbackId is required"))
                    respond(exchange, 200, mapOf("count" to viewCounts.record(animeId, number, playbackId)))
                }
                parts.size == 4 && parts[1] == "episode" && parts[3] == "streams" && parts[0].toLongOrNull() != null -> {
                    val number = parts[2].toIntOrNull()
                        ?: return@createContext respond(exchange, 400, mapOf("error" to "episode number is invalid"))
                    val failedSources = query(exchange, "failedSourceIds")
                        ?.split(",")
                        ?.map { it.trim() }
                        ?.filter { it.isNotBlank() }
                        ?.toSet()
                        .orEmpty()
                    runSuspend(exchange) {
                        service.streams(parts[0].toLong(), number, failedSources)
                    }
                }
                else -> respond(exchange, 404, mapOf("error" to "route not found"))
            }
        }
        http.start()
        server = http
    }

    override fun close() {
        server?.stop(1)
        server = null
        executor?.shutdownNow()
        executor = null
    }


    private fun withUser(exchange: HttpExchange, block: (AuthRuntime, ShinigamiUser) -> Unit) {
        val runtime = authRuntime ?: return respond(exchange, 503, mapOf("error" to "authentication backend is not configured"))
        val token = bearerToken(exchange) ?: return respond(exchange, 401, mapOf("error" to "authorization token is required"))
        // User/session persistence can fail before the route callback starts (for example,
        // if a persisted JSON file is malformed). Return a controlled 500 instead of
        // letting the HTTP worker terminate without sending a response.
        val user = try {
            runtime.auth.currentUser(token)
        } catch (_: Exception) {
            return respond(exchange, 500, mapOf("error" to "internal server error"))
        } ?: return respond(exchange, 401, mapOf("error" to "session is invalid or expired"))
        try { block(runtime, user) }
        catch (_: SecurityException) { respond(exchange, 403, mapOf("error" to "forbidden")) }
        catch (error: IllegalStateException) { respond(exchange, 404, mapOf("error" to (error.message ?: "resource not found"))) }
        catch (error: IllegalArgumentException) { respond(exchange, 400, mapOf("error" to (error.message ?: "invalid request"))) }
        catch (_: java.io.IOException) { respond(exchange, 500, mapOf("error" to "internal server error")) }
        catch (_: Exception) { respond(exchange, 500, mapOf("error" to "internal server error")) }
    }

    private fun withAdminUser(exchange: HttpExchange, block: (AuthRuntime, ShinigamiUser) -> Unit) {
        val runtime = authRuntime
            ?: return respond(exchange, 503, mapOf("error" to "authentication backend is not configured"))
        val token = bearerToken(exchange)
            ?: return respond(exchange, 401, mapOf("error" to "authorization token is required"))
        val user = try {
            runtime.auth.currentUser(token)
        } catch (_: Exception) {
            return respond(exchange, 500, mapOf("error" to "internal server error"))
        } ?: return respond(exchange, 401, mapOf("error" to "session is invalid or expired"))
        try {
            block(runtime, user)
        } catch (error: SecurityException) {
            respond(exchange, 403, mapOf("error" to "admin permission required"))
        } catch (error: IllegalStateException) {
            respond(exchange, 404, mapOf("error" to (error.message ?: "resource not found")))
        } catch (error: IllegalArgumentException) {
            respond(exchange, 400, mapOf("error" to (error.message ?: "invalid request")))
        } catch (_: java.io.IOException) {
            respond(exchange, 500, mapOf("error" to "internal server error"))
        } catch (_: Exception) {
            respond(exchange, 500, mapOf("error" to "internal server error"))
        }
    }

    private fun readingBrowseItem(entry: ReadingCatalogEntry) = ReadingCatalogBrowseItem(
        id = entry.id,
        kind = entry.kind,
        title = entry.title,
        coverUrl = entry.coverUrl,
        description = entry.description,
        author = entry.author,
        genres = entry.genres,
        status = entry.status,
        chapterCount = entry.chapters.size,
        updatedAt = entry.updatedAt,
        metadataId = entry.metadataId
    )

    private fun readingChapterSummary(chapter: ReadingChapterEntry) =
        ReadingCatalogChapterSummary(id = chapter.id, number = chapter.number, title = chapter.title)

    private data class PlaybackStartedRequest(val playbackId: String? = null)

    private fun methodNotAllowed(exchange: HttpExchange, allowedMethods: String) {
        exchange.responseHeaders.set("Allow", allowedMethods)
        respond(exchange, 405, mapOf("error" to "method not allowed"))
    }

    private fun method(exchange: HttpExchange, expected: String): Boolean {
        if (exchange.requestMethod.equals(expected, ignoreCase = true)) return true
        exchange.responseHeaders.set("Allow", expected.uppercase())
        respond(exchange, 405, mapOf("error" to "method not allowed"))
        return false
    }

    private fun bearerToken(exchange: HttpExchange): String? {
        val header = exchange.requestHeaders.getFirst("Authorization")?.trim() ?: return null
        if (!header.startsWith("Bearer ", ignoreCase = true)) return null
        return header.substring(7).trim().takeIf { it.isNotEmpty() }
    }

    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)

    private fun readBody(exchange: HttpExchange, maxBytes: Int): ByteArray? {
        // Do not reject solely from Content-Length before consuming any request bytes:
        // HttpServer may reset the connection when a 413 response is sent with an unread body.
        // The bounded read below rejects oversized payloads without buffering the full body.
        val input = exchange.requestBody
        val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 8192))
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (total.toLong() + read > maxBytes) {
                // The caller will reject the request; do not close the upload before responding.
                return null
            }
            output.write(buffer, 0, read)
            total += read
        }
        input.close()
        return output.toByteArray()
    }

    private fun query(exchange: HttpExchange, name: String): String? =
        exchange.requestURI.rawQuery?.split("&")?.mapNotNull { part ->
            val pair = part.split("=", limit = 2)
            if (pair.firstOrNull() == name) URLDecoder.decode(pair.getOrElse(1) { "" }, StandardCharsets.UTF_8) else null
        }?.firstOrNull()

    private fun respond(exchange: HttpExchange, status: Int, body: Any) {
        val bytes = gson.toJson(body).toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private data class HttpResult(val status: Int, val body: Any)

    private fun runSuspend(exchange: HttpExchange, block: suspend () -> Any) {
        try {
            kotlinx.coroutines.runBlocking {
                when (val result = block()) {
                    is HttpResult -> respond(exchange, result.status, result.body)
                    else -> respond(exchange, 200, result)
                }
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: AniListUpstreamException) {
            respond(exchange, 502, mapOf("error" to "AniList request failed"))
        } catch (_: Exception) {
            respond(exchange, 500, mapOf("error" to "internal server error"))
        }
    }
    private fun handleCommunityChatRoute(exchange: HttpExchange, routePrefix: String) {
        val parts = exchange.requestURI.path.removePrefix(routePrefix).split("/")
        val mediaId = parts.firstOrNull()?.toLongOrNull()?.takeIf { it > 0L }
            ?: return respond(exchange, 400, mapOf("error" to "mediaId must be a positive integer"))
        if (parts.size != 2 || parts[1] != "messages") {
            return respond(exchange, 404, mapOf("error" to "community route not found"))
        }
        withUser(exchange) { runtime, user ->
            when (exchange.requestMethod.uppercase()) {
                "GET" -> {
                    val page = query(exchange, "page")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    val perPage = query(exchange, "perPage")?.toIntOrNull()?.coerceIn(1, 100) ?: 30
                    respond(exchange, 200, runtime.chatService.anime(user.id, mediaId, page, perPage))
                }
                "POST" -> {
                    val body = readBody(exchange, 64 * 1024)
                        ?: return@withUser respond(exchange, 413, mapOf("error" to "request body is too large"))
                    val request = runCatching {
                        gson.fromJson(body.toString(StandardCharsets.UTF_8), SendChatMessageRequest::class.java)
                    }.getOrNull() ?: return@withUser respond(exchange, 400, mapOf("error" to "invalid chat message"))
                    val content = request.content?.trim().orEmpty()
                    if (content.isBlank() || content.length > 4000) {
                        return@withUser respond(exchange, 400, mapOf("error" to "content must contain 1 to 4000 characters"))
                    }
                    respond(exchange, 201, runtime.chatService.sendAnime(user.id, mediaId, content))
                }
                else -> methodNotAllowed(exchange, "GET, POST")
            }
        }
    }


}

data class CreateActivityRequest(val type: String? = null, val text: String? = null, val mediaId: Long? = null, val mediaTitle: String? = null)
data class CreateReplyRequest(val text: String? = null)
data class CreateCommentRequest(val mediaId: Long = 0L, val content: String? = null, val parentCommentId: String? = null, val tag: Int? = null)
data class EditCommentRequest(val content: String? = null)
data class VoteCommentRequest(val vote: Int? = null)
data class CreateSocialReportRequest(val targetUserId: String? = null, val targetContentId: String? = null, val type: String? = null, val description: String? = null)

data class AdminRoleRequest(val role: String? = null)
data class AdminUserActionRequest(
    val action: String? = null,
    val reason: String? = null,
    val suspendedUntil: String? = null
)
data class AdminReportUpdateRequest(
    val id: String? = null,
    val reporterId: String? = null,
    val targetUserId: String? = null,
    val targetContentId: String? = null,
    val type: String? = null,
    val description: String? = null,
    val status: String? = null,
    val assignedTo: String? = null,
    val createdAt: String? = null,
    val resolvedAt: String? = null
)

data class AdminAnnouncementRequest(
    val title: String? = null,
    val body: String? = null,
    val imageUrl: String? = null
)

data class CatalogImportSource(
    val url: String? = null,
    val provider: String? = null,
    val role: String? = null,
    val type: String? = null,
    val resolution: String? = null,
    val quality: String? = null,
    val episode: Int? = null,
    val sourcePage: String? = null,
    val source: String? = null,
    val label: String? = null
)

data class CatalogImportPayload(
    val directLinks: List<CatalogImportSource>? = null,
    val resolvers: List<CatalogImportSource>? = null,
    val extractors: List<CatalogImportSource>? = null,
    val sources: List<CatalogImportSource>? = null
)
