package streamix.runtime

import streamix.api.StreamRef

/** Playback boundary for the dedicated Animein API adapter. */
fun interface AnimeinStreamProvider {
    suspend fun streams(title: String, episodeNumber: Int): List<StreamRef>
}

/** Website-provider fallback boundary kept separate from Catalog and Animein. */
fun interface RuntimeWebsiteFallback {
    suspend fun streams(title: String, episodeNumber: Int): List<StreamRef>

    /**
     * Retry-aware overload. Existing implementations remain source-compatible;
     * the production fallback overrides this to skip failed provider sources
     * before racing the remaining sites.
     */
    suspend fun streams(
        title: String,
        episodeNumber: Int,
        failedSourceIds: Set<String>
    ): List<StreamRef> =
        streams(title, episodeNumber).filterNot { it.providerId in failedSourceIds || it.url in failedSourceIds }
}
