package streamix.runtime

import streamix.api.AniListGateway
import streamix.api.EpisodeRef
import streamix.api.ProviderAnime
import streamix.api.StreamRef
import streamix.catalog.CatalogService
import kotlinx.coroutines.CancellationException

/**
 * Shinigami production streaming runtime.
 *
 * AniList owns anime identity/metadata. Playback source order is Catalog first,
 * then the dedicated Animein API adapter, then website RuntimeProviderFallback.
 * The API provider and website runtime are separate boundaries.
 */
class StreamixService(
    private val aniList: AniListGateway = streamix.api.AniListProxy(),
    private val catalog: CatalogService,
    private val runtimeProviderFallback: RuntimeWebsiteFallback? = null,
    private val animeinApiProvider: AnimeinStreamProvider? = null
) : AutoCloseable {
    override fun close() = Unit

    suspend fun search(query: String, page: Int = 1): List<ProviderAnime> = aniList.search(query, page)

    suspend fun detail(anilistId: Long): List<ProviderAnime> =
        aniList.detail(anilistId)?.let(::listOf).orEmpty()

    suspend fun episodes(anilistId: Long): List<EpisodeRef> =
        catalog.episodes(anilistId).map { episode ->
            EpisodeRef(
                providerId = "catalog",
                number = episode.number,
                title = episode.title,
                providerEpisodeId = "catalog:" + episode.number,
                url = episode.sources.firstOrNull()?.sourceUrl.orEmpty()
            )
        }

    suspend fun refreshEpisodes(anilistId: Long): List<EpisodeRef> = episodes(anilistId)

    suspend fun streams(
        anilistId: Long,
        episodeNumber: Int,
        failedCatalogSourceIds: Set<String> = emptySet()
    ): List<StreamRef> {
        // Android currently reports StreamRef.providerId values (for example
        // "catalog:<source-id>"), while CatalogService indexes bare source IDs.
        // Accept both forms without changing the HTTP query contract.
        val failedSourceIds = failedCatalogSourceIds.filter { it.isNotBlank() }.toSet()
        val failedCatalogIds = failedSourceIds.map { it.removePrefix("catalog:") }.toSet()
        val catalogStreams = catalog.streams(anilistId, episodeNumber, failedCatalogIds)
            .filterNot { ref ->
                ref.providerId in failedSourceIds ||
                    ref.url in failedSourceIds ||
                    ref.providerId.removePrefix("catalog:") in failedCatalogIds
            }
        if (catalogStreams.isNotEmpty()) return catalogStreams

        val title = aniList.detail(anilistId)?.title?.trim().orEmpty()
        if (title.isBlank()) return emptyList()

        val animeinStreams = if ("animein-api" in failedSourceIds) {
            emptyList()
        } else try {
            animeinApiProvider?.streams(title, episodeNumber).orEmpty()
                .filterNot { it.providerId in failedSourceIds || it.url in failedSourceIds }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            emptyList()
        }
        if (animeinStreams.isNotEmpty()) return animeinStreams

        return runtimeProviderFallback
            ?.streams(title, episodeNumber, failedSourceIds)
            .orEmpty()
    }

    suspend fun providerEpisodes(providerId: String, providerAnimeId: String): List<EpisodeRef> = emptyList()
    suspend fun providerStreams(episode: EpisodeRef): List<StreamRef> = emptyList()
}
