package com.baseprovider.extractor

import com.baseprovider.streamix.*

import org.json.JSONObject

open class MegaPlay : CachedExtractorApi() {
    override var name = "MegaPlay"
    override var mainUrl = "https://megaplay.buzz"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?,
        subtitleCallback: (StreamixSubtitleFile) -> Unit,
            callback: (StreamixExtractorLink) -> Unit) {
        val doc = app.get(url).document
        val id = doc.selectFirst("#megaplay-player")
            ?.attr("data-id") ?: return
        val apiUrl = "$mainUrl/stream/getSources?id=$id"
        val responseText = cachedGetText(apiUrl)
        val json = JSONObject(responseText)
        val m3u8 = json.optJSONObject("sources")
            ?.optString("file") ?: return
        MasterLinkGenerator.createSmartLink(this.name, m3u8, null,
            headers = MasterLinkGenerator.minimalVideoHeaders,
            bareHeaders = true, callback = callback)
    }
}
