package com.baseprovider.extractor

import com.baseprovider.streamix.StreamixExtractorApi
import com.baseprovider.streamix.StreamixExtractorLink
import com.baseprovider.streamix.StreamixExtractorLinkType
import com.baseprovider.streamix.app
import com.baseprovider.streamix.StreamixSubtitleFile
import com.baseprovider.streamix.StreamixLogger
import com.baseprovider.streamix.Qualities
import org.jsoup.nodes.Element

/**
 * Small, reusable extractors that are not tied to a single anime provider.
 * Provider-specific extraction remains in the provider itself.
 */
class ArchiveOrg : StreamixExtractorApi() {
    override val name = "ArchiveOrg"
    override val mainUrl = "https://archive.org"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (StreamixSubtitleFile) -> Unit,
        callback: (StreamixExtractorLink) -> Unit
    ) {
        callback(
            StreamixExtractorLink(
                source = name,
                name = name,
                url = url,
                type = StreamixExtractorLinkType.VIDEO,
                quality = Qualities.Unknown.value,
                referer = referer ?: mainUrl
            )
        )
    }
}

class TurboVid : StreamixExtractorApi() {
    override val name = "TurboVid"
    override val mainUrl = "https://turbovidhls.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (StreamixSubtitleFile) -> Unit,
        callback: (StreamixExtractorLink) -> Unit
    ) {
        val response = runCatching {
            app.get(
                url,
                referer = referer ?: "$mainUrl/",
                headers = mapOf(
                    "Referer" to "$mainUrl/",
                    "Origin" to mainUrl
                )
            )
        }.getOrNull() ?: return

        val script = response.document
            .select("script")
            .firstOrNull { it.data().contains("var urlPlay") || it.html().contains("var urlPlay") }
            ?.html()
            .orEmpty()

        if (script.isBlank()) return

        var master = script.substringAfter("var urlPlay = '", "")
            .substringBefore("'")
            .trim()

        if (master.isBlank()) return
        master = when {
            master.startsWith("//") -> "https:$master"
            master.startsWith("/") -> mainUrl + master
            else -> master
        }

        callback(
            StreamixExtractorLink(
                source = name,
                name = name,
                url = master,
                type = StreamixExtractorLinkType.M3U8,
                quality = Qualities.Unknown.value,
                referer = "$mainUrl/",
                headers = mapOf(
                    "Referer" to "$mainUrl/",
                    "Origin" to mainUrl
                )
            )
        )
    }
}


class GenericPlayerPage : StreamixExtractorApi() {
    override val name = "GenericPlayerPage"
    override val mainUrl = "https://mp4upload.com"
    override val requiresReferer = true
    override val domains = setOf(
        "mp4upload.com",
        "puterin.biz",
        "upbolt.to",
        "acefile.co"
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (StreamixSubtitleFile) -> Unit,
        callback: (StreamixExtractorLink) -> Unit
    ) {
        val response = runCatching {
            app.get(
                url,
                referer = referer,
                headers = mapOf("Referer" to (referer ?: url), "User-Agent" to "Mozilla/5.0"),
                timeout = 20_000L
            )
        }.getOrNull() ?: return

        val scripts = response.document.select("script").joinToString("\n") { it.data() + "\n" + it.html() }
        val patterns = listOf(
            Regex("""(?:file|src|source|videoUrl)\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4)(?:\?[^"']*)?)["']""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^\s"'<>]+\.(?:m3u8|mp4)(?:\?[^\s"'<>]*)?""", RegexOption.IGNORE_CASE)
        )
        val found = linkedSetOf<String>()
        for (pattern in patterns) {
            pattern.findAll(scripts).forEach { match ->
                val candidate = match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: match.value
                found += candidate.replace("\\/", "/")
            }
        }
        found.forEach { media ->
            callback(
                StreamixExtractorLink(
                    source = name,
                    name = name,
                    url = media,
                    type = if (media.contains(".m3u8", true)) StreamixExtractorLinkType.M3U8 else StreamixExtractorLinkType.VIDEO,
                    quality = Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE).find(media)?.groupValues?.get(1)?.toIntOrNull() ?: Qualities.Unknown.value,
                    referer = referer ?: url,
                    headers = mapOf("Referer" to (referer ?: url))
                )
            )
        }
    }
}
