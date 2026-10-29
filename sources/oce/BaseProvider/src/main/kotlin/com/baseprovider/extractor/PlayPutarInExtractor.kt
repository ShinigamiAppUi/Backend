package com.baseprovider.extractor
import com.baseprovider.streamix.*
import java.net.URLDecoder
import java.net.URI

class PlayPutarIn : StreamixExtractorApi() {
    override var name = "PlayPutarIn"
    override var mainUrl = "https://play.putar.in"
    override val requiresReferer = true
    override val domains: Set<String> = setOf("play.putar.in", "puterin.biz", "putarin.biz", "putarin.xyz")

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (StreamixSubtitleFile) -> Unit, callback: (StreamixExtractorLink) -> Unit) {
        val queryTarget = url.substringAfter("?url=", "").takeIf { it.isNotBlank() }
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
        if (!queryTarget.isNullOrBlank() && queryTarget.startsWith("http")) {
            loadExtractorWithFallbackCustom(queryTarget, url, subtitleCallback, callback = callback,
                providerTag = name, callChain = "PlayPutarIn")
            return
        }

        val page = runCatching {
            app.get(url, referer = referer ?: "$mainUrl/",
                headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36",
                    "Accept" to "text/html,application/xhtml+xml"), timeout = 10_000L)
        }.getOrNull() ?: return

        val discovered = linkedSetOf<String>()

        // Some live mirrors redirect the short /e/{id} URL to the player
        // surface. Preserve the final URL as a resolver candidate.
        if (page.url.isNotBlank() && page.url != url &&
            ((page.url.contains("play.putar.in", true) ||
              page.url.contains("puterin", true) ||
              page.url.contains("putarin", true)) ||
             Regex("""(?i)\.(m3u8|mp4|m4v|webm)(?:[?#]|$)""").containsMatchIn(page.url))) {
            discovered += page.url
        }

        val redirectTarget = Regex("""(?i)(?:[?&](?:url|src|file|source)=)(https?%3A%2F%2F[^"'&\s]+|https?://[^"'&\s]+)""")
            .find(page.text)?.groupValues?.getOrNull(1)
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
        if (!redirectTarget.isNullOrBlank()) discovered += redirectTarget

        val marker = page.text
        val scriptUrls = Regex("""https?://[^"'\s<>]+""").findAll(marker)
            .map { it.value.replace("\\/", "/") }
            .filter {
                it.contains("putar", true) || it.contains("puterin", true) ||
                it.contains(".m3u8", true) || it.contains(".mp4", true) ||
                it.contains("player", true) || it.contains("embed", true)
            }
        scriptUrls.forEach { discovered += it }

        val normalized = page.text.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
        Regex("""https?://[^"'\s<>]+\.(?:m3u8|mp4|m4v|webm)(?:\?[^"'\s<>]*)?""", RegexOption.IGNORE_CASE)
            .findAll(normalized).forEach { discovered += it.value }
        page.document.select("video[src],video source[src],source[src],iframe[src],iframe[data-src]").forEach { el ->
            val raw = el.attr("src").ifBlank { el.attr("data-src") }.trim()
            if (raw.isNotBlank()) discovered += runCatching { URI(url).resolve(raw).toString() }.getOrDefault(raw)
        }

        discovered.forEach { target ->
            if (target.contains(".m3u8", true) || target.contains(".mp4", true) || target.contains(".m4v", true) || target.contains(".webm", true)) {
                callback(StreamixExtractorLink(
                    source = name, name = name, url = target,
                    type = if (target.contains(".m3u8", true)) StreamixExtractorLinkType.M3U8 else StreamixExtractorLinkType.VIDEO,
                    quality = Regex("""(?:^|[^0-9])(1080|720|480|360)p?""").find(target)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: Qualities.Unknown.value,
                    referer = url, headers = mapOf("Referer" to url)
                ))
            } else {
                loadExtractorWithFallbackCustom(target, url, subtitleCallback, callback = callback,
                    providerTag = name, callChain = "PlayPutarIn")
            }
        }
    }
}
