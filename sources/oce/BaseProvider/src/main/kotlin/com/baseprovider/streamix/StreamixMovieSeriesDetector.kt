package com.baseprovider.streamix

import com.baseprovider.config.ProviderConfig
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import streamix.core.StreamixMediaType

/**
 * Native JVM movie/series detector.
 * Mirrors provider-content signals without CloudStream models.
 */
data class StreamixDetection(
    val isMovie: Boolean,
    val effectiveEpisodes: Elements,
    val reason: String,
    val rawEpisodeCount: Int,
    val validEpisodeCount: Int
)

object StreamixMovieSeriesDetector {
    private val strongEpisodePattern =
        Regex("""(?i)(?:/eps/|/episode/|/ep/|-episode-|/ep-)""")

    fun detect(
        url: String,
        config: ProviderConfig,
        configuredEpisodes: Elements,
        hasPlayer: Boolean,
        seasonContainer: Element?,
        fallbackEpisodes: Elements
    ): StreamixDetection {
        val (valid, _) = validateEpisodes(configuredEpisodes, url)

        if (valid.isNotEmpty()) {
            return StreamixDetection(
                isMovie = false,
                effectiveEpisodes = valid,
                reason = "configured_episodes_validated",
                rawEpisodeCount = configuredEpisodes.size,
                validEpisodeCount = valid.size
            )
        }

        if (seasonContainer != null) {
            return StreamixDetection(false, configuredEpisodes, "season_container",
                configuredEpisodes.size, 0)
        }

        if (fallbackEpisodes.isNotEmpty()) {
            return StreamixDetection(false, fallbackEpisodes, "detected_episode_links",
                fallbackEpisodes.size, fallbackEpisodes.size)
        }

        val strongTv =
            (config.tvPathSegment.isNotBlank() && url.contains(config.tvPathSegment, true)) ||
                listOf("/tv/", "/series/", "/anime/", "/drama/", "/episode/", "/eps/")
                    .any { url.contains(it, true) }

        if (strongTv) {
            return StreamixDetection(false, Elements(), "strong_tv_url", 0, 0)
        }

        val supportsMovies = config.supportedTypes.contains(StreamixMediaType.Movie)
        if (hasPlayer && supportsMovies) {
            return StreamixDetection(true, Elements(), "movie_player_only", 0, 0)
        }

        return StreamixDetection(true, Elements(), "movie_no_episodes", 0, 0)
    }

    private fun validateEpisodes(items: Elements, url: String): Pair<Elements, Int> {
        if (items.isEmpty()) return Elements() to 0

        val slug = runCatching {
            java.net.URI(url).path?.trim('/')?.substringAfterLast('/') ?: ""
        }.getOrDefault("")

        val valid = Elements()
        var invalid = 0

        for (episode in items) {
            val anchor = episode.selectFirst("a[href]")
                ?: episode.takeIf { it.tagName() == "a" }

            if (anchor == null) {
                invalid++
                continue
            }

            val href = anchor.attr("href").trim()
            if (href.isBlank() || href == "#") {
                invalid++
                continue
            }

            val absolute = runCatching {
                java.net.URI(url).resolve(href).toString()
            }.getOrDefault(href)

            if (absolute.trimEnd('/') == url.trimEnd('/')) {
                invalid++
                continue
            }

            val slugMatch = slug.isNotBlank() && absolute.contains(slug, true)
            val strongMatch = strongEpisodePattern.containsMatchIn(absolute)

            if (slugMatch || strongMatch) valid.add(episode) else invalid++
        }

        return valid to invalid
    }
}
