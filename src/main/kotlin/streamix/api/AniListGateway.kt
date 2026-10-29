package streamix.api

/**
 * Injectable AniList boundary used by the service and HTTP API.
 * Production uses [AniListProxy]; deterministic tests can provide a fixture.
 */
interface AniListGateway {
    fun search(query: String, page: Int = 1): List<ProviderAnime>
    fun detail(anilistId: Long): ProviderAnime?
    fun execute(body: ByteArray, authorization: String?): AniListProxy.Response
}
