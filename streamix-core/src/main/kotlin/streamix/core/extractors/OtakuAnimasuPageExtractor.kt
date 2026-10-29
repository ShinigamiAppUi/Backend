package streamix.core.extractors

import org.jsoup.Jsoup
import streamix.core.ProviderStream
import streamix.core.StreamixExtractor
import streamix.core.StreamixExtractorRegistry
import streamix.core.StreamixExtractorRequest
import streamix.core.StreamixExtractorResult
import java.net.URI
import java.net.URLEncoder
import java.util.Base64

class OtakuAnimasuPageExtractor(
    private val http: streamix.core.StreamixHttp,
    private val registry: StreamixExtractorRegistry
) : StreamixExtractor {
    override val id = "otaku-animasu-page"
    override val domains = setOf("otakudesu.blog", "animasu.love")

    override suspend fun extract(request: StreamixExtractorRequest): StreamixExtractorResult {
        val page = runCatching {
            http.get(
                request.url,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                ),
                referer = request.referer,
                timeoutMs = 15_000L
            )
        }.getOrNull() ?: return StreamixExtractorResult()
        if (page.code !in 200..399) return StreamixExtractorResult()

        val playerUrls = linkedSetOf<String>()
        val doc = page.document
        if (isOtakudesu(page.url)) resolveOtakudesuMirrors(page.url, doc).forEach(playerUrls::add)
        if (isAnimasu(page.url)) resolveAnimasuMirrors(page.url, doc).forEach(playerUrls::add)

        doc.select("iframe[src], iframe[data-src], source[src], video[src]").forEach { el ->
            absolute(el.attr("src").ifBlank { el.attr("data-src") }, page.url)?.let(playerUrls::add)
        }

        val streams = mutableListOf<ProviderStream>()
        for (playerUrl in playerUrls) {
            if (isPlayable(playerUrl)) {
                streams += ProviderStream(id, playerUrl, quality(playerUrl), mediaType(playerUrl), referer = page.url)
                continue
            }
            for (extractor in registry.all().filter { it.id != id && matches(it, playerUrl) }) {
                val result = runCatching {
                    extractor.extract(StreamixExtractorRequest(playerUrl, providerId = id, referer = page.url))
                }.getOrNull() ?: continue
                streams += result.streams
                if (result.streams.isNotEmpty()) break
            }
        }
        return StreamixExtractorResult(streams.distinctBy { it.url })
    }

    private suspend fun resolveOtakudesuMirrors(pageUrl: String, doc: org.jsoup.nodes.Document): List<String> {
        val base = runCatching { URI(pageUrl).let { it.scheme + "://" + it.authority } }.getOrNull() ?: return emptyList()
        val nonceResponse = runCatching {
            http.post(
                "$base/wp-admin/admin-ajax.php",
                body = "action=aa1208d27f29ca340c92c66d1926f13f",
                headers = ajaxHeaders(),
                referer = pageUrl
            )
        }.getOrNull() ?: return emptyList()
        val nonce = Regex("\"data\"\\s*:\\s*\"([^\"]+)\"").find(nonceResponse.text)?.groupValues?.get(1) ?: return emptyList()

        val result = mutableListOf<String>()
        for (ul in doc.select(".mirrorstream ul")) {
            for (a in ul.select("a[data-content]")) {
                val token = a.attr("data-content").trim()
                if (token.isBlank()) continue
                val payload = decodeJsonPayload(token) ?: continue
                val form = listOf(
                    "action" to "2a3505c93b0035d3f455df82bf976b84",
                    "nonce" to nonce,
                    "id" to payload.id.toString(),
                    "i" to payload.i.toString(),
                    "q" to payload.q
                ).joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") }

                val response = runCatching {
                    http.post(
                        "$base/wp-admin/admin-ajax.php",
                        body = form,
                        headers = ajaxHeaders(),
                        referer = pageUrl
                    )
                }.getOrNull() ?: continue

                val encoded = Regex("\"data\"\\s*:\\s*\"([^\"]+)\"").find(response.text)?.groupValues?.get(1) ?: continue
                val decoded = decodeBase64(encoded) ?: continue
                val mirror = Jsoup.parse(decoded, pageUrl)
                mirror.select("iframe[src], iframe[data-src]").firstOrNull()?.let {
                    absolute(it.attr("src").ifBlank { it.attr("data-src") }, pageUrl)?.let(result::add)
                }
            }
        }
        return result.distinct()
    }

    private fun resolveAnimasuMirrors(pageUrl: String, doc: org.jsoup.nodes.Document): List<String> {
        val result = mutableListOf<String>()
        for (option in doc.select("select.mirror option[value]")) {
            val decoded = decodeBase64(option.attr("value").trim()) ?: continue
            val mirror = Jsoup.parse(decoded, pageUrl)
            mirror.select("iframe[src], iframe[data-src]").firstOrNull()?.let {
                absolute(it.attr("src").ifBlank { it.attr("data-src") }, pageUrl)?.let(result::add)
            }
        }
        return result.distinct()
    }

    private fun decodeJsonPayload(token: String): Payload? {
        val json = decodeBase64(token) ?: return null
        val id = Regex("\"id\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val i = Regex("\"i\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val q = Regex("\"q\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: return null
        return Payload(id, i, q)
    }

    private fun decodeBase64(value: String): String? = runCatching {
        Base64.getDecoder().decode(value).toString(Charsets.UTF_8)
    }.getOrNull()

    private fun absolute(value: String, base: String): String? =
        if (value.isBlank()) null else runCatching { URI(base).resolve(value).toString() }.getOrNull()

    private fun matches(extractor: StreamixExtractor, url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase()?.removePrefix("www.") }.getOrNull() ?: return false
        return extractor.domains.any { d ->
            val domain = d.lowercase().removePrefix("www.")
            host == domain || host.endsWith(".$domain")
        }
    }

    private fun isPlayable(url: String) = Regex("\\.(m3u8|mp4|m4v|webm|mov|mpd)(?:[?#]|$)", RegexOption.IGNORE_CASE).containsMatchIn(url)
    private fun mediaType(url: String) = when {
        url.contains(".m3u8", true) -> "m3u8"
        url.contains(".mpd", true) -> "dash"
        else -> "video"
    }
    private fun quality(url: String): Int? = Regex("(?:^|[^0-9])(2160|1440|1080|720|480|360|240|144)p?(?:[^0-9]|$)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.toIntOrNull()
    private fun isOtakudesu(url: String) = host(url) == "otakudesu.blog"
    private fun isAnimasu(url: String) = host(url) == "animasu.love"
    private fun host(url: String) = runCatching { URI(url).host?.lowercase()?.removePrefix("www.") }.getOrNull()
    private fun ajaxHeaders() = mapOf(
        "User-Agent" to "Mozilla/5.0",
        "X-Requested-With" to "XMLHttpRequest",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
    )
    private data class Payload(val id: Int, val i: Int, val q: String)
}