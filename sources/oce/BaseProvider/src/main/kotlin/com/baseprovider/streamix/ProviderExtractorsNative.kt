package com.baseprovider.streamix

import com.baseprovider.extractor.*
import com.baseprovider.config.ExtractorConfigRegistry
import kotlinx.coroutines.currentCoroutineContext
import streamix.core.StreamixHttpContext
/**
 * Native JVM extractor registry.
 *
 * This is the Streamix runtime source of truth. Only native Streamix
 * extractors participate in this registry.
 */
object ProviderExtractorsNative {
    private val extractors: List<StreamixExtractorApi> = listOf(
        Odnoklassniki(),
        Rumble(),
        StreamRuby(),
        Svanila(),
        Svilla(),
        ByseSX(),
        Hownetwork(),
        Cloudhownetwork(),
        PlayStreamplay(),
        AbyssPlayer(),
        Filedon(),
        BloggerVideo(),
        Wishfast(),
        Minochinos(),
        Morencius(),
        ShortIcu(),
        PlayPutarIn(),
        MegaPlay(),
        AWSStream(),
        LuluStream(),
        Dhcplay(),
        Voe(),
        Xtwap(),
        Gdplayer(),
        Vidguardto2(),
        Vidguardto(),
        Movearnpre(),
        Lk21PlayerPage(),
        VideoNodePage(),
        Dailymotion(),
        PlayCdn(),
        EmTurbovid(),
        Krakenfiles(),
        VideoplayerVip(),
        Anonmp4(),
        StreamHG(),
        LinkboxExtractor(),
        KuramadriveExtractor(),
        ArchiveOrg(),
        TurboVid(),
        YourUpload(),
        Pixeldrain(),
        Gofile(),
        Buzzheavier(),
        Vtbe(),
        FileMoonSx(),
        StreamSBWaaw(),
        GenericPlayerPage()
    )

    private val configExtractorIds = listOf(
        "CyberJunkiePage",
        "DTube",
        "EmbedPyrox",
        "Odysee",
        "PlayMogo",
        "PlayStreamVideo",
        "SamehadakuPage",
        "VeevTo"
    )

    /**
     * Adapter exposed to config-driven extractors for recursive/native fallback.
     * The backing list is lazy, so config extractors can safely delegate after
     * the complete native catalog has been assembled.
     */
    private val registryAdapter = object : StreamixExtractorRegistry {
        override fun getMatchingExtractors(url: String): List<StreamixExtractor> =
            getMatchingStreamixExtractors(url)

        override fun hasMatchingExtractor(url: String): Boolean =
            getMatchingStreamixExtractors(url).isNotEmpty()

        override fun all(): List<StreamixExtractor> = allExtractors

        override suspend fun resolve(
            url: String,
            referer: String?,
            subtitleCallback: (StreamixSubtitle) -> Unit,
            callback: (StreamixStream) -> Unit,
            callChain: Set<String>
        ): Boolean = resolveInternal(
            url = url,
            referer = referer,
            subtitleCallback = { legacy ->
                subtitleCallback(
                    StreamixSubtitle(
                        url = legacy.url,
                        language = legacy.language,
                        headers = legacy.headers,
                        referer = legacy.referer
                    )
                )
            },
            callback = { legacy ->
                callback(
                    StreamixStream(
                        source = legacy.source,
                        name = legacy.name,
                        url = legacy.url,
                        quality = legacy.quality.takeIf { it >= 0 },
                        type = when (legacy.type) {
                            StreamixExtractorLinkType.M3U8 -> streamix.core.StreamixStreamType.M3u8
                            StreamixExtractorLinkType.DASH -> streamix.core.StreamixStreamType.Dash
                            else -> streamix.core.StreamixStreamType.Video
                        },
                        referer = legacy.referer.takeIf { it.isNotBlank() },
                        headers = legacy.headers,
                        extractorData = legacy.extractorData
                    )
                )
            },
            callChain = callChain
        )
    }

    private val configExtractors: List<StreamixExtractor> by lazy {
        configExtractorIds.mapNotNull { id ->
            ExtractorConfigRegistry.get(id)?.let { config ->
                ConfigDrivenStreamixExtractor(config, registryAdapter)
            }
        }
    }

    private val allExtractors: List<StreamixExtractor> by lazy {
        (extractors + configExtractors).distinctBy { it.id.lowercase() }
    }
    private val byId: Map<String, StreamixExtractor> by lazy {
        allExtractors.associateBy { it.id.lowercase() }
    }

    private fun normalizeHost(url: String): String =
        runCatching { java.net.URI(url).host?.lowercase().orEmpty() }
            .getOrDefault("")

    fun all(): List<StreamixExtractor> = allExtractors

    fun get(id: String): StreamixExtractor? = byId[id.lowercase()]

    fun getMatchingStreamixExtractors(url: String): List<StreamixExtractor> {
        val host = normalizeHost(url)
        if (host.isBlank()) return emptyList()
        return allExtractors.filter { extractor ->
            extractor.domains.any { domain ->
                host == domain || host.endsWith(".$domain")
            }
        }
    }

    fun hasMatchingExtractor(url: String): Boolean =
        getMatchingStreamixExtractors(url).isNotEmpty()

    suspend fun resolve(
        url: String,
        referer: String? = null,
        subtitleCallback: (StreamixSubtitleFile) -> Unit = {},
        callChain: Set<String> = emptySet(),
        callback: (StreamixExtractorLink) -> Unit
    ): Boolean = resolveInternal(
        url, referer, subtitleCallback, callback, callChain
    )

    private suspend fun resolveInternal(
        url: String,
        referer: String?,
        subtitleCallback: (StreamixSubtitleFile) -> Unit,
        callback: (StreamixExtractorLink) -> Unit,
        callChain: Set<String>
    ): Boolean {
        for (extractor in getMatchingStreamixExtractors(url)) {
            if (extractor.id in callChain) continue

            val result = runCatching {
                extractor.extract(
                    StreamixExtractorRequest(
                        url = url,
                        referer = referer,
                        providerId = extractor.id,
                        scopedHttp = currentCoroutineContext()[StreamixHttpContext]?.http
                    )
                )
            }.getOrNull() ?: continue

            result.subtitles.forEach {
                subtitleCallback(
                    StreamixSubtitleFile(
                        language = it.language ?: "Unknown",
                        url = it.url,
                        headers = it.headers,
                        referer = it.referer
                    )
                )
            }

            result.streams.forEach { stream ->
                callback(
                    StreamixExtractorLink(
                        source = extractor.id,
                        name = extractor.id,
                        url = stream.url,
                        type = when (stream.type) {
                            "m3u8" -> StreamixExtractorLinkType.M3U8
                            "dash" -> StreamixExtractorLinkType.DASH
                            else -> StreamixExtractorLinkType.VIDEO
                        },
                        quality = stream.quality ?: Qualities.Unknown.value,
                        referer = stream.referer.orEmpty(),
                        headers = stream.headers
                    )
                )
            }

            if (result.streams.isNotEmpty()) return true
        }
        return false
    }

    val registry: StreamixExtractorRegistry = registryAdapter

}
