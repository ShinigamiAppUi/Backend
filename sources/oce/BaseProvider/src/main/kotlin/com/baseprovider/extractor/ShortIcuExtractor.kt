package com.baseprovider.extractor

import com.baseprovider.streamix.*


class ShortIcu : StreamixExtractorApi() {
    override var name = "ShortIcu"
    override var mainUrl = "https://short.icu"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?,
        subtitleCallback: (StreamixSubtitleFile) -> Unit,
            callback: (StreamixExtractorLink) -> Unit) {
        val response = app.get(url, referer = referer)
        val finalUrl = response.url
        if (finalUrl != url) {
            loadExtractor(finalUrl, url, subtitleCallback, callback)
        }

        val urls = CompiledRegexPatterns.extractAllVideoUrls(response.text)
        CompiledRegexPatterns.filterMasterM3u8(urls).forEach { videoUrl ->
            MasterLinkGenerator.createSmartLink(this.name, videoUrl,
                null, headers = MasterLinkGenerator.minimalVideoHeaders,
                bareHeaders = true, callback = callback)
        }
    }
}
