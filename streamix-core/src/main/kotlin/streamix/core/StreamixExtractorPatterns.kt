package streamix.core

/** Pure JVM extractor parsing/quality helpers. */
object StreamixExtractorPatterns {
    val M3U8_STREAM_INFO = Regex("#EXT-X-STREAM-INF")
    val RUMBLE_URL_PATTERN = Regex("""\"url\":\"(.*?)\"|h\":(.*?)\}""")
    val DAILYMOTION_VIDEO_URL = Regex("""\"url\"\s*:\s*\"([^\"]+)\"""")
    val DAILYMOTION_SUBTITLE = Regex("""\{\s*"label"\s*:\s*"([^"]+)",\s*"urls"\s*:\s*\["([^"]+)"""")
    val ARCHIVE_ORG_URL = Regex("""\"url\":\"(.*?)\"""")
    val UNIVERSAL_VIDEO_URL = Regex("""\"([^\"]*?\.(?:mp4|m3u8|mkv|mpd|webm|ts|mov)(?:\?[^\"]*?)?)\"""")

    val MLG_QUALITY_1080 = Regex("(1080|p1080|fhd|fullhd)", RegexOption.IGNORE_CASE)
    val MLG_QUALITY_720 = Regex("(720|p720|hd)", RegexOption.IGNORE_CASE)
    val MLG_QUALITY_480 = Regex("(480|p480|sd)", RegexOption.IGNORE_CASE)
    val MLG_QUALITY_360 = Regex("(360|p360)", RegexOption.IGNORE_CASE)

    fun extractAllVideoUrls(text: String): Set<String> =
        UNIVERSAL_VIDEO_URL.findAll(text).mapNotNull { match ->
            val url = match.groupValues[1].replace("\\/", "/").trim()
            when {
                url.startsWith("http") -> url
                url.startsWith("//") -> "https:$url"
                else -> null
            }
        }.toSet()

    fun filterMasterM3u8(urls: Collection<String>): List<String> {
        if (urls.isEmpty()) return emptyList()
        val adaptive = urls.filter { it.contains(".m3u8") || it.contains(".mpd") }
        if (adaptive.isEmpty()) return urls.toList()
        val masters = adaptive.filter {
            it.contains("master", true) || it.contains("manifest", true) || it.contains("playlist", true)
        }
        return if (masters.isNotEmpty()) masters.distinct() else listOf(adaptive.first())
    }

    fun prioritizeAdaptiveUrls(urls: Collection<String>): List<String> {
        if (urls.isEmpty()) return emptyList()
        val m3u8s = urls.filter { it.contains(".m3u8", true) }
        if (m3u8s.isNotEmpty()) {
            val masters = m3u8s.filter {
                it.contains("master", true) || it.contains("manifest", true) || it.contains("playlist", true)
            }
            return if (masters.isNotEmpty()) masters.distinct() else listOf(m3u8s.first())
        }
        val mpds = urls.filter { it.contains(".mpd", true) }
        if (mpds.isNotEmpty()) return mpds.distinct()
        return urls.distinct()
    }

    fun detectQualityFromUrl(url: String): Int = when {
        MLG_QUALITY_1080.containsMatchIn(url.lowercase()) -> 1080
        MLG_QUALITY_720.containsMatchIn(url.lowercase()) -> 720
        MLG_QUALITY_480.containsMatchIn(url.lowercase()) -> 480
        MLG_QUALITY_360.containsMatchIn(url.lowercase()) -> 360
        else -> 480
    }

    fun getQualityFromName(name: String?): Int = when {
        name == null -> 480
        name.contains("1080", true) || name.contains("fhd", true) -> 1080
        name.contains("720", true) || name.contains("hd", true) -> 720
        name.contains("480", true) || name.contains("sd", true) -> 480
        else -> 360
    }
}
