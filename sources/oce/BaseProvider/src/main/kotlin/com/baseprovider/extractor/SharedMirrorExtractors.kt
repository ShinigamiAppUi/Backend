package com.baseprovider.extractor

import com.baseprovider.streamix.*
import java.net.URI

private fun absUrl(value: String, base: String): String = runCatching {
    if (value.startsWith("http://", true) || value.startsWith("https://", true)) value
    else URI(base).resolve(value).toString()
}.getOrDefault(value)

private fun emitDirect(
    name: String,
    url: String,
    referer: String?,
    callback: (StreamixExtractorLink) -> Unit,
    type: StreamixExtractorLinkType = if (url.contains(".m3u8", true)) StreamixExtractorLinkType.M3U8 else StreamixExtractorLinkType.VIDEO,
    quality: Int = Qualities.Unknown.value,
    headers: Map<String,String> = emptyMap()
) = callback(StreamixExtractorLink(name, name, url, type, quality, referer.orEmpty(), headers))

class YourUpload : StreamixExtractorApi() {
    override val name = "YourUpload"
    override val mainUrl = "https://www.yourupload.com"
    override suspend fun getUrl(url:String, referer:String?, subtitleCallback:(StreamixSubtitleFile)->Unit, callback:(StreamixExtractorLink)->Unit) {
        val html = app.get(url, referer=referer ?: mainUrl).text
        val candidates = Regex("""(?i)(?:file|src|source)\s*[:=]\s*["'](https?://[^"']+)["']""")
            .findAll(html).map { it.groupValues[1] } +
            Regex("""https?://[^"'<\s]+\.(?:mp4|m3u8)(?:\?[^"'<\s]*)?""", RegexOption.IGNORE_CASE)
                .findAll(html).map { it.value }
        candidates.map { it.replace("\\/", "/").replace("&amp;", "&") }.distinct()
            .forEach { emitDirect(name,it,url,callback) }
    }
}

class Pixeldrain : StreamixExtractorApi() {
    override val name="Pixeldrain"; override val mainUrl="https://pixeldrain.com"
    override suspend fun getUrl(url:String, referer:String?, subtitleCallback:(StreamixSubtitleFile)->Unit, callback:(StreamixExtractorLink)->Unit) {
        val id=Regex("""pixeldrain\.(?:com|lu)/(?:u|api/file)/([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1)
            ?: Regex("""/([A-Za-z0-9]{6,})/?$""").find(url)?.groupValues?.get(1) ?: return
        val nameHint=runCatching { org.json.JSONObject(app.get("$mainUrl/api/file/$id").text).optString("name") }.getOrDefault("")
        emitDirect(name,"$mainUrl/api/file/$id?download",mainUrl,callback,quality=com.baseprovider.streamix.Qualities.Unknown.value)
    }
}

class Gofile : StreamixExtractorApi() {
    override val name="Gofile"; override val mainUrl="https://gofile.io"
    override suspend fun getUrl(url:String, referer:String?, subtitleCallback:(StreamixSubtitleFile)->Unit, callback:(StreamixExtractorLink)->Unit) {
        val id=Regex("""gofile\.io/(?:d|w)/([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1) ?: return
        val servers=runCatching { org.json.JSONObject(app.get("https://api.gofile.io/servers").text).optJSONObject("data")?.optJSONArray("servers")?.optJSONObject(0)?.optString("name") }.getOrNull()
        val tokens=listOf("4fd6sg89d7s6","1234567890abcdef")
        for(token in tokens) {
            val body=runCatching { app.get("https://api.gofile.io/contents/$id?wt=$token&cache=true",headers=mapOf("Accept" to "application/json","Referer" to mainUrl)).text }.getOrNull() ?: continue
            runCatching {
                val children=org.json.JSONObject(body).optJSONObject("data")?.optJSONObject("children") ?: return@runCatching
                val keys=children.keys()
                while(keys.hasNext()) { val file=children.optJSONObject(keys.next()) ?: continue; val link=file.optString("link").ifBlank { file.optString("directLink") }; if(link.isNotBlank()) emitDirect(name,link,mainUrl,callback) }
            }
            return
        }
        servers?.takeIf { it.isNotBlank() }?.let { emitDirect(name,"https://$it.gofile.io/download/web/$id",mainUrl,callback) }
    }
}

class Buzzheavier : StreamixExtractorApi() {
    override val name="Buzzheavier"; override val mainUrl="https://buzzheavier.com"
    override suspend fun getUrl(url:String, referer:String?, subtitleCallback:(StreamixSubtitleFile)->Unit, callback:(StreamixExtractorLink)->Unit) {
        val fixed=if(url.startsWith("//")) "https:$url" else url
        val id=Regex("""buzzheavier\.com/([A-Za-z0-9]+)""").find(fixed)?.groupValues?.get(1)
        val html=runCatching { app.get(fixed,referer=referer).text }.getOrNull().orEmpty()
        val links=(Regex("""https?://[^"'<>\s]+\.(?:mp4|m3u8|mkv)[^"'<>\s]*""",RegexOption.IGNORE_CASE).findAll(html).map{it.value}+
            Regex("""(?:hx-get|data-url|href)=["']([^"']*(?:download|/dl/|/f/)[^"']*)["']""",RegexOption.IGNORE_CASE).findAll(html).map{absUrl(it.groupValues[1],mainUrl)})
        val all=links.toMutableList(); id?.let { all += "$mainUrl/download/$it"; all += "$mainUrl/api/download/$it" }
        all.distinct().forEach { emitDirect(name,it,fixed,callback,headers=mapOf("Referer" to fixed)) }
    }
}

class Vtbe : StreamixExtractorApi() {
    override val name="Vtbe"; override val mainUrl="https://vtbe.to"; override val requiresReferer=true
    override suspend fun getUrl(url:String, referer:String?, subtitleCallback:(StreamixSubtitleFile)->Unit, callback:(StreamixExtractorLink)->Unit) {
        val html=app.get(url,referer=mainUrl).text
        val packed=findPackedJsInPage(html) ?: return
        val unpacked=decodePackedJs(packed.first, packed.second, packed.third)
        Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+)""").find(unpacked)?.groupValues?.get(1)
            ?.let { emitDirect(name,absUrl(it,mainUrl),referer,callback,StreamixExtractorLinkType.M3U8) }
    }
}

class FileMoonSx : StreamixExtractorApi() {
    override val name="FileMoonSx"; override val mainUrl="https://filemoon.sx"
    override suspend fun getUrl(url:String, referer:String?, subtitleCallback:(StreamixSubtitleFile)->Unit, callback:(StreamixExtractorLink)->Unit) {
        val html=app.get(url,referer=referer ?: mainUrl).text
        Regex("""https?://[^"'\s]+\.(?:mp4|m3u8)[^"'\s]*""",RegexOption.IGNORE_CASE).findAll(html)
            .map{it.value}.distinct().forEach{emitDirect(name,it,url,callback)}
    }
}

class StreamSBWaaw : StreamixExtractorApi() {
    override val name="StreamSB"; override val mainUrl="https://waaw.to"
    override suspend fun getUrl(url:String, referer:String?, subtitleCallback:(StreamixSubtitleFile)->Unit, callback:(StreamixExtractorLink)->Unit) {
        val html=app.get(url,referer=referer ?: mainUrl).text
        Regex("""https?://[^"'\s]+\.(?:m3u8|mp4)[^"'\s]*""",RegexOption.IGNORE_CASE).findAll(html)
            .map{it.value}.distinct().forEach{emitDirect(name,it,url,callback)}
    }
}
