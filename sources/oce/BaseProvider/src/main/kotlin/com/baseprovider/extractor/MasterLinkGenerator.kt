package com.baseprovider.extractor
import com.baseprovider.streamix.StreamixStream
import com.baseprovider.streamix.StreamixStreamType

const val DEFAULT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

object MasterLinkGenerator {

    internal val DEFAULT_QUALITY_STRIP = Regex("""\d{3,4}p|HD|SD|FHD""",
        RegexOption.IGNORE_CASE)

    private val BROWSER_LIKE_HEADERS = mapOf(
        "Accept" to "*/*",
        "Connection" to "keep-alive",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "cross-site",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "User-Agent" to DEFAULT_UA
    )

    fun decodeUnicodeEscapes(input: String): String {
        if (!input.contains("\\u")) return input
        return Regex("""\\u([0-9A-Fa-f]{4})""").replace(input) { m ->
            m.groupValues[1].toInt(16).toChar().toString()
        }
    }

    val minimalVideoHeaders = mapOf(
        "Accept" to "*/*",
        "User-Agent" to DEFAULT_UA
    )

    private fun enrichHeaders(
        headers: Map<String, String>?,
        bareHeaders: Boolean
    ): Map<String, String> {
        val provided = headers ?: emptyMap()
        if (bareHeaders) {
            return if (provided.isEmpty()) minimalVideoHeaders else provided
        }
        if (provided.isEmpty()) return minimalVideoHeaders
        val merged = HashMap(BROWSER_LIKE_HEADERS)
        merged.putAll(provided)
        return merged
    }

    /**
     * URL non-media/tracking yang terdeteksi pernah lolos sebagai "link"
     * (kasus Sacrifice: ping.gif JWPlayer & blank.mp4 plyr). Ditolak di pintu.
     */
    val JUNK_URL_REGEX = Regex(
        "(?i)(jwpltx\\.com|plyr\\.io/static|google-analytics|googletagmanager|" +
        "doubleclick|/ping[._]|\\.gif(\\?|\$)|/static/blank\\.)"
    )

    suspend fun createSmartStreamLink(
        source: String,
        url: String,
        referer: String?,
        quality: Int? = null,
        headers: Map<String, String>? = null,
        qualityStripRegex: Regex = DEFAULT_QUALITY_STRIP,
        bareHeaders: Boolean = false,
        providerTag: String = "ExtractorEngine",
        runId: String? = null,
        callback: (StreamixStream) -> Unit
    ) {
        val __t0 = System.currentTimeMillis()
        if (url.isBlank() || JUNK_URL_REGEX.containsMatchIn(url)) {
            // Link kosong ATAU non-media/tracking: jangan sampai ke player.
            com.baseprovider.log.logFail(
                providerTag,
                "createSmartLink rejected blank url for $source",
                url = url,
                method = "createSmartLink",
                type = com.baseprovider.log.FailureType.INVALID_URL,
                stage = "VERIFY",
                extractor = source,
                runId = runId
            )
            return
        }
        val isAdaptive = url.contains(".m3u8") || url.contains(".mpd")
        val safeHeaders = enrichHeaders(headers, bareHeaders)

        // Adaptive headers: untuk link bare, probe otomatis (valid + tercepat)
        // per-host, hasil di-cache. Menghindari test manual per extractor
        // (kasus OkRu dulu): uji beberapa combo header (bare/referer/origin/
        // browser-like) paralel, pilih yang valid 2xx/3xx dan tercepat.
        var effectiveReferer = referer
        var effectiveHeaders = safeHeaders
        var probeBody: String? = null
        var probeBodyTruncated = false
        if (bareHeaders) {
            // Probe otomatis (valid pertama yang selesai menang, sisanya di-cancel).
            // Headers asli extractor ikut diuji sebagai combo EXPLICIT.
            // captureBody hanya untuk master m3u8: body pemenang dipakai
            // verifikasi variant di pass yang sama (P1, hindari fetch 2x).
            val decision = AdaptiveHeaderProbe.resolve(url, referer, headers,
                captureBody = isAdaptive && url.contains(".m3u8"))
            if (!decision.valid) {
                // Link gagal test (non-2xx/3xx) di semua combo header.
                // Jangan kirim link rusak ke player (avoid error 2004).
                com.baseprovider.log.logFail(
                    providerTag,
                    "AdaptiveHeaderProbe rejected link (non-2xx/3xx on all combos): $url",
                    url = url,
                    method = "createSmartLink",
                    type = com.baseprovider.log.FailureType.HTTP_FAILURE,
                    stage = "PROBE",
                    extractor = source,
                    runId = runId
                )
                return
            }
            effectiveReferer = decision.referer
            effectiveHeaders = decision.headers
            probeBody = decision.capturedBody
            probeBodyTruncated = decision.bodyTruncated
        }

        val cleanName = source.replace(qualityStripRegex, "").trim()

        // Verifikasi master m3u8: buang variant tanpa URI (mis. baris kosong
        // setelah #EXT-X-STREAM-INF) yang bikin ExoPlayer error 3002
        // (PARSING_MANIFEST_MALFORMED). Master bersih tetap dikirim as-is
        // (ABR jalan); hanya saat ada variant rusak variant valid dikirim
        // terpisah dan yang rusak TIDAK pernah sampai ke player.
        if (isAdaptive && url.contains(".m3u8")) {
            // Verifikasi master: pakai body hasil probe pemenang bila tersedia
            // (P1, satu fetch), fallback fetch penuh bila body null/truncated
            // (waiter single-flight, master >1MB, atau gagal baca body).
            val verdict = if (probeBody != null && !probeBodyTruncated) {
                M3u8MasterVerifier.classify(url, M3u8MasterVerifier.parseVariants(probeBody))
            } else {
                M3u8MasterVerifier.verify(url, effectiveReferer, effectiveHeaders)
            }
            when (verdict) {
                is streamix.core.StreamixM3u8Verifier.Verdict.Valid -> {
                    for ((variantUrl, height) in verdict.variants) {
                        callback(StreamixStream(source = source, name = cleanName, url = variantUrl, quality = height ?: detectQualityFromUrl(variantUrl), type = StreamixStreamType.M3u8, referer = effectiveReferer, headers = effectiveHeaders))
                    }
                    com.baseprovider.log.logSuccess(source,
                        "M3U8 master valid -> ${verdict.variants.size} variant dikirim",
                        url = url, extractor = source, runId = runId)
                    return
                }
                is streamix.core.StreamixM3u8Verifier.Verdict.AllMalformed -> {
                    com.baseprovider.log.logFail(
                        providerTag,
                        "M3u8MasterVerifier rejected master (all variants malformed): $url",
                        url = url,
                        method = "createSmartLink",
                        type = com.baseprovider.log.FailureType.INVALID_URL,
                        stage = "VERIFY",
                        extractor = source,
                        runId = runId
                    )
                    return
                }
                streamix.core.StreamixM3u8Verifier.Verdict.Clean -> {
                    // Master bersih / bukan master / fetch gagal: deliver as-is.
                    com.baseprovider.log.logSuccess(source,
                        "M3U8 clean/bukan-master -> dikirim as-is",
                        url = url, extractor = source, runId = runId)
                }
            }
        }

        callback(StreamixStream(source = source, name = cleanName, url = url, quality = if (!isAdaptive) quality ?: detectQualityFromUrl(url) else null, type = if (url.contains(".mpd")) StreamixStreamType.Dash else if (isAdaptive) StreamixStreamType.M3u8 else StreamixStreamType.Video, referer = effectiveReferer, headers = effectiveHeaders))
        com.baseprovider.log.logSuccess(source,
            "link delivered (${if (isAdaptive) "adaptive" else "direct"}) " +
                "dalam ${System.currentTimeMillis() - __t0} ms",
            url = url, extractor = source, runId = runId,
            durationMs = System.currentTimeMillis() - __t0)
    }

