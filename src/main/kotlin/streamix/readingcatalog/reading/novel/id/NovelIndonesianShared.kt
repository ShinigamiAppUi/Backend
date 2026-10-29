package streamix.readingcatalog

import org.jsoup.nodes.Document

internal fun novelHost(value: String, hosts: Set<String>): Boolean =
    runCatching {
        java.net.URI(value).let {
            it.scheme == "https" &&
                hosts.any { host -> it.host.equals(host, true) } &&
                it.userInfo == null &&
                it.port in setOf(-1, 443)
        }
    }.getOrDefault(false)

/**
 * Shared HTML fetch path for novel providers. Apply the same response-size ceiling
 * used by the dedicated novel document fetcher.
 */
internal fun fetchNovelHtml(value: String): Document? = fetchReadingHtmlDocument(
    value,
    mapOf("User-Agent" to "ShinigamiReadingRuntime/1.0", "Accept" to "text/html,application/xhtml+xml"),
    maxBytes = 2 * 1024 * 1024,
)
