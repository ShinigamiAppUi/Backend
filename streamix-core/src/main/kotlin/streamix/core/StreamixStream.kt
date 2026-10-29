package streamix.core

/**
 * Provider/extractor-neutral stream produced by the JVM runtime.
 *
 * This model intentionally contains no CloudStream or Android types.
 */
data class StreamixStream(
    val source: String,
    val name: String,
    val url: String,
    val quality: Int? = null,
    val type: StreamixStreamType = StreamixStreamType.Video,
    val referer: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val extractorData: String? = null
)