    /** Streamix-native callback compatibility for donor extractors. */
    suspend fun createSmartLink(
        source: String,
        url: String,
        referer: String?,
        quality: Int? = null,
        headers: Map<String, String>? = null,
        qualityStripRegex: Regex = DEFAULT_QUALITY_STRIP,
        bareHeaders: Boolean = false,
        providerTag: String = "ExtractorEngine",
        runId: String? = null,
        callback: (com.baseprovider.streamix.StreamixExtractorLink) -> Unit
    ) {
        createSmartStreamLink(
            source = source,
            url = url,
            referer = referer,
            quality = quality,
            headers = headers,
            qualityStripRegex = qualityStripRegex,
            bareHeaders = bareHeaders,
            providerTag = providerTag,
            runId = runId
        ) { stream ->
            callback(
                com.baseprovider.streamix.StreamixExtractorLink(
                    source = stream.source,
                    name = stream.name,
                    url = stream.url,
                    type = when (stream.type) {
                        StreamixStreamType.M3u8 -> com.baseprovider.streamix.StreamixExtractorLinkType.M3U8
                        StreamixStreamType.Dash -> com.baseprovider.streamix.StreamixExtractorLinkType.DASH
                        StreamixStreamType.Video -> com.baseprovider.streamix.StreamixExtractorLinkType.VIDEO
                    },
                    quality = stream.quality ?: com.baseprovider.streamix.Qualities.Unknown.value,
                    referer = stream.referer.orEmpty(),
                    headers = stream.headers,
                    extractorData = stream.extractorData
                )
            )
        }
    }

    fun refineAndDeliverStreamix(
        links: List<StreamixStream>,
        finalCallback: (StreamixStream) -> Unit,
        qualityStripRegex: Regex = DEFAULT_QUALITY_STRIP
    ) {
        val seenAdaptiveSources = mutableSetOf<String>()
        links.forEach { link ->
            val isAdaptive = link.type == StreamixStreamType.M3u8 || link.type == StreamixStreamType.Dash
            if (isAdaptive) {
                if (seenAdaptiveSources.add(link.source)) {
                    val refinedName = link.name.ifBlank {
                        link.source.replace(qualityStripRegex, "").trim()
                    }
                    finalCallback(link.copy(name = refinedName))
                }
            } else {
                val cleanSource = link.name.ifBlank {
                    link.source.replace(qualityStripRegex, "").trim()
                }
                finalCallback(link.copy(name = cleanSource))
            }
        }
    }

    private fun detectQualityFromUrl(url: String): Int =
        streamix.core.StreamixExtractorPatterns.detectQualityFromUrl(url)

    fun getQualityFromName(name: String?): Int =
        streamix.core.StreamixExtractorPatterns.getQualityFromName(name)
}
