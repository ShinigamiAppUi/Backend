package streamix.catalog

import java.net.URI
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import streamix.api.StreamRef
import streamix.core.StreamixHttp

abstract class HostPageResolver(
    protected val http: StreamixHttp,
    final override val id: String,
    final override val domains: Set<String>
) : CatalogResolver {
    override suspend fun resolve(source: EpisodeSource): CatalogResolverResult {
        return try {
            val page = http.get(source.sourceUrl, source.headers, source.referer)
            if (page.code !in 200..299) return CatalogResolverResult(httpStatus = page.code, finalUrl = page.url ?: source.sourceUrl, failureReason = "HTTP " + page.code)
            parse(source, page.text, page.url ?: source.sourceUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            CatalogResolverResult(finalUrl = source.sourceUrl, failureReason = e.message?.take(512))
        }
    }

    protected abstract suspend fun parse(source: EpisodeSource, body: String, finalUrl: String): CatalogResolverResult

    protected fun stream(source: EpisodeSource, raw: String, finalUrl: String, type: String? = null, headers: Map<String, String> = emptyMap(), quality: Int? = null): CatalogResolverResult {
        val url = runCatching { URI(finalUrl).resolve(raw).toString() }.getOrNull() ?: return CatalogResolverResult(finalUrl = finalUrl, failureReason = id + " invalid URL")
        val uri = runCatching { URI(url) }.getOrNull()
            ?: return CatalogResolverResult(finalUrl = finalUrl, failureReason = id + " invalid media URI")
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return CatalogResolverResult(finalUrl = finalUrl, failureReason = id + " non-http media URL")
        val mediaType = type ?: if (uri.path.orEmpty().endsWith(".m3u8", true)) "hls" else "video"
        return CatalogResolverResult(
            streams = listOf(StreamRef(id, url, quality ?: source.quality, mediaType, source.language, source.subtitleLanguage, source.headers + headers, finalUrl)),
            finalUrl = finalUrl
        )
    }

    protected fun unpack(body: String): String {
        val marker = "eval(function(p,a,c,k,e,d)"
        val i = body.indexOf(marker)
        if (i < 0) return body
        return PackedJsUnpacker.unpackAndCombine(body.substring(i)) ?: body
    }

    protected fun firstHls(body: String): String? =
        Regex("""https?://[^"\'\s<>]+\.m3u8[^"\'\s<>]*""", RegexOption.IGNORE_CASE).find(body)?.value
            ?: Regex("""["\']((?:https?:)?//[^"\'\s<>]+\.m3u8[^"\'\s<>]*)["\']""", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)
}

class StreamWishCatalogResolver(http: StreamixHttp = CatalogSafeStreamixHttp()) : HostPageResolver(http, "streamwish-catalog", setOf("streamwish.com", "niramirus.com", "medixiru.com")) {
    override suspend fun parse(source: EpisodeSource, body: String, finalUrl: String) =
        firstHls(unpack(body))?.let { stream(source, it, finalUrl, "hls") }
            ?: CatalogResolverResult(finalUrl = finalUrl, failureReason = "streamwish HLS source not found")
}

class FileLionsCatalogResolver(http: StreamixHttp = CatalogSafeStreamixHttp()) : HostPageResolver(http, "filelions-catalog", setOf("filelions.to", "filelions.live")) {
    override suspend fun parse(source: EpisodeSource, body: String, finalUrl: String) =
        firstHls(unpack(body))?.let { stream(source, it, finalUrl, "hls") }
            ?: CatalogResolverResult(finalUrl = finalUrl, failureReason = "filelions HLS source not found")
}

class VidHideCatalogResolver(http: StreamixHttp = CatalogSafeStreamixHttp()) : HostPageResolver(http, "vidhide-catalog", setOf("vidhide.com", "vidhidepro.com")) {
    override suspend fun parse(source: EpisodeSource, body: String, finalUrl: String) =
        firstHls(unpack(body))?.let { stream(source, it, finalUrl, "hls") }
            ?: CatalogResolverResult(finalUrl = finalUrl, failureReason = "vidhide HLS source not found")
}

class VidLandCatalogResolver(http: StreamixHttp = CatalogSafeStreamixHttp()) : HostPageResolver(http, "vidland-catalog", setOf("vidland.net")) {
    override suspend fun parse(source: EpisodeSource, body: String, finalUrl: String): CatalogResolverResult {
        val decoded = unpack(body)
        val media = Regex("""hls[34]\s*:\s*["\']([^"\']+)["\']""").find(decoded)?.groupValues?.get(1)
        return media?.let { stream(source, it, finalUrl, "hls") }
            ?: CatalogResolverResult(finalUrl = finalUrl, failureReason = "vidland HLS source not found")
    }
}

class VidMolyCatalogResolver(http: StreamixHttp = CatalogSafeStreamixHttp()) : HostPageResolver(http, "vidmoly-catalog", setOf("vidmoly.biz", "vidmoly.to")) {
    override suspend fun parse(source: EpisodeSource, body: String, finalUrl: String): CatalogResolverResult {
        val media = Regex("""file\s*:\s*["']([^"']+)["']""").find(body)?.groupValues?.get(1)
        return media?.let { stream(source, it, finalUrl, "hls", mapOf("Referer" to "https://vidmoly.biz/", "Origin" to "https://vidmoly.biz")) }
            ?: CatalogResolverResult(finalUrl = finalUrl, failureReason = "vidmoly source not found")
    }
}

class VidoCatalogResolver(http: StreamixHttp = CatalogSafeStreamixHttp()) : HostPageResolver(http, "vido-catalog", setOf("vido.lol")) {
    override suspend fun parse(source: EpisodeSource, body: String, finalUrl: String): CatalogResolverResult {
        val id = Regex("""master\|([^|]+)\|""").find(body)?.groupValues?.get(1)
        return id?.let { stream(source, "https://pink.vido.lol/hls/" + it + "/master.m3u8", finalUrl, "hls") }
            ?: CatalogResolverResult(finalUrl = finalUrl, failureReason = "vido media id not found")
    }
}

class VudeoCatalogResolver(http: StreamixHttp = CatalogSafeStreamixHttp()) : HostPageResolver(http, "vudeo-catalog", setOf("vudeo.net", "vudeo.co")) {
    override suspend fun parse(source: EpisodeSource, body: String, finalUrl: String): CatalogResolverResult {
        val media = Regex("""sources:\s*\[([^]]+)]""").find(body)?.groupValues?.get(1)
            ?.split(',')?.map { it.trim().trim('"', '\'') }?.firstOrNull { it.startsWith("http") }
        return media?.let { stream(source, it, finalUrl) }
            ?: CatalogResolverResult(finalUrl = finalUrl, failureReason = "vudeo source not found")
    }
}

private fun md5(input: String): String = java.security.MessageDigest.getInstance("MD5")
    .digest(input.toByteArray())
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

class VkCatalogResolver(private val http: StreamixHttp = CatalogSafeStreamixHttp()) : CatalogResolver {
    override val id = "vk-catalog"
    override val domains = setOf("vk.com", "vkvideo.ru")
    override suspend fun resolve(source: EpisodeSource): CatalogResolverResult {
        return try {
            val videoId = Regex("""(-?\d+_\d+)""").find(source.sourceUrl)?.groupValues?.get(1)
                ?: return CatalogResolverResult(finalUrl = source.sourceUrl, failureReason = "vk video id not found")
            val response = http.post(
                "https://vk.com/al_video.php",
                "act=show&al=1&video=" + videoId,
                source.headers + mapOf("X-Requested-With" to "XMLHttpRequest"),
                source.referer ?: "https://vk.com/"
            )
            if (response.code !in 200..299) return CatalogResolverResult(httpStatus = response.code, finalUrl = response.url, failureReason = "HTTP " + response.code)
            val streams = Regex("""["'](?:url|mp4)_(\d+)["']\s*:\s*["']([^"']+)["']""").findAll(response.text).mapNotNull {
                val url = it.groupValues[2].replace("\\/", "/")
                if (!url.startsWith("http")) null else StreamRef(id, url, it.groupValues[1].toIntOrNull(), "video", source.language, source.subtitleLanguage, source.headers + mapOf("Referer" to "https://vk.com/"), response.url)
            }.toList().distinctBy { it.quality }.sortedByDescending { it.quality ?: 0 }
            if (streams.isNotEmpty()) return CatalogResolverResult(streams = streams, finalUrl = response.url)

            val embedUrl = if (source.sourceUrl.contains("video_ext.php", true)) {
                source.sourceUrl
            } else {
                "https://vk.com/video_ext.php?oid=" + videoId.substringBefore("_") + "&id=" + videoId.substringAfter("_") + "&autoplay=0"
            }
            val page = http.get(embedUrl, source.headers + mapOf("Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"), source.referer ?: "https://vk.com/")
            val html = if (page.code == 429 || page.url.contains("429.html")) {
                val hash429 = page.cookies["hash429"]
                if (hash429.isNullOrBlank()) return CatalogResolverResult(finalUrl = page.url, failureReason = "vk WAF hash429 cookie missing")
                val key = md5(hash429)
                http.get(
                    page.url + if (page.url.contains("?")) "&key=$key" else "?key=$key",
                    source.headers + mapOf("Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"),
                    source.referer ?: "https://vk.com/"
                )
                http.get(embedUrl, source.headers, source.referer ?: "https://vk.com/")
            } else page
            val fallbackStreams = Regex("""["'](?:url|mp4)_(\d+)["']\s*:\s*["']([^"']+)["']""").findAll(html.text).mapNotNull {
                val url = it.groupValues[2].replace("\\/", "/")
                if (!url.startsWith("http")) null else StreamRef(id, url, it.groupValues[1].toIntOrNull(), "video", source.language, source.subtitleLanguage, source.headers + mapOf("Referer" to "https://vk.com/"), html.url)
            }.toList().distinctBy { it.quality }.sortedByDescending { it.quality ?: 0 }
            if (fallbackStreams.isEmpty()) CatalogResolverResult(finalUrl = html.url, failureReason = "vk stream list empty")
            else CatalogResolverResult(streams = fallbackStreams, finalUrl = html.url)
        } catch (e: CancellationException) { throw e }
        catch (e: Throwable) { CatalogResolverResult(finalUrl = source.sourceUrl, failureReason = e.message?.take(512)) }
    }
}

class StreamDavCatalogResolver(http: StreamixHttp = CatalogSafeStreamixHttp()) : HostPageResolver(http, "streamdav-catalog", setOf("streamdav.com")) {
    override suspend fun parse(source: EpisodeSource, body: String, finalUrl: String): CatalogResolverResult {
        val match = Regex("""<source[^>]+src=["']([^"']+)["'][^>]*label=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(body)
            ?: Regex("""<source[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(body)
        val media = match?.groupValues?.get(1)
        val quality = match?.groupValues?.getOrNull(2)?.filter(Char::isDigit)?.toIntOrNull()
        return media?.let { stream(source, it, finalUrl, quality = quality) }
            ?: CatalogResolverResult(finalUrl = finalUrl, failureReason = "streamdav source not found")
    }
}

class StreamSilkCatalogResolver(http: StreamixHttp = CatalogSafeStreamixHttp()) : HostPageResolver(http, "streamsilk-catalog", setOf("streamsilk.com")) {
    override suspend fun parse(source: EpisodeSource, body: String, finalUrl: String): CatalogResolverResult {
        val direct = Regex("""(?:var\s+)?urlPlay\s*=\s*["\']([^"\']*m3u8[^"\']*)["\']""").find(body)?.groupValues?.get(1)
        if (direct != null) return stream(source, direct, finalUrl, "hls", mapOf("Referer" to "https://streamsilk.com/", "Origin" to "https://streamsilk.com"))
        val script = Regex("""<script[^>]*>(.*?)</script>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(body).map { it.groupValues[1] }
            .firstOrNull { it.contains("eval(function(h,u,n,t,e,r)") } ?: return CatalogResolverResult(finalUrl = finalUrl, failureReason = "streamsilk hunter script not found")
        val dehunted = dehunt(script) ?: return CatalogResolverResult(finalUrl = finalUrl, failureReason = "streamsilk hunter decode failed")
        val media = Regex("""var\s+urlPlay\s*=\s*["']([^"']*m3u8[^"']*)["']""").find(dehunted)?.groupValues?.get(1)
            ?: firstHls(dehunted)
        return media?.let { stream(source, it, finalUrl, "hls", mapOf("Referer" to "https://streamsilk.com/", "Origin" to "https://streamsilk.com")) }
            ?: CatalogResolverResult(finalUrl = finalUrl, failureReason = "streamsilk HLS source not found")
    }

    private fun dehunt(script: String): String? {
        val m = Regex("""\}\("([^"]+)",[^,]+,\s*"([^"]+)",\s*(\d+),\s*(\d+)""", RegexOption.DOT_MATCHES_ALL).find(script) ?: return null
        val h = m.groupValues[1]
        val n = m.groupValues[2]
        val t = m.groupValues[3].toInt()
        val e = m.groupValues[4].toInt()
        fun duf(d: String): Int {
            val alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ+/"
            val hi = alphabet.take(e)
            val lo = alphabet.take(10)
            var j = 0.0
            for ((idx, ch) in d.reversed().withIndex()) if (ch in hi) j += hi.indexOf(ch) * Math.pow(e.toDouble(), idx.toDouble())
            var k = ""
            while (j > 0) {
                k = lo[(j % 10).toInt()] + k
                j = (j - j % 10) / 10
            }
            return k.toIntOrNull() ?: 0
        }
        val out = StringBuilder()
        var i = 0
        while (i < h.length) {
            var part = ""
            while (i < h.length && h[i] != n[e]) { part += h[i]; i++ }
            for (j in n.indices) part = part.replace(n[j], j.digitToChar())
            out.append((duf(part) - t).toChar())
            i++
        }
        return out.toString()
    }
}
class StreamUpCatalogResolver(http: StreamixHttp = CatalogSafeStreamixHttp()) : HostPageResolver(http, "streamup-catalog", setOf("streamup.ws", "streamup.cc")) {
    override suspend fun parse(source: EpisodeSource, body: String, finalUrl: String): CatalogResolverResult {
        val encoded = Regex("""decodePrintable95\(["']([a-f0-9]+)["']""").find(body)?.groupValues?.get(1)
        val shift = Regex("""__enc_shift\s*=\s*(\d+)""").find(body)?.groupValues?.get(1)?.toIntOrNull()
        val base = java.net.URI(finalUrl)
        val origin = base.scheme + "://" + base.host
        if (encoded != null && shift != null) {
            val decoded = decodePrintable95(encoded, shift)
            if (decoded.startsWith("http")) return stream(source, decoded, finalUrl, headers = mapOf("Referer" to finalUrl, "Origin" to origin))
        }
        val mediaId = finalUrl.substringAfterLast('/').substringBefore('?')
        val sessionId = Regex("""['"]([a-f0-9]{32})['"]""").find(body)?.groupValues?.get(1)
        val encrypted = Regex("""['"]([A-Za-z0-9+/=]{200,})['"]""").find(body)?.groupValues?.get(1)
        if (sessionId != null && encrypted != null) {
            val keyResponse = http.get(origin + "/ajax/stream?session=" + sessionId, mapOf("Referer" to finalUrl, "X-Requested-With" to "XMLHttpRequest"), finalUrl)
            val decrypted = decryptAes(encrypted, keyResponse.text.trim())
            if (decrypted?.startsWith("http") == true) return stream(source, decrypted, finalUrl, headers = mapOf("Referer" to origin + "/", "Origin" to origin))
        }
        val fallback = http.get(origin + "/ajax/stream?filecode=" + mediaId, mapOf("Referer" to finalUrl), finalUrl)
        val media = Regex("""["']streaming_url["']\s*:\s*["']([^"']+)["']""").find(fallback.text)?.groupValues?.get(1)?.replace("\\/", "/")
        return media?.let { stream(source, it, fallback.url ?: finalUrl, headers = mapOf("Referer" to finalUrl)) }
            ?: firstHls(body)?.let { stream(source, it, finalUrl, "hls") }
            ?: CatalogResolverResult(finalUrl = finalUrl, failureReason = "streamup media source not found")
    }

    private fun decodePrintable95(encoded: String, shift: Int): String {
        val bytes = ByteArray(encoded.length / 2) { encoded.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val intermediate = String(bytes, Charsets.ISO_8859_1)
        return buildString {
            intermediate.forEachIndexed { index, char ->
                var n = (char.code - 32 - shift - index) % 95
                if (n < 0) n += 95
                append((n + 32).toChar())
            }
        }
    }

    private fun decryptAes(encryptedB64: String, keyB64: String): String? = runCatching {
        val key = Base64.getDecoder().decode(keyB64)
        val encrypted = Base64.getDecoder().decode(encryptedB64)
        val iv = encrypted.copyOfRange(0, 16)
        val ciphertext = encrypted.copyOfRange(16, encrypted.size)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }.getOrNull().let { text ->
        text?.let { Regex("""["']streaming_url["']\s*:\s*["']([^"']+)["']""").find(it)?.groupValues?.get(1)?.replace("\\/", "/") }
    }
}
class StreamLareCatalogResolver(private val http: StreamixHttp = CatalogSafeStreamixHttp()) : CatalogResolver {
    override val id = "streamlare-catalog"
    override val domains = setOf("streamlare.com", "slwatch.co")
    override suspend fun resolve(source: EpisodeSource): CatalogResolverResult {
        return try {
            val idValue = source.sourceUrl.substringAfterLast('/').substringBefore('?')
            val response = http.post("https://slwatch.co/api/video/stream/get", """{"id":"$idValue"}""", source.headers, source.referer ?: "https://slwatch.co/")
            if (response.code !in 200..299) return CatalogResolverResult(httpStatus = response.code, finalUrl = response.url, failureReason = "HTTP " + response.code)
            val media = Regex("""["']file["']\s*:\s*["']([^"']+)["']""").find(response.text)?.groupValues?.get(1)?.replace("\\/", "/")
            if (media != null) {
                val url = URI(response.url).resolve(media).toString()
                return CatalogResolverResult(streams = listOf(StreamRef(id, url, source.quality, if (url.contains(".m3u8")) "hls" else "video", source.language, source.subtitleLanguage, source.headers, response.url)), finalUrl = response.url)
            }
            CatalogResolverResult(finalUrl = response.url, failureReason = "streamlare media source not found")
        } catch (e: CancellationException) { throw e }
        catch (e: Throwable) { CatalogResolverResult(finalUrl = source.sourceUrl, failureReason = e.message?.take(512)) }
    }
}

class StreamPlayCatalogResolver(http: StreamixHttp = CatalogSafeStreamixHttp()) : HostPageResolver(http, "streamplay-catalog", setOf("streamplay.co.in")) {
    override suspend fun parse(source: EpisodeSource, body: String, finalUrl: String): CatalogResolverResult {
        val token = Regex("""window\.kaken\s*=\s*["']([^"']+)["']""").find(unpack(body))?.groupValues?.get(1)
            ?: return CatalogResolverResult(finalUrl = finalUrl, failureReason = "streamplay kaken token not found")
        val response = http.post(
            "https://play.streamplay.co.in/api/",
            token,
            source.headers + mapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "Origin" to (URI(finalUrl).let { it.scheme + "://" + it.host }),
                "Referer" to finalUrl,
                "X-Requested-With" to "XMLHttpRequest"
            ),
            finalUrl
        )
        if (response.code !in 200..299) return CatalogResolverResult(httpStatus = response.code, finalUrl = response.url, failureReason = "HTTP " + response.code)
        val streams = Regex("""["']file["']\s*:\s*["']([^"']+)["']\s*,\s*["']label["']\s*:\s*["']([^"']*)["']\s*,\s*["']type["']\s*:\s*["']([^"']+)["']""").findAll(response.text).mapNotNull { m ->
            val raw = m.groupValues[1].replace("\\/", "/")
            val fixed = if (raw.contains("master.txt")) raw.replace("master.txt", "master.m3u8") else raw
            val url = runCatching { URI(response.url).resolve(fixed).toString() }.getOrNull() ?: return@mapNotNull null
            if (!url.startsWith("http")) return@mapNotNull null
            val type = if (m.groupValues[3].equals("hls", true) || url.contains(".m3u8", true)) "hls" else "video"
            val quality = m.groupValues[2].filter(Char::isDigit).toIntOrNull()
            StreamRef(id, url, quality ?: source.quality, type, source.language, source.subtitleLanguage, source.headers + mapOf("Referer" to finalUrl), finalUrl)
        }.toList().distinctBy { it.url }
        return if (streams.isEmpty()) CatalogResolverResult(finalUrl = response.url, failureReason = "streamplay terminal source not found")
        else CatalogResolverResult(streams = streams, finalUrl = response.url)
    }
}

class KwikCatalogResolver(http: StreamixHttp = CatalogSafeStreamixHttp()) : HostPageResolver(http, "kwik-catalog", setOf("kwik.cx")) {
    override suspend fun parse(source: EpisodeSource, body: String, finalUrl: String): CatalogResolverResult =
        firstHls(unpack(body))?.let { stream(source, it, finalUrl, "hls", mapOf("Referer" to "https://kwik.cx/", "Origin" to "https://kwik.cx")) }
            ?: CatalogResolverResult(finalUrl = finalUrl, failureReason = "kwik m3u8 not found")
}

class KodikCatalogResolver(http: StreamixHttp = CatalogSafeStreamixHttp()) : HostPageResolver(http, "kodik-catalog", setOf("kodikplayer.com", "kodik.info")) {
    override suspend fun parse(source: EpisodeSource, body: String, finalUrl: String): CatalogResolverResult {
        firstHls(body)?.let { return stream(source, it, finalUrl) }
        val params = Regex("""urlParams\s*=\s*'([^']+)'""").find(body)?.groupValues?.get(1)
            ?: Regex("""urlParams\s*=\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
            ?: return CatalogResolverResult(finalUrl = finalUrl, failureReason = "kodik urlParams not found")
        val playerHost = URI(finalUrl).host ?: return CatalogResolverResult(finalUrl = finalUrl, failureReason = "kodik player host missing")
        val identity = resolveIdentity(body, finalUrl) ?: return CatalogResolverResult(finalUrl = finalUrl, failureReason = "kodik media identity not found")
        val json = org.json.JSONObject(params)
        val form = listOf(
            "d" to json.optString("d"),
            "d_sign" to java.net.URLDecoder.decode(json.optString("d_sign"), "UTF-8"),
            "pd" to json.optString("pd"),
            "pd_sign" to java.net.URLDecoder.decode(json.optString("pd_sign"), "UTF-8"),
            "ref" to java.net.URLDecoder.decode(json.optString("ref"), "UTF-8"),
            "ref_sign" to java.net.URLDecoder.decode(json.optString("ref_sign"), "UTF-8"),
            "type" to identity.first, "id" to identity.second, "hash" to identity.third,
            "bad_user" to "true", "cdn_is_working" to "true"
        ).joinToString("&") { java.net.URLEncoder.encode(it.first, "UTF-8") + "=" + java.net.URLEncoder.encode(it.second, "UTF-8") }
        val response = http.post(
            "https://" + playerHost + "/ftor",
            form,
            source.headers + mapOf("Referer" to finalUrl, "Origin" to "https://" + playerHost, "Content-Type" to "application/x-www-form-urlencoded"),
            finalUrl
        )
        if (response.code !in 200..299) return CatalogResolverResult(httpStatus = response.code, finalUrl = response.url, failureReason = "HTTP " + response.code)
        val scriptUrl = Regex("""<script[^>]+src=["']([^"']*(?:app\.serial|app\.video|player_single|player|app)[^"']*)["']""").find(body)?.groupValues?.get(1)
            ?.let { URI(finalUrl).resolve(it).toString() }
            ?: return CatalogResolverResult(finalUrl = response.url, failureReason = "kodik decoder script not found")
        val script = http.get(scriptUrl, source.headers, finalUrl).text
        val links = runCatching { org.json.JSONObject(response.text).optJSONObject("links") }.getOrNull()
            ?: return CatalogResolverResult(finalUrl = response.url, failureReason = "kodik links payload missing")
        val tokens = listOf("360", "480", "720", "1080").mapNotNull { quality ->
            links.optJSONArray(quality)?.optJSONObject(0)?.optString("src")?.takeIf { it.isNotBlank() }?.let { quality to it }
        }
        if (tokens.isEmpty()) return CatalogResolverResult(finalUrl = response.url, failureReason = "kodik stream token list empty")
        val function = resolveDecoderFunction(script, tokens.map { it.second })
            ?: return CatalogResolverResult(finalUrl = response.url, failureReason = "kodik decoder function not found")
        val streams = tokens.mapNotNull { (quality, token) ->
            val encoded = runCatching {
                val cx = org.mozilla.javascript.Context.enter()
                try {
                    val scope = cx.initStandardObjects()
                    cx.evaluateString(scope, "var t=" + org.json.JSONObject.quote(token) + "; " + function, "kodik", 1, null).toString()
                } finally { org.mozilla.javascript.Context.exit() }
            }.getOrNull() ?: return@mapNotNull null
            val media = runCatching { String(Base64.getDecoder().decode(encoded), Charsets.UTF_8) }.getOrNull()
                ?.let { if (it.startsWith("//")) "https:$it" else it }
            media?.takeIf { it.startsWith("http") }?.let {
                StreamRef(id, it, quality.toIntOrNull() ?: source.quality, if (it.contains(".m3u8", true)) "hls" else "video", source.language, source.subtitleLanguage, source.headers + mapOf("Referer" to response.url), response.url)
            }
        }
        return if (streams.isEmpty()) CatalogResolverResult(finalUrl = response.url, failureReason = "kodik decoder returned no media URLs")
        else CatalogResolverResult(streams = streams, finalUrl = response.url)
    }

    private fun resolveIdentity(body: String, url: String): Triple<String, String, String>? {
        val type = Regex("""\.type\s*=\s*["']([^"']+)["']""").find(body)?.groupValues?.get(1)
        val id = Regex("""\.id\s*=\s*["']?([A-Za-z0-9]+)["']?""").find(body)?.groupValues?.get(1)
        val hash = Regex("""\.hash\s*=\s*["']([^"']+)["']""").find(body)?.groupValues?.get(1)
        if (type != null && id != null && hash != null) return Triple(type, id, hash)
        val p = url.substringAfter("://").substringBefore('?').split('/')
        return if (p.size >= 4) Triple(p[1], p[2], p[3]) else null
    }

    private fun resolveDecoderFunction(script: String, tokens: List<String>): String? {
        for (match in Regex("""atob\([^"']""").findAll(script)) {
            val candidate = extractDecoderFunction(script, match.range.last) ?: continue
            for (token in tokens) {
                val decoded = runCatching {
                    val cx = org.mozilla.javascript.Context.enter()
                    try {
                        val scope = cx.initStandardObjects()
                        cx.evaluateString(scope, "var t=" + org.json.JSONObject.quote(token) + "; " + candidate, "kodik-probe", 1, null).toString()
                    } finally { org.mozilla.javascript.Context.exit() }
                }.getOrNull() ?: continue
                val url = runCatching { String(Base64.getDecoder().decode(decoded), Charsets.UTF_8) }.getOrNull()
                    ?.let { if (it.startsWith("//")) "https:$it" else it }
                if (url?.startsWith("http") == true) return candidate
            }
        }
        return null
    }

    private fun extractDecoderFunction(script: String, start: Int): String? {
        val function = StringBuilder("(")
        val opened = ArrayDeque<Char>()
        opened.addFirst('(')
        for (i in start until script.length) {
            val char = script[i]
            when (char) {
                '(', '{' -> opened.addFirst(char)
                ')', '}' -> if (opened.isNotEmpty()) opened.removeFirst()
            }
            function.append(char)
            if (opened.isEmpty()) break
        }
        return function.takeIf { it.length > 1 }?.toString()
    }
}
