package com.baseprovider.streamix

import com.google.gson.Gson
import org.jsoup.nodes.Document
import java.net.URI
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.EmptyCoroutineContext
import streamix.core.StreamixHttpContext

/**
 * Streamix-owned compatibility surface for legacy OCE extractor implementations.
 *
 * This preserves the small callback-shaped API used by donor extractors while
 * their implementations migrate onto the canonical Streamix contracts.
 */
data class StreamixExtractorLink(
    val source: String,
    val name: String,
    val url: String,
    val type: StreamixExtractorLinkType = StreamixExtractorLinkType.VIDEO,
    var quality: Int = Qualities.Unknown.value,
    var referer: String = "",
    var headers: Map<String, String> = emptyMap(),
    var extractorData: String? = null
)

enum class StreamixExtractorLinkType { M3U8, DASH, VIDEO }

typealias SubtitleFile = StreamixSubtitleFile
typealias ExtractorLink = StreamixExtractorLink

data class StreamixSubtitleFile(
    val language: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val referer: String? = null
)

object Qualities {
    data class Quality(val value: Int)
    val Unknown = Quality(-1)
    val P144 = Quality(144)
    val P240 = Quality(240)
    val P360 = Quality(360)
    val P480 = Quality(480)
    val P720 = Quality(720)
    val P1080 = Quality(1080)
    val P1440 = Quality(1440)
    val P2160 = Quality(2160)
}

fun newExtractorLink(
    source: String,
    name: String,
    url: String,
    type: StreamixExtractorLinkType = StreamixExtractorLinkType.VIDEO,
    block: StreamixExtractorLink.() -> Unit = {}
): StreamixExtractorLink = StreamixExtractorLink(
    source = source,
    name = name,
    url = url,
    type = type
).apply(block)

abstract class StreamixExtractorApi : StreamixExtractor {

    protected suspend fun loadExtractorWithFallbackCustom(
        url: String,
        referer: String?,
        subtitleCallback: (StreamixSubtitleFile) -> Unit,
        callback: (StreamixExtractorLink) -> Unit,
        providerTag: String = name,
        callChain: String = providerTag
    ): Boolean = ProviderExtractorsNative.resolve(
        url = url,
        referer = referer,
        subtitleCallback = subtitleCallback,
        callChain = setOf(providerTag, callChain).filter { it.isNotBlank() }.toSet(),
        callback = callback
    )

    protected suspend fun loadExtractor(
        url: String,
        referer: String?,
        subtitleCallback: (StreamixSubtitleFile) -> Unit,
        callback: (StreamixExtractorLink) -> Unit
    ): Boolean = ProviderExtractorsNative.resolve(
        url = url,
        referer = referer,
        subtitleCallback = subtitleCallback,
        callChain = setOf(name),
        callback = callback
    )
    abstract val name: String
    abstract val mainUrl: String
    open val requiresReferer: Boolean = false

    override val id: String
        get() = name

    override val domains: Set<String>
        get() = runCatching {
            URI(mainUrl).host?.lowercase()?.let(::setOf) ?: emptySet()
        }.getOrDefault(emptySet())

    override suspend fun extract(request: StreamixExtractorRequest): StreamixExtractorResult {
        val streams = mutableListOf<streamix.core.ProviderStream>()
        val subtitles = mutableListOf<streamix.core.ProviderSubtitle>()

        withContext(request.scopedHttp?.let(::StreamixHttpContext) ?: EmptyCoroutineContext) {
            getUrl(
            request.url,
            request.referer,
            { subtitle ->
                subtitles += streamix.core.ProviderSubtitle(
                    url = subtitle.url,
                    language = subtitle.language,
                    headers = subtitle.headers,
                    referer = subtitle.referer
                )
            },
            { link ->
                streams += streamix.core.ProviderStream(
                    providerId = request.providerId ?: name,
                    url = link.url,
                    quality = link.quality.takeIf { it >= 0 },
                    type = when (link.type) {
                        StreamixExtractorLinkType.M3U8 -> "m3u8"
                        StreamixExtractorLinkType.DASH -> "dash"
                        StreamixExtractorLinkType.VIDEO -> "video"
                    },
                    headers = link.headers,
                    referer = link.referer.ifBlank { null }
                )
            }
            )
        }

        return StreamixExtractorResult(streams = streams, subtitles = subtitles)
    }

    abstract suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (StreamixSubtitleFile) -> Unit,
        callback: (StreamixExtractorLink) -> Unit
    )
}

data class StreamixHttpCompatResponse(
    val code: Int,
    val url: String,
    val text: String,
    val document: Document,
    val headers: Map<String, List<String>> = emptyMap(),
    val cookies: Map<String, String> = emptyMap()
)

object app {
    /** True only while an extractor call carries an explicit request-scoped HTTP policy. */
    suspend fun hasScopedHttpRequest(): Boolean = currentCoroutineContext()[StreamixHttpContext] != null

    private fun globalHttp() = requireNotNull(StreamixRuntime.http) {
        "StreamixHttp is not installed"
    }

    suspend fun get(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        timeout: Long = 15_000L,
        cookies: Map<String, String> = emptyMap()
    ): StreamixHttpCompatResponse {
        val transport = currentCoroutineContext()[StreamixHttpContext]?.http ?: globalHttp()
        val r = transport.get(url, headers = headers, referer = referer, cookies = cookies, timeoutMs = timeout)
        return StreamixHttpCompatResponse(r.code, r.url, r.text, r.document, r.headers, r.cookies)
    }

    suspend fun probe(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        timeout: Long = 15_000L,
        cookies: Map<String, String> = emptyMap(),
        maxBytes: Long = Long.MAX_VALUE
    ): streamix.core.StreamixHttpProbeResponse {
        val transport = currentCoroutineContext()[StreamixHttpContext]?.http ?: globalHttp()
        return transport.probe(
            url, headers = headers, referer = referer, cookies = cookies,
            timeoutMs = timeout, maxBytes = maxBytes
        )
    }

    suspend fun post(
        url: String,
        data: Map<String, String>? = null,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        timeout: Long = 15_000L,
        cookies: Map<String, String> = emptyMap()
    ): StreamixHttpCompatResponse {
        val body = data.orEmpty().entries.joinToString("&") {
            java.net.URLEncoder.encode(it.key, "UTF-8") + "=" +
                java.net.URLEncoder.encode(it.value, "UTF-8")
        }
        val transport = currentCoroutineContext()[StreamixHttpContext]?.http ?: globalHttp()
        val r = transport.post(
            url,
            body = body,
            headers = headers + ("Content-Type" to "application/x-www-form-urlencoded"),
            referer = referer,
            cookies = cookies,
            timeoutMs = timeout
        )
        return StreamixHttpCompatResponse(r.code, r.url, r.text, r.document, r.headers, r.cookies)
    }
}

inline fun <reified T> tryParseJson(text: String): T? =
    runCatching { Gson().fromJson(text, T::class.java) }.getOrNull()



fun fixUrlSmart(value: String, baseUrl: String): String =
    runCatching {
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) value
        else URI(baseUrl).resolve(value).toString()
    }.getOrDefault(value)
