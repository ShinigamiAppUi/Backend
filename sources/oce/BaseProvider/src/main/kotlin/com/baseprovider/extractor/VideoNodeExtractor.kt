package com.baseprovider.extractor

import com.baseprovider.streamix.*


class VideoNodePage : StreamixExtractorApi() {
    override var name = "VideoNodePage"
    override var mainUrl = "https://videonode.de"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?,
        subtitleCallback: (StreamixSubtitleFile) -> Unit,
            callback: (StreamixExtractorLink) -> Unit) {
        val doc = app.get(url, referer = referer).document
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                loadExtractorWithFallbackCustom(
                    src, url, subtitleCallback,
                    callback = callback,
                    providerTag = "VideoNodePage",
                    callChain = "VideoNodePage"
                )
            }
        }
    }
}