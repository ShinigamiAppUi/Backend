package com.baseprovider.extractor

import com.baseprovider.streamix.*

class KuramadriveExtractor : StreamixExtractorApi() {
    override val name = "DriveKurama"
    override val mainUrl = "https://kuramadrive.com"
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (StreamixSubtitleFile) -> Unit, callback: (StreamixExtractorLink) -> Unit) {
        val req = app.get(url, referer = referer)
        val token = Regex("""<meta[^>]+name=["']csrf-token["'][^>]+content=["']([^"']+)""", RegexOption.IGNORE_CASE).find(req.text)?.groupValues?.get(1) ?: return
        val route = Regex("""<input[^>]+id=["']routeCheckAvl["'][^>]+value=["']([^"']+)""", RegexOption.IGNORE_CASE).find(req.text)?.groupValues?.get(1) ?: return
        val json = app.get(route, headers = mapOf("X-Requested-With" to "XMLHttpRequest", "X-CSRF-TOKEN" to token), referer = url, cookies = req.cookies).text
        val source = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: return
        callback(newExtractorLink(name, name, source, StreamixExtractorLinkType.VIDEO) {
            this.referer = "$mainUrl/"
            quality = Regex("(\\d{3,4})p").find(req.text)?.groupValues?.get(1)?.toIntOrNull() ?: Qualities.Unknown.value
        })
    }
}
