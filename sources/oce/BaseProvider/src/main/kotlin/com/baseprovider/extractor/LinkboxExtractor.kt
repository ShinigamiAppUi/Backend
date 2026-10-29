package com.baseprovider.extractor

import com.baseprovider.streamix.*

class LinkboxExtractor : StreamixExtractorApi() {
    override val name = "Linkbox"
    override val mainUrl = "https://lbx.to"
    override val domains: Set<String> = setOf("lbx.to", "linkbox.to")
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (StreamixSubtitleFile) -> Unit, callback: (StreamixExtractorLink) -> Unit) {
        val token = Regex("(?:/f/|/file/|\\?id=)(\\w+)").find(url)?.groupValues?.get(1) ?: return
        val share = app.get("https://www.linkbox.to/api/file/share_out_list/?sortField=utime&sortAsc=0&pageNo=1&pageSize=50&shareToken=$token").text
        val itemId = Regex("\"itemId\"\\s*:\\s*\"?([^\",}]+)").find(share)?.groupValues?.get(1) ?: return
        val detail = app.get("https://www.linkbox.to/api/file/detail?itemId=$itemId", referer = url).text
        Regex("\"url\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"resolution\"\\s*:\\s*\"([^\"]*)\"").findAll(detail).forEach { m ->
            callback(newExtractorLink(name, name, m.groupValues[1], StreamixExtractorLinkType.VIDEO) {
                this.referer = "https://www.linkbox.to/"
                quality = Regex("(\\d{3,4})").find(m.groupValues[2])?.groupValues?.get(1)?.toIntOrNull() ?: Qualities.Unknown.value
            })
        }
    }
}
