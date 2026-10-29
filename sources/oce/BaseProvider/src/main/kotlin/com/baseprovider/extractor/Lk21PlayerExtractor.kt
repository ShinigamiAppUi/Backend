package com.baseprovider.extractor

import com.baseprovider.streamix.*


class Lk21PlayerPage : StreamixExtractorApi() {
    override var name = "Lk21Player"
    override var mainUrl = "https://playeriframe.sbs"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?,
        subtitleCallback: (StreamixSubtitleFile) -> Unit,
            callback: (StreamixExtractorLink) -> Unit) {
        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        val doc = app.get(url, referer = referer, headers = mapOf("User-Agent" to ua)).document
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                loadExtractorWithFallbackCustom(
                    src, url, subtitleCallback,
                    callback = callback,
                    providerTag = "Lk21Player",
                    callChain = "Lk21Player"
                )
            }
        }
    }
}
