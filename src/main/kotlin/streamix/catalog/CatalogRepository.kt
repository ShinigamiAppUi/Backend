package streamix.catalog

interface CatalogRepository {
    fun listEpisodes(anilistId: Long): List<CatalogEpisode>
    fun getEpisode(anilistId: Long, number: Int): CatalogEpisode?
    fun upsertSource(source: EpisodeSource): EpisodeSource
    fun upsertSources(sources: List<EpisodeSource>): List<EpisodeSource> = sources.map(::upsertSource)
    fun deleteSource(anilistId: Long, episodeNumber: Int, sourceId: String): Boolean
    fun updateSourceHealth(
        anilistId: Long,
        episodeNumber: Int,
        sourceId: String,
        health: SourceHealth,
        checkedAt: Long,
        httpStatus: Int? = null,
        failureReason: String? = null
    ): EpisodeSource?
}
