package com.baseprovider.extractor

import com.baseprovider.streamix.*
import com.baseprovider.log.logDebug

class StreamHG : StreamixExtractorApi() {
    override var name = "StreamHG"
    override var mainUrl = "https://hgcloud.to"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?,
        subtitleCallback: (StreamixSubtitleFile) -> Unit,
            callback: (StreamixExtractorLink) -> Unit) {
        val response = app.get(url, referer = referer)
        val text = response.text
        val packed = findPackedJsInPage(text)
        if (packed != null) {
            val unpacked = decodePackedJs(packed.first, packed.second,
                packed.third)
            CompiledRegexPatterns.extractAllVideoUrls(unpacked)
                .let { urls ->
                CompiledRegexPatterns.filterMasterM3u8(urls).forEach {
                    MasterLinkGenerator.createSmartLink(this.name, it, null,
                        headers = MasterLinkGenerator.minimalVideoHeaders,
                        bareHeaders = true, callback = callback)
                }
            }
        } else {
            try {
                // WebView navigation and subresources are outside the scoped HTTP policy.
                if (app.hasScopedHttpRequest()) {
                    throw IllegalStateException("browser-assisted resolution is unavailable for Catalog-scoped requests")
                }
                val interceptedUrl = StreamixRuntime.webResolver?.resolve(
                    streamix.core.StreamixWebRequest(url = url, referer = referer)
                )?.intercepted?.url.orEmpty()
                if (interceptedUrl.isNotBlank()) {
                    MasterLinkGenerator.createSmartLink(this.name,
                        interceptedUrl, null,
                        headers = MasterLinkGenerator.minimalVideoHeaders,
                        bareHeaders = true, callback = callback)
                }
            } catch (e: Exception) { logDebug("StreamHG", "WebViewResolver failed: ${e.message}") }
        }
    }
}
