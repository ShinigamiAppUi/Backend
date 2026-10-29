package com.baseprovider.extractor

import com.google.gson.annotations.SerializedName

data class ByseDetailsRoot(
    val id: Long,
    val code: String,
    val title: String,
    @SerializedName("poster_url") val posterUrl: String,
    val description: String,
    @SerializedName("embed_frame_url") val embedFrameUrl: String
)

data class BysePlaybackRoot(val playback: BysePlayback)
data class BysePlayback(
    val algorithm: String,
    val iv: String,
    val payload: String,
    @SerializedName("key_parts") val keyParts: List<String>
)

data class BysePlaybackDecrypt(val sources: List<BysePlaybackSource>)
data class BysePlaybackSource(val quality: String, val label: String,
    val url: String)
