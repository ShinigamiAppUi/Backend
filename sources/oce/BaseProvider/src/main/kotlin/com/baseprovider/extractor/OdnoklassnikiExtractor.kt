package com.baseprovider.extractor

import com.baseprovider.streamix.*
import com.baseprovider.streamix.StreamixLogger

import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

open class Odnoklassniki : StreamixExtractorApi() {
    override var name = "OkRu"; override var mainUrl = "https://ok.ru"; override val requiresReferer = false
    override suspend fun getUrl(url: String, referer: String?,
        subtitleCallback: (StreamixSubtitleFile) -> Unit,
            callback: (StreamixExtractorLink) -> Unit) {
        val headers = mapOf(
            "Accept" to "*/*",
            "Connection" to "keep-alive",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Origin" to mainUrl,
            "User-Agent" to DEFAULT_UA
        )
        val videoHeaders = MasterLinkGenerator.minimalVideoHeaders
        // ok.ru memberi token HLS yang ditolak CDN (HTTP 400) bila diambil dari
        // halaman embed (/videoembed/). Ambil dari halaman video normal
        // (/video/) yang menghasilkan token valid, fallback ke embed bila
        // halaman video tidak menyediakan data.
        val videoPageUrl = if (url.contains("/videoembed/")) {
            url.replace("/videoembed/", "/video/")
        } else url
        var videoReq = app.get(videoPageUrl, headers = headers, timeout = 5_000L).text.replace("\\&quot;", "\"")
            .replace("\\\\", "\\")
        if (!videoReq.contains("hlsManifestUrl") && !videoReq.contains("\"videos\"")) {
            val embedUrl = url.replace("/video/", "/videoembed/")
            videoReq = app.get(embedUrl, headers = headers, timeout = 5_000L).text.replace("\\&quot;", "\"")
                .replace("\\\\", "\\")
        }

        val hlsUrl = Regex(""""hlsManifestUrl":\s*"([^"]+)"""")
            .find(videoReq)?.groupValues?.getOrNull(1)
            ?.let { MasterLinkGenerator.decodeUnicodeEscapes(it) }
        if (!hlsUrl.isNullOrBlank()) {
            StreamixLogger.d("OkRu", "Using adaptive HLS: ${hlsUrl.take(90)}...")
            // Node CDN ok.ru di-assign per video & per waktu; sebagian node
            // (mis. ok6-4.vkuser.net) punya routing pathologis (connect 5s+,
            // throughput <40KB/s) yang bikin buffer padahal node lain cepat.
            // Header tidak berpengaruh. Probe throughput segmen sungguhan:
            // - CEPAT  -> deliver langsung (node bagus, lanjut seperti biasa).
            // - LAMBAT -> tunda delivery agar sumber lain (prioritas 80)
            //   menang duluan; ok.ru tetap di-deliver sebagai last resort
            //   (bukan skip -> tidak pernah 0-link / error player).
            val probeStart = System.currentTimeMillis()
            if (!okruCdnIsFast(hlsUrl, videoHeaders)) {
                val wait = OKRU_SLOW_DELAY_MS - (System.currentTimeMillis() - probeStart)
                if (wait > 0) {
                    StreamixLogger.w("OkRu", "CDN ok.ru lambat, tunda ${wait}ms agar sumber lain menang duluan")
                    delay(wait)
                }
            }
            // Kirim master HLS penuh (perilaku stabil). Headers dipilih otomatis
            // per-host oleh AdaptiveHeaderProbe (uji combo bare/referer/origin/browser).
            MasterLinkGenerator.createSmartLink(
                this.name, hlsUrl, null,
                headers = videoHeaders, bareHeaders = true,
                callback = callback
            )
            return
        }

        val videosStr = Regex(""""videos":(\[[^]]*])""").find(videoReq)
            ?.groupValues?.get(1) ?: return
        tryParseJson<List<OkRuVideo>>(videosStr)?.forEach { video ->
            val videoUrl = if (video.url.startsWith("//")) "https:${video.url}" else video.url
            MasterLinkGenerator.createSmartLink(
                this.name,
                videoUrl,
                null,
                MasterLinkGenerator.getQualityFromName(video.name),
                headers = videoHeaders,
                bareHeaders = true,
                callback = callback
            )
        }
    }
    data class OkRuVideo(@SerializedName("name") val name: String,
        @SerializedName("url") val url: String)

    // Sampel throughput cukup untuk membedakan node cepat (>150KB/s) dari node
    // rusak (<40KB/s, mis. ok6-4.vkuser.net) dengan margin lebar.
    private val OKRU_PROBE_BYTES = 300L * 1024
    private val OKRU_PROBE_BUDGET_MS = 4_000L
    private val OKRU_PROBE_TOTAL_MS = 6_000L
    private val OKRU_SLOW_DELAY_MS = 6_000L

    /**
     * true = node CDN terbukti cepat (segmen terbaca >= OKRU_PROBE_BYTES dalam
     * budget). Semua jalur lain (master/variant/segment gagal, HTTP non-2xx,
     * timeout, exception) mengembalikan false -> link tetap di-deliver tapi
     * ditunda agar sumber lain menang duluan. Tidak ada skip, sehingga tidak
     * pernah menghasilkan 0-link. Mirror pola AdaptiveHeaderProbe: baca body
     * sungguhan (bounded, tanpa Range agar tidak kena 416 di segmen kecil).
     */
    private suspend fun okruCdnIsFast(hlsUrl: String, headers: Map<String, String>): Boolean =
        try {
            withTimeoutOrNull(OKRU_PROBE_TOTAL_MS) {
                val start = System.currentTimeMillis()
                val master = app.get(hlsUrl, headers = headers, timeout = 5_000L).text
                val variant = pickProbeVariant(master, hlsUrl) ?: return@withTimeoutOrNull false
                val playlist = app.get(variant, headers = headers, timeout = 5_000L).text
                val segment = playlist.lineSequence()
                    .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                    ?: return@withTimeoutOrNull false
                val segmentUrl = if (segment.startsWith("http")) segment
                    else variant.substringBeforeLast('/') + '/' + segment
                val probe = app.probe(
                    segmentUrl,
                    headers = headers,
                    timeout = 5_000L,
                    maxBytes = OKRU_PROBE_BYTES
                )
                if (probe.code !in 200..399) return@withTimeoutOrNull false
                val got = probe.bytesRead
                val eof = probe.eof
                // Segmen kecil (mis. 144p ~250KB) tetap dianggap cepat bila
                // habis terbaca sebelum budget (eof) — hindari false reject.
                got >= OKRU_PROBE_BYTES || (eof && got > 0)
            } ?: false
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }

    /**
     * Pilih varian dengan RESOLUTION terkecil (segmennya cukup besar untuk
     * sampel throughput), fallback ke varian pertama bila tanpa RESOLUTION.
     */
    private fun pickProbeVariant(master: String, baseUrl: String): String? {
        val lines = master.lines()
        var best: Pair<Int, String>? = null
        var first: String? = null
        var i = 0
        while (i < lines.size - 1) {
            if (lines[i].startsWith("#EXT-X-STREAM-INF")) {
                val url = lines[i + 1].trim()
                if (url.isNotEmpty() && first == null) first = url
                val h = Regex("RESOLUTION=\\d+x(\\d+)").find(lines[i])
                    ?.groupValues?.get(1)?.toIntOrNull()
                if (h != null && (best == null || h < best.first)) best = h to url
            }
            i++
        }
        val chosen = best?.second ?: first ?: return null
        return if (chosen.startsWith("http")) chosen
            else baseUrl.substringBeforeLast('/') + chosen
    }
}
