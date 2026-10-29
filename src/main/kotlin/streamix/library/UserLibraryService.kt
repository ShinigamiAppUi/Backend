package streamix.library

import streamix.api.UserAnimeState
import streamix.api.UserLibraryPage

class UserLibraryService(
    private val repository: UserLibraryRepository
) {
    fun list(userId: String, page: Int = 1, perPage: Int = 50): UserLibraryPage {
        val safePage = page.coerceAtLeast(1)
        val safePerPage = perPage.coerceIn(1, 100)
        val fetched = repository.list(userId, safePage, safePerPage + 1)
        return UserLibraryPage(
            items = fetched.take(safePerPage),
            page = safePage,
            perPage = safePerPage,
            hasNextPage = fetched.size > safePerPage,
            total = null
        )
    }

    fun find(userId: String, mediaId: Long): UserAnimeState? =
        repository.find(userId, mediaId)

    fun upsert(userId: String, state: UserAnimeState): UserAnimeState =
        repository.upsert(userId, state)

    fun delete(userId: String, mediaId: Long) =
        repository.delete(userId, mediaId)
}
