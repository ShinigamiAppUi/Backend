package streamix.library

import streamix.api.UserAnimeState

interface UserLibraryRepository {
    fun list(userId: String, page: Int = 1, perPage: Int = 50): List<UserAnimeState>
    fun find(userId: String, mediaId: Long): UserAnimeState?
    fun upsert(userId: String, state: UserAnimeState): UserAnimeState
    fun delete(userId: String, mediaId: Long)
}
