package streamix.api

/**
 * Stable HTTP contract for the Shinigami client.
 *
 * Provider pool/update endpoints are intentionally absent.
 */
object BackendApiContract {
    const val ANILIST_GRAPHQL = "/api/v1/anilist/graphql"
    const val SEARCH = "/api/v1/anime/search"
    const val DETAIL = "/api/v1/anime/{id}"
    const val EPISODES = "/api/v1/anime/{id}/episodes"
    const val STREAMS = "/api/v1/anime/{id}/episode/{number}/streams"
    const val VIEWS = "/api/v1/anime/{id}/views"
    const val EPISODE_PLAYBACK = "/api/v1/anime/{id}/episode/{number}/playback"
    const val EXTRACTORS = "/api/v1/extractors"
    const val HEALTH = "/api/v1/health"
    const val READING = "/api/v1/reading"
    const val READING_METADATA_SEARCH = "/api/v1/reading/metadata/search"
    const val READING_METADATA_DETAIL = "/api/v1/reading/metadata/{id}"
    const val READING_METADATA = "/api/v1/reading/metadata"
    const val READING_CATALOG = "/api/v1/admin/reading/catalog"
}
