package com.baseprovider.extractor

import com.baseprovider.streamix.*
import com.baseprovider.log.logDebug

import org.json.JSONObject

open class Hownetwork : CachedExtractorApi() {
    override var name = "Hownetwork"
    override var mainUrl = "https://stream.hownetwork.xyz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (StreamixSubtitleFile) -> Unit,
        callback: (StreamixExtractorLink) -> Unit
    ) {
        try {
            val id = url.substringAfter("id=")
            val responseText = cachedPostText(
                "$mainUrl/api2.php?id=$id",
                data = mapOf("r" to "", "d" to mainUrl),
                referer = url,
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest"
                )
            )
            JSONObject(responseText).optString("file").let {
                MasterLinkGenerator.createSmartLink(
                    this.name, it, null,
                    headers = MasterLinkGenerator.minimalVideoHeaders,
                    bareHeaders = true,
                    callback = callback
                )
            }
        } catch (e: Exception) {
            logDebug("Hownetwork", "Extraction failed: ${e.message}")
        }
    }
}
