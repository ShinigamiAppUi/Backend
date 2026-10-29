package streamix.readingcatalog

import java.net.URI
import org.jsoup.nodes.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext




internal fun fetchNovelDocument(value: String): Document? =
    fetchReadingHtmlDocument(
        value,
        mapOf("Accept" to "text/html,application/xhtml+xml"),
        maxBytes = MAX_NOVEL_RESPONSE_BYTES,
    )

/** Read an upstream chapter body with a hard cap to avoid unbounded response buffering. */
internal fun readBoundedNovelResponse(input: java.io.InputStream, maxBytes: Int): ByteArray? {
    require(maxBytes > 0) { "maxBytes must be positive" }
    val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) {
            // Some unusual streams may report zero for a non-empty read request.
            // Fall back to a single-byte read so the bounded loop cannot spin forever.
            val single = input.read()
            if (single < 0) break
            if (total == maxBytes) return null
            output.write(single)
            total++
            continue
        }
        if (count > maxBytes - total) return null
        output.write(buffer, 0, count)
        total += count
    }
    return output.toByteArray()
}

private const val MAX_NOVEL_RESPONSE_BYTES = 2 * 1024 * 1024
