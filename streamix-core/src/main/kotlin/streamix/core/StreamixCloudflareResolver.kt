package streamix.core

interface StreamixCloudflareResolver {
    suspend fun solve(url: String, referer: String? = null): StreamixCloudflareResult
}

data class StreamixCloudflareResult(
    val solved: Boolean,
    val cookies: Map<String, String> = emptyMap(),
    val userAgent: String? = null
)
