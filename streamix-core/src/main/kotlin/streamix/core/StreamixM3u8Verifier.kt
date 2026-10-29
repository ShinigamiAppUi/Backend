package streamix.core

import java.net.URI

/** Pure JVM HLS master playlist parser/verifier primitives. */
object StreamixM3u8Verifier {
    data class MasterVariant(val url: String?, val bandwidth: Long, val height: Int?)
    sealed class Verdict {
        data object Clean : Verdict()
        data class Valid(val variants: List<Pair<String, Int?>>) : Verdict()
        data object AllMalformed : Verdict()
    }

    private val bandwidthRegex = Regex("""BANDWIDTH=(\d+)""")
    private val resolutionRegex = Regex("""RESOLUTION=\d+x(\d+)""")

    fun parseVariants(masterText: String): List<MasterVariant> {
        val variants = mutableListOf<MasterVariant>()
        val lines = masterText.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val bandwidth = bandwidthRegex.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val height = resolutionRegex.find(line)?.groupValues?.get(1)?.toIntOrNull()
                val uri = if (i + 1 < lines.size) lines[i + 1].trim() else ""
                val malformed = uri.isBlank() || uri.startsWith("#")
                variants += MasterVariant(if (malformed) null else uri, bandwidth, height)
                i += 2
            } else i++
        }
        return variants
    }

    fun classify(masterUrl: String, parsed: List<MasterVariant>): Verdict {
        if (parsed.isEmpty()) return Verdict.Clean
        val valid = parsed.mapNotNull { variant ->
            val raw = variant.url ?: return@mapNotNull null
            val resolved = resolveUrl(masterUrl, raw)
            if (resolved == masterUrl || resolved == masterUrl.trimEnd('/')) null
            else resolved to variant.height
        }
        return when {
            valid.isEmpty() -> Verdict.AllMalformed
            valid.size < parsed.size -> Verdict.Valid(valid)
            else -> Verdict.Clean
        }
    }

    fun resolveUrl(baseUrl: String, path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val parsed = runCatching { URI(baseUrl) }.getOrNull() ?: return path
        val origin = buildString {
            append(parsed.scheme ?: "https")
            append("://")
            append(parsed.host.orEmpty())
            if (parsed.port > 0 && parsed.port != 80 && parsed.port != 443) append(":")
            if (parsed.port > 0 && parsed.port != 80 && parsed.port != 443) append(parsed.port)
        }
        if (path.startsWith("/")) return origin + path
        val dir = parsed.path.orEmpty().substringBeforeLast('/', "")
        return origin + dir + "/" + path
    }
}
