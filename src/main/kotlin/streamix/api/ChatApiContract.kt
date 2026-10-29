package streamix.api

/**
 * Shinigami-owned chat and private messaging contract.
 *
 * Global Chat and Community are public rooms; Message is private 1:1 messaging.
 */
object ChatApiContract {
    const val GLOBAL_MESSAGES = "/api/v1/chat/global/messages"
    const val COMMUNITY_MESSAGES = "/api/v1/community/{mediaId}/messages"
    const val MESSAGES = "/api/v1/messages"
    const val MESSAGE_THREAD = "/api/v1/messages/{userId}"
    const val MESSAGE_READ = "/api/v1/messages/{userId}/read"
    const val MESSAGE_UNREAD_COUNT = "/api/v1/messages/unread-count"
}

enum class ChatRoomType { GLOBAL, ANIME, DIRECT }

data class ChatMessageRef(
    val id: String,
    val roomType: ChatRoomType,
    val sender: ShinigamiUser,
    val recipient: ShinigamiUser? = null,
    val mediaId: Long? = null,
    val content: String,
    val read: Boolean = false,
    val createdAt: String
)

data class MessageConversationRef(
    val user: ShinigamiUser,
    val lastMessage: ChatMessageRef,
    val unreadCount: Int
)

data class ChatPage<T>(
    val items: List<T>,
    val page: Int,
    val perPage: Int,
    val hasNextPage: Boolean
)

data class SendMessageRequest(val content: String)
data class SendChatMessageRequest(val content: String)
