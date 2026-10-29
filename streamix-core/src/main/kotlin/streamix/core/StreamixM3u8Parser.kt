package streamix.core

import java.net.URI

/**
 * Minimal JVM HLS multivariant parser for Streamix extractors.
 *
 * This intentionally mirrors the extractor-facing behavior of CloudStream's
 * M3u8Helper/HlsPlaylistParser without importing the Android/Media3 runtime.
 * It parses master variants, preserves request headers/referer, filters
 * trick-play entries, and resolves variant URLs against the playlist URL.
 */
class StreamixM3u8Parser {
    fun parseMaster(
        text: String,
        baseUrl: String,
        providerId: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null
    ): List<ProviderStream> {
        val lines = text.lines().map(String::trim)
        val streams = mutableListOf<ProviderStream>()

        lines.forEachIndexed { index, line ->
            // CloudStream treats I-frame-only variants as trick-play rather
            // than normal playable video variants.
            if (line.startsWith("#EXT-X-I-FRAME-STREAM-INF:", true)) {
                return@forEachIndexed
            }

            if (!line.startsWith("#EXT-X-STREAM-INF:", true)) {
                return@forEachIndexed
            }

            val uri = lines
                .drop(index + 1)
                .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                ?: return@forEachIndexed

            val attributes = parseAttributes(line.substringAfter(':'))
            val quality = attributes["RESOLUTION"]?.let(::heightFromResolution)

            streams += ProviderStream(
                providerId = providerId,
                url = resolve(uri, baseUrl),
                quality = quality,
                type = "M3U8",
                headers = headers,
                referer = referer
            )
        }

        return streams.distinctBy { it.url }
    }

    private fun parseAttributes(value: String): Map<String, String> =
        Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")
            .findAll(value)
            .associate { it.groupValues[1] to it.groupValues[2].trim('"') }

    private fun heightFromResolution(value: String): Int? =
        value.substringAfter('x', "").toIntOrNull()

    /**
     * Resolve HLS variant references using RFC-3986 semantics, matching the
     * behavior used by CloudStream's HlsPlaylistParser UrlUtil.
     */
    private fun resolve(value: String, baseUrl: String): String {
        val base = runCatching { URI(baseUrl) }.getOrNull()
            ?: return value

        return runCatching { base.resolve(value).toString() }
            .getOrElse { value }
    }
}
