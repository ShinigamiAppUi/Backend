package com.baseprovider.extractor

import com.baseprovider.streamix.*
import com.baseprovider.log.logDebug
import org.jsoup.Jsoup


open class Minochinos : StreamixExtractorApi() {
    override var name = "Minochinos";
    override var mainUrl = "https://minochinos.com";
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?,
        subtitleCallback: (StreamixSubtitleFile) -> Unit,
            callback: (StreamixExtractorLink) -> Unit) {
        val text = app.get(url, referer = referer).text
        val packed = findPackedJsInPage(text)
        val script = if (packed != null) decodePackedJs(packed.first,
            packed.second, packed.third) else text
        var found = false
        CompiledRegexPatterns.extractAllVideoUrls(script).let { urls ->
            CompiledRegexPatterns.filterMasterM3u8(urls).forEach {
                found = true
                MasterLinkGenerator.createSmartLink(this.name, it, null,
                    headers = MasterLinkGenerator.minimalVideoHeaders,
                    bareHeaders = true, callback = callback)
            }
        }
        if (!found) {
            val docScripts = try {
                Jsoup.parse(text).selectFirst("script:containsData(sources:)")?.data()
            } catch (e: Exception) {
                logDebug("Minochinos", "Script fetch failed: ${e.message}")
                null
            }
            if (docScripts != null) {
                CompiledRegexPatterns.extractAllVideoUrls(docScripts)
                    .let { urls ->
                    CompiledRegexPatterns.filterMasterM3u8(urls).forEach {
                        MasterLinkGenerator.createSmartLink(this.name, it,
                            null, headers = MasterLinkGenerator
                            .minimalVideoHeaders, bareHeaders = true,
                            callback = callback)
                    }
                }
            }
        }
    }
}
