package com.baseprovider.extractor

import com.baseprovider.streamix.*


open class LuluStream : StreamixExtractorApi() {
    override var name = "LuluStream"
    override var mainUrl = "https://luluvdo.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?,
        subtitleCallback: (StreamixSubtitleFile) -> Unit,
            callback: (StreamixExtractorLink) -> Unit) {
        val filecode = url.substringAfterLast("/")
        val doc = app.post(
            "$mainUrl/dl",
            data = mapOf(
                "op" to "embed",
                "file_code" to filecode,
                "auto" to "1",
                "referer" to (referer ?: "")
            )
        ).document
        val script = doc.selectFirst("script:containsData(vplayer)")
            ?.data() ?: return
        val urls = CompiledRegexPatterns.extractAllVideoUrls(script)
        CompiledRegexPatterns.prioritizeAdaptiveUrls(urls).forEach {
            MasterLinkGenerator.createSmartLink(this.name, it, null,
                headers = MasterLinkGenerator.minimalVideoHeaders,
                bareHeaders = true, callback = callback)
        }
    }
}
