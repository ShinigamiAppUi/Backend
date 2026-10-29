package streamix.core

/**
 * Platform-neutral browser-assisted resolution contract.
 *
 * The core describes navigation and intercepted requests only. Android/WebView
 * implementations stay behind this boundary.
 */
interface StreamixWebResolver {
    suspend fun resolve(
        request: StreamixWebRequest,
        onRequest: suspend (StreamixWebRequest) -> Boolean = { false }
    ): StreamixWebResult
}

data class StreamixWebRequest(
    val url: String,
    val referer: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val method: String = "GET"
)

data class StreamixWebResult(
    val intercepted: StreamixWebRequest? = null,
    val additionalRequests: List<StreamixWebRequest> = emptyList()
)
