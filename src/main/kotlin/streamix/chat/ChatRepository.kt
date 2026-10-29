package streamix.chat

import streamix.api.ChatMessageRef
import streamix.api.MessageConversationRef

interface ChatRepository {
    fun global(viewerId: String, page: Int, perPage: Int): List<ChatMessageRef>
    fun anime(viewerId: String, mediaId: Long, page: Int, perPage: Int): List<ChatMessageRef>
    fun sendGlobal(senderId: String, content: String): ChatMessageRef
    fun sendAnime(senderId: String, mediaId: Long, content: String): ChatMessageRef
    fun conversations(userId: String, page: Int, perPage: Int): List<MessageConversationRef>
    fun messages(userId: String, otherUserId: String, page: Int, perPage: Int): List<ChatMessageRef>
    fun sendMessage(senderId: String, recipientId: String, content: String): ChatMessageRef
    fun unreadCount(userId: String): Int
    fun markRead(userId: String, otherUserId: String): Int
}
