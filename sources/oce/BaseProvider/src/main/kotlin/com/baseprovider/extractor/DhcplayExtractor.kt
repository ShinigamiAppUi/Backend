package com.baseprovider.extractor

import com.baseprovider.streamix.*
import com.baseprovider.log.logDebug

class Dhcplay : StreamixExtractorApi() {
    override var name = "Dhcplay"
    override var mainUrl = "https://dhcplay.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?,
        subtitleCallback: (StreamixSubtitleFile) -> Unit,
            callback: (StreamixExtractorLink) -> Unit) {
        val text = app.get(url, referer = referer).text
        val packed = findPackedJsInPage(text)
        if (packed != null) {
            val unpacked = decodePackedJs(packed.first, packed.second,
                packed.third)
            var found = false
            CompiledRegexPatterns.extractAllVideoUrls(unpacked)
                .let { urls ->
                CompiledRegexPatterns.filterMasterM3u8(urls).forEach {
                    found = true
                    MasterLinkGenerator.createSmartLink(this.name, it, null,
                        headers = MasterLinkGenerator.minimalVideoHeaders,
                        bareHeaders = true, callback = callback)
                }
            }
            if (found) return
        }
        val urls = CompiledRegexPatterns.extractAllVideoUrls(text)
        CompiledRegexPatterns.filterMasterM3u8(urls)
            .forEach { MasterLinkGenerator.createSmartLink(this.name, it,
                null, headers = MasterLinkGenerator.minimalVideoHeaders,
                bareHeaders = true, callback = callback) }
        try {
            // WebView navigation and subresources are outside the scoped HTTP policy.
            if (app.hasScopedHttpRequest()) {
                throw IllegalStateException("browser-assisted resolution is unavailable for Catalog-scoped requests")
            }
            val webResolver = StreamixRuntime.webResolver
                ?: throw IllegalStateException("StreamixWebResolver is not installed")
            val webResult = webResolver.resolve(
                streamix.core.StreamixWebRequest(url = url, referer = referer)
            )
            val interceptedUrl = webResult.intercepted?.url.orEmpty()
            if (interceptedUrl.isNotBlank()) {
                MasterLinkGenerator.createSmartLink(this.name,
                    interceptedUrl, null,
                    headers = MasterLinkGenerator.minimalVideoHeaders,
                    bareHeaders = true, callback = callback)
            }
        } catch (e: Exception) { logDebug("Dhcplay", "WebViewResolver failed: ${e.message}") }
    }
}
