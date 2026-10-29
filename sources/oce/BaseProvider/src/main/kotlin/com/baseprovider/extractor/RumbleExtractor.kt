package com.baseprovider.extractor

import com.baseprovider.streamix.*


class Rumble : StreamixExtractorApi() {
    override var name = "Rumble"; override var mainUrl = "https://rumble.com"; override val requiresReferer = false
    override suspend fun getUrl(url: String, referer: String?,
        subtitleCallback: (StreamixSubtitleFile) -> Unit,
            callback: (StreamixExtractorLink) -> Unit) {
        val response = app.get(url, referer = referer ?: "$mainUrl/")
        val scriptData = response.document.selectFirst("script:containsData(mp4)")
            ?.data()
            ?.substringAfter("{\"mp4")
            ?.substringBefore("\"evt\":{") ?: return
        CompiledRegexPatterns.RUMBLE_URL_PATTERN.findAll(scriptData)
            .forEach { match ->
            val cleanedUrl = match.groupValues[1].replace("\\/", "/")
            if (cleanedUrl.contains("rumble.com") && cleanedUrl.endsWith(".m3u8")) {
                MasterLinkGenerator.createSmartLink(this.name, cleanedUrl,
                    null, headers = MasterLinkGenerator
                    .minimalVideoHeaders, bareHeaders = true,
                    callback = callback)
            }
        }
    }
}
