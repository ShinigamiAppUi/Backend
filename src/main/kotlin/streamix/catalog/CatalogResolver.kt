package streamix.catalog

import streamix.api.StreamRef

/**
 * Host/page resolver used only by catalog RESOLVER sources.
 *
 * A resolver converts a stable container/page URL into one or more media
 * candidates. It does not decide whether playback is usable; Media3 remains
 * the final runtime authority for that.
 */
interface CatalogResolver {
    val id: String
    val domains: Set<String>

    suspend fun resolve(source: EpisodeSource): CatalogResolverResult
}

data class CatalogResolverResult(
    val streams: List<StreamRef> = emptyList(),
    val httpStatus: Int? = null,
    val finalUrl: String? = null,
    val failureReason: String? = null
) {
    val succeeded: Boolean
        get() = streams.isNotEmpty()
}

class CatalogResolverRegistry(
    resolvers: List<CatalogResolver> = emptyList()
) {
    private val entries = resolvers.toList()

    fun all(): List<CatalogResolver> = entries

    fun resolveAll(sourceUrl: String): List<CatalogResolver> {
        val host = runCatching {
            java.net.URI(sourceUrl).host?.lowercase()?.removePrefix("www.")
        }.getOrNull() ?: return emptyList()

        return entries.filter { resolver ->
            resolver.domains.any { configured ->
                val domain = configured
                    .lowercase()
                    .removePrefix("https://")
                    .removePrefix("http://")
                    .substringBefore('/')
                host == domain || host.endsWith(".$domain")
            }
        }
    }
}
