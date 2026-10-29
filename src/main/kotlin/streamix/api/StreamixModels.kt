package streamix.api

data class ProviderAnime(
    val providerId: String,
    val id: String,
    val title: String,
    val url: String = id,
    val poster: String? = null,
    val banner: String? = null,
    val description: String? = null,
    val year: Int? = null,
    val status: String? = null,
    val tags: List<String> = emptyList(),
    val rating: String? = null,
    val trailer: String? = null
)

data class EpisodeRef(
    val providerId: String,
    val number: Int,
    val title: String? = null,
    val providerEpisodeId: String,
    val url: String
)

data class SubtitleRef(
    val url: String,
    val language: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val referer: String? = null
)

data class StreamRef(
    val providerId: String,
    val url: String,
    val quality: Int? = null,
    val type: String? = null,
    val language: String? = null,
    val subtitleLanguage: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val referer: String? = null,
    val subtitles: List<SubtitleRef> = emptyList()
)
