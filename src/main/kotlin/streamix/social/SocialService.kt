package streamix.social

import streamix.api.SocialPage

class SocialService(
    private val repository: SocialRepository
) {
    fun feed(viewerId: String, page: Int = 1, perPage: Int = 20) =
        page(repository.activities(viewerId, page, fetchSize(perPage)), page, perPage)

    fun activities(viewerId: String?, page: Int = 1, perPage: Int = 20) =
        page(repository.activities(viewerId, page, fetchSize(perPage)), page, perPage)

    fun activity(id: String, viewerId: String?) =
        repository.activity(id, viewerId)

    fun replies(activityId: String, viewerId: String?, page: Int = 1, perPage: Int = 20) =
        page(repository.replies(activityId, viewerId, page, fetchSize(perPage)), page, perPage)

    fun comment(commentId: String, viewerId: String?) = repository.findComment(commentId, viewerId)

    fun comments(mediaId: Long, viewerId: String?, parentCommentId: String?, page: Int = 1, perPage: Int = 20) =
        page(repository.comments(mediaId, viewerId, parentCommentId, page, fetchSize(perPage)), page, perPage)

    fun commentReplies(commentId: String, viewerId: String?, page: Int = 1, perPage: Int = 20) =
        page(repository.commentReplies(commentId, viewerId, page, fetchSize(perPage)), page, perPage)

    fun notifications(userId: String, page: Int = 1, perPage: Int = 20) =
        page(repository.notifications(userId, page, fetchSize(perPage)), page, perPage)

    fun unreadNotificationCount(userId: String) =
        repository.unreadNotificationCount(userId)

    fun createActivity(authorId: String, type: String, text: String?, mediaId: Long?, mediaTitle: String?) =
        repository.createActivity(authorId, type, text, mediaId, mediaTitle)

    fun deleteActivity(activityId: String, actorId: String) =
        repository.deleteActivity(activityId, actorId)

    fun createReply(activityId: String, authorId: String, text: String) =
        repository.createReply(activityId, authorId, text)

    fun likeActivity(activityId: String, userId: String) =
        repository.toggleActivityLike(activityId, userId)

    fun subscribeActivity(activityId: String, userId: String) =
        repository.toggleActivitySubscription(activityId, userId)

    fun createComment(mediaId: Long, authorId: String, content: String, parentCommentId: String?, tag: Int? = null) =
        repository.createComment(mediaId, authorId, content, parentCommentId, tag)

    fun deleteComment(commentId: String, actorId: String) =
        repository.deleteComment(commentId, actorId)

    fun editComment(commentId: String, actorId: String, content: String) =
        repository.editComment(commentId, actorId, content)

    fun voteComment(commentId: String, userId: String, vote: Int?) =
        repository.voteComment(commentId, userId, vote)

    fun markNotificationRead(notificationId: String, userId: String) =
        repository.markNotificationRead(notificationId, userId)

    private fun fetchSize(perPage: Int): Int = perPage.coerceIn(1, 100) + 1

    private fun <T> page(items: List<T>, page: Int, perPage: Int): SocialPage<T> {
        val safePage = page.coerceAtLeast(1)
        val safePerPage = perPage.coerceIn(1, 100)
        return SocialPage(
            items = items.take(safePerPage),
            page = safePage,
            perPage = safePerPage,
            hasNextPage = items.size > safePerPage,
            total = null
        )
    }
}
