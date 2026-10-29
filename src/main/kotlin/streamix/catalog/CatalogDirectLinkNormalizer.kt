package streamix.catalog

import java.net.URI

/**
 * Deterministic direct-link transformations stay inside the DIRECT contract.
 * No page scraping or host-specific link discovery belongs here.
 */
object CatalogDirectLinkNormalizer {
    fun normalize(sourceUrl: String): String {
        val uri = runCatching { URI(sourceUrl) }.getOrNull() ?: return sourceUrl
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return sourceUrl
        if (host != "pixeldrain.com" && host != "pixeldrain.lu") return sourceUrl

        val id = Regex("""^/u/([A-Za-z0-9_-]+)$""").find(uri.path.orEmpty())?.groupValues?.getOrNull(1)
            ?: return sourceUrl

        return "https://pixeldrain.com/api/file/" + id + "?download"
    }
}
