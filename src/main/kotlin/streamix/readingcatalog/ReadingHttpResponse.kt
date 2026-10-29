package streamix.readingcatalog

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Hard cap for upstream reading-provider response bodies (HTML and JSON). */
internal const val MAX_READING_HTTP_RESPONSE_BYTES: Int = 2 * 1024 * 1024

/**
 * Reads an upstream response without unbounded buffering. Returns null when the
 * response exceeds the cap. Handles streams that temporarily return zero progress.
 * The caller owns and closes [input].
 */
internal fun readBoundedReadingResponse(
    input: InputStream,
    maxBytes: Int = MAX_READING_HTTP_RESPONSE_BYTES
): ByteArray? {
    require(maxBytes > 0) { "maxBytes must be positive" }
    val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val count = input.read(buffer, 0, minOf(buffer.size, maxBytes - total + 1))
        if (count < 0) return output.toByteArray()
        if (count == 0) {
            val next = input.read()
            if (next < 0) return output.toByteArray()
            if (total >= maxBytes) return null
            output.write(next)
            total++
            continue
        }
        if (count > maxBytes - total) return null
        output.write(buffer, 0, count)
        total += count
    }
}
