package com.baseprovider.extractor

import com.baseprovider.streamix.*

import org.json.JSONObject

open class AWSStream : CachedExtractorApi() {
    override var name = "AWSStream"
    override var mainUrl = "https://z.awstream.net"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?,
        subtitleCallback: (StreamixSubtitleFile) -> Unit,
            callback: (StreamixExtractorLink) -> Unit) {
        val hash = url.substringAfterLast("/")
        val apiUrl = "$mainUrl/player/index.php?data=$hash&do=getVideo"
        val responseText = cachedPostText(
            apiUrl,
            data = mapOf("hash" to hash, "r" to mainUrl),
            headers = mapOf("x-requested-with" to "XMLHttpRequest")
        )
        val json = JSONObject(responseText)
        val m3u8 = json.optString("videoSource")
        if (m3u8.isNotBlank()) MasterLinkGenerator.createSmartLink(this
            .name, m3u8, null,
            headers = MasterLinkGenerator.minimalVideoHeaders,
            bareHeaders = true, callback = callback)
    }
}
