package com.baseprovider.extractor
import com.baseprovider.streamix.*
import java.net.URLEncoder

class BloggerVideo : StreamixExtractorApi() {
    override var name = "BloggerVideo"
    override var mainUrl = "https://www.blogger.com"
    override val domains: Set<String> = setOf("blogger.com", "blogspot.com")
    override val requiresReferer = true
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36"

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (StreamixSubtitleFile) -> Unit, callback: (StreamixExtractorLink) -> Unit) {
        val token = Regex("""[?&]token=([A-Za-z0-9_-]+)""").find(url)?.groupValues?.getOrNull(1) ?: return
        val page = runCatching {
            app.get(url, referer = referer ?: "$mainUrl/",
                headers = mapOf("User-Agent" to userAgent, "Accept" to "text/html,application/xhtml+xml"),
                timeout = 12_000L)
        }.getOrNull() ?: return
        val html = page.text
        val sid = Regex(""""FdrFJe"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
        val build = Regex(""""cfb2h"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
        val at = Regex(""""SNlM0e"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.getOrNull(1).orEmpty()
        emitGoogleVideoUrls(html, callback)
        if (sid.isNullOrBlank() || build.isNullOrBlank()) return

        val innerJson = """["$token","",0]"""
        val fReq = """[[["WcwnYd","${escapeJson(innerJson)}",null,"generic"]]]""".replace("$", "$")
        val batchUrl = "$mainUrl/_/BloggerVideoPlayerUi/data/batchexecute" +
            "?rpcids=WcwnYd&source-path=%2Fvideo.g" +
            "&f.sid=" + URLEncoder.encode(sid, "UTF-8") +
            "&bl=" + URLEncoder.encode(build, "UTF-8") +
            "&hl=en-US&_reqid=100001&rt=c"

        val response = runCatching {
            app.post(batchUrl,
                data = mapOf("f.req" to fReq) + if (at.isNotBlank()) mapOf("at" to at) else emptyMap(),
                referer = url,
                headers = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8",
                    "X-Same-Domain" to "1", "Origin" to mainUrl, "User-Agent" to userAgent
                ),
                timeout = 12_000L)
        }.getOrNull() ?: return
        emitGoogleVideoUrls(response.text, callback)
    }

    private fun escapeJson(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun emitGoogleVideoUrls(source: String, callback: (StreamixExtractorLink) -> Unit) {
        val normalized = source.replace("\\/", "/").replace("\\u003d", "=").replace("\\u0026", "&").replace("&amp;", "&")
        Regex("""https://[^"'<\s]+?\.googlevideo\.com/[^"'<\s]+""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .map { it.value.trimEnd('\\', '"', '\'', ')', ',', ';') }
            .distinct().sortedByDescending { quality(it) }
            .forEach { streamUrl ->
                callback(StreamixExtractorLink(
                    source = name, name = name, url = streamUrl,
                    type = StreamixExtractorLinkType.VIDEO, quality = quality(streamUrl),
                    referer = "$mainUrl/",
                    headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to userAgent)
                ))
            }
    }

    private fun quality(url: String): Int = when {
        "itag=22" in url -> Qualities.P720.value
        "itag=18" in url -> Qualities.P360.value
        Regex("""(?:^|[^0-9])1080p?""").containsMatchIn(url) -> Qualities.P1080.value
        Regex("""(?:^|[^0-9])720p?""").containsMatchIn(url) -> Qualities.P720.value
        Regex("""(?:^|[^0-9])480p?""").containsMatchIn(url) -> Qualities.P480.value
        else -> Qualities.Unknown.value
    }
}
