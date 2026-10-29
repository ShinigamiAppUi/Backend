package streamix.chat

import streamix.api.ChatPage
import streamix.notification.NotificationService

class ChatService(
    private val repository: ChatRepository,
    private val notifications: NotificationService? = null
) {
    fun global(viewerId: String, page: Int = 1, perPage: Int = 30) =
        page(page, perPage) { p, size -> repository.global(viewerId, p, size) }
    fun anime(viewerId: String, mediaId: Long, page: Int = 1, perPage: Int = 30) =
        page(page, perPage) { p, size -> repository.anime(viewerId, mediaId, p, size) }
    fun sendGlobal(senderId: String, content: String) = repository.sendGlobal(senderId, content)
    fun sendAnime(senderId: String, mediaId: Long, content: String) = repository.sendAnime(senderId, mediaId, content)
    fun conversations(userId: String, page: Int = 1, perPage: Int = 30) =
        page(page, perPage) { p, size -> repository.conversations(userId, p, size) }
    fun messages(userId: String, otherUserId: String, page: Int = 1, perPage: Int = 30) =
        page(page, perPage) { p, size -> repository.messages(userId, otherUserId, p, size) }
    fun sendMessage(senderId: String, recipientId: String, content: String) =
        repository.sendMessage(senderId, recipientId, content).also { message ->
            notifications?.message(
                recipientId = recipientId,
                senderId = senderId,
                preview = message.content,
                messageId = message.id
            )
        }
    fun unreadCount(userId: String) = repository.unreadCount(userId)
    fun markRead(userId: String, otherUserId: String) = repository.markRead(userId, otherUserId)

    private fun <T> page(page: Int, perPage: Int, fetch: (Int, Int) -> List<T>): ChatPage<T> {
        val safePage = page.coerceAtLeast(1)
        val safePerPage = perPage.coerceIn(1, 100)
        val fetched = fetch(safePage, safePerPage + 1)
        return ChatPage(fetched.take(safePerPage), safePage, safePerPage, fetched.size > safePerPage)
    }
}
