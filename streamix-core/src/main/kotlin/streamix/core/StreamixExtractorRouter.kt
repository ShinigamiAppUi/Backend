package streamix.core

import java.net.URI

/**
 * Pure JVM hostname-based extractor dispatcher.
 *
 * This is the Streamix equivalent of the provider-facing extractor selection
 * boundary. It knows nothing about CloudStream.
 */
class StreamixExtractorRouter(
    private val registry: StreamixExtractorRegistry
) {
    fun resolve(url: String): StreamixExtractor? = resolveAll(url).firstOrNull()

    fun resolveAll(url: String): List<StreamixExtractor> {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull()
            ?.removePrefix("www.")
            ?: return emptyList()

        return registry.all().filter { extractor ->
            extractor.domains.any { domainMatches(host, it) }
        }
    }

    suspend fun extract(request: StreamixExtractorRequest): StreamixExtractorResult {
        val extractor = resolve(request.url)
            ?: return StreamixExtractorResult()

        return extractor.extract(request)
    }

    private fun domainMatches(host: String, configured: String): Boolean {
        val domain = configured
            .lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')

        return host == domain || host.endsWith(".$domain")
    }
}
