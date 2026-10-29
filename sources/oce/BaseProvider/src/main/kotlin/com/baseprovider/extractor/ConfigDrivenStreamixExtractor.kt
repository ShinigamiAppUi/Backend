package com.baseprovider.extractor

import com.baseprovider.config.ExtractorConfig
import com.baseprovider.config.ExtractorStep
import com.baseprovider.config.ExtractorVariant
import com.baseprovider.log.FailureType
import com.baseprovider.log.logDebug
import com.baseprovider.log.logFail
import com.baseprovider.log.logSuccess
import com.baseprovider.streamix.StreamixExtractor
import com.baseprovider.streamix.StreamixExtractorRegistry
import com.baseprovider.streamix.StreamixRuntime
import com.baseprovider.streamix.StreamixStream
import com.baseprovider.streamix.StreamixStreamType
import com.baseprovider.streamix.StreamixSubtitle
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.net.URI
import java.net.URLEncoder
import org.jsoup.Jsoup
import kotlinx.coroutines.withContext
import streamix.core.StreamixHttpContext
import kotlin.coroutines.EmptyCoroutineContext

class ConfigDrivenStreamixExtractor(
    private val config: ExtractorConfig,
    private val registry: StreamixExtractorRegistry,
    private val webResolver: () -> streamix.core.StreamixWebResolver? = { StreamixRuntime.webResolver }
) : StreamixExtractor {
    override val id: String = config.id
    override val domains: Set<String> = setOf(
        URI(config.mainUrl).host?.lowercase().orEmpty()
    )

    private class State(
        val url: String,
        val referer: String?,
        val id: String?,
        val variant: ExtractorVariant
    ) {
        val base: String = runCatching {
            URI(url).let { it.scheme + "://" + it.host + it.port.takeIf { p -> p != -1 }?.let { p -> ":" + p }.orEmpty() }
        }.getOrDefault(url)
        val vars = mutableMapOf<String, String>()
        val urls = linkedSetOf<String>()

        fun template(value: String, mainUrl: String): String {
            var out = value
                .replace("{mainUrl}", mainUrl)
                .replace("{url}", url)
                .replace("{base}", base)
                .replace("{id}", id.orEmpty())
                .replace("{referer}", referer.orEmpty())
            vars.forEach { (key, variable) -> out = out.replace("{$key}", variable) }
            return out
        }

        fun referer(stepReferer: String?, mainUrl: String): String? =
            stepReferer?.takeIf { it.isNotBlank() }?.let { template(it, mainUrl) }
                ?: variant.referer.takeIf { it.isNotBlank() }?.let { template(it, mainUrl) }
                ?: referer

        fun headers(step: Map<String, String>, mainUrl: String): Map<String, String> {
            val result = HashMap(variant.headers)
            result.putAll(step)
            if (variant.userAgent.isNotBlank()) result["User-Agent"] = variant.userAgent
            return result.mapValues { (_, value) -> template(value, mainUrl) }
        }
    }

    override suspend fun extract(
        request: streamix.core.StreamixExtractorRequest
    ): streamix.core.StreamixExtractorResult {
        val streams = mutableListOf<StreamixStream>()
        val subtitles = mutableListOf<StreamixSubtitle>()
        val started = System.currentTimeMillis()

        val completed = withContext(request.scopedHttp?.let(::StreamixHttpContext) ?: EmptyCoroutineContext) {
        for (variant in config.variants) {
            val state = State(request.url, request.referer, extractId(request.url), variant)
            try {
                for (step in config.steps) {
                    execute(step, state, subtitles, streams, setOf(id), request.scopedHttp ?: requireNotNull(StreamixRuntime.http) { "StreamixHttp is not installed" }, request.scopedHttp != null)
                    if (state.urls.isNotEmpty()) break
                }
                if (state.urls.isNotEmpty()) {
                    deliver(state, request, streams)
                    if (streams.isNotEmpty()) {
                        logSuccess(id, "delivered ${streams.size} stream(s)",
                            url = request.url, extractor = id)
                        return@withContext result(request, streams, subtitles)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                logDebug(id, "variant ${variant.name} failed: ${e.message}")
            }
        }
        result(request, streams, subtitles)
        }
        if (streams.isNotEmpty()) return completed

        logFail(id, "all config variants failed", url = request.url,
            method = "extract", type = FailureType.EXTRACTOR_FAILURE,
            stage = "EXTRACT", extractor = id,
            durationMs = System.currentTimeMillis() - started)
        return result(request, streams, subtitles)
    }

    private fun result(
        request: streamix.core.StreamixExtractorRequest,
        streams: List<StreamixStream>,
        subtitles: List<StreamixSubtitle>
    ): streamix.core.StreamixExtractorResult =
        streamix.core.StreamixExtractorResult(
            streams = streams.map {
                streamix.core.ProviderStream(
                    providerId = request.providerId.orEmpty(),
                    url = it.url,
                    quality = it.quality,
                    type = when (it.type) {
                        StreamixStreamType.M3u8 -> "m3u8"
                        StreamixStreamType.Dash -> "dash"
                        StreamixStreamType.Video -> "video"
                    },
                    headers = it.headers,
                    referer = it.referer
                )
            },
            subtitles = subtitles.map {
                streamix.core.ProviderSubtitle(
                    url = it.url,
                    language = it.language,
                    headers = it.headers,
                    referer = it.referer
                )
            }
        )

    private fun extractId(url: String): String? {
        val source = config.idSource ?: return null
        return when (source.type.lowercase()) {
            "query" -> runCatching {
                Regex("[?&]${Regex.escape(source.param)}=([^&]+)")
                    .find(url)?.groupValues?.getOrNull(1)
            }.getOrNull()
            "path" -> url.trimEnd('/').substringAfterLast('/').substringBefore('?')
            "regex" -> runCatching {
                Regex(source.pattern).find(url)?.groupValues?.getOrNull(source.group)
            }.getOrNull()
            else -> null
        }?.takeIf { it.isNotBlank() }
    }

    private suspend fun execute(
        step: ExtractorStep,
        state: State,
        subtitles: MutableList<StreamixSubtitle>,
        streams: MutableList<StreamixStream>,
        chain: Set<String>,
        http: streamix.core.StreamixHttp,
        catalogScoped: Boolean
    ) {
        when (step) {
            is ExtractorStep.Fetch -> {
                var target = state.template(step.url, config.mainUrl)
                step.urlReplace.forEach { (from, to) -> target = target.replace(from, to) }
                val response = http.get(
                    target,
                    headers = state.headers(step.headers, config.mainUrl),
                    referer = state.referer(step.referer, config.mainUrl)
                )
                state.vars[step.store] = response.text
                if (step.storeFinalUrl.isNotBlank()) state.vars[step.storeFinalUrl] = response.url
            }
            is ExtractorStep.PostForm -> {
                val body = step.data.entries.joinToString("&") {
                    URLEncoder.encode(it.key, "UTF-8") + "=" +
                        URLEncoder.encode(state.template(it.value, config.mainUrl), "UTF-8")
                }
                state.vars[step.store] = http.post(
                    state.template(step.url, config.mainUrl),
                    body,
                    headers = state.headers(step.headers, config.mainUrl) +
                        ("Content-Type" to "application/x-www-form-urlencoded"),
                    referer = state.referer(step.referer, config.mainUrl)
                ).text
            }
            is ExtractorStep.PostJson -> {
                state.vars[step.store] = http.post(
                    state.template(step.url, config.mainUrl),
                    state.template(step.jsonBody, config.mainUrl),
                    headers = state.headers(step.headers, config.mainUrl) +
                        ("Content-Type" to "application/json"),
                    referer = state.referer(step.referer, config.mainUrl)
                ).text
            }
            is ExtractorStep.Regex -> {
                val source = state.vars[step.source] ?: step.source
                val match = Regex(
                    step.pattern,
                    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
                ).find(source)
                val value = match?.groupValues?.getOrNull(step.group)
                    ?.let { if (step.decodeUnicode) MasterLinkGenerator.decodeUnicodeEscapes(it) else it }
                    ?.trim()
                    .orEmpty()
                if (value.isNotBlank()) {
                    state.vars[step.store.ifBlank { "value" }] = value
                    if (step.store.isBlank() &&
                        (step.filter.isBlank() || Regex(step.filter, RegexOption.IGNORE_CASE).containsMatchIn(value))) {
                        state.urls.add(value)
                    }
                }
            }
            is ExtractorStep.JsonPath -> {
                val source = state.vars[step.source] ?: step.source
                val values = jsonPathValues(source, step.path)
                val filtered = values.filter {
                    step.filter.isBlank() || Regex(step.filter, RegexOption.IGNORE_CASE).containsMatchIn(it)
                }
                if (filtered.isNotEmpty()) {
                    val joined = filtered.joinToString("\n")
                    state.vars[step.store.ifBlank { "value" }] = joined
                    if (step.store.isBlank()) state.urls.addAll(filtered)
                }
            }
            is ExtractorStep.ConstructUrl -> {
                val value = state.template(step.template, config.mainUrl)
                if (value.isNotBlank()) {
                    state.vars[step.store.ifBlank { "value" }] = value
                    if (step.store.isBlank()) state.urls.add(value)
                }
            }
            is ExtractorStep.Substring -> {
                val source = state.vars[step.source] ?: step.source
                val value = source.substringAfter(step.startMarker, "")
                    .substringBefore(step.endMarker, "")
                    .trim()
                if (value.isNotBlank()) {
                    state.vars[step.store.ifBlank { "value" }] = value
                    if (step.store.isBlank()) state.urls.add(value)
                }
            }
            is ExtractorStep.ResolveUrl -> {
                val base = state.template(step.base, config.mainUrl)
                val source = state.template(
                    step.source.ifBlank { state.vars["value"].orEmpty() },
                    config.mainUrl
                )
                if (source.isNotBlank()) {
                    val resolved = runCatching { URI(base).resolve(source).toString() }.getOrDefault(source)
                    state.vars["value"] = resolved
                    state.urls.add(resolved)
                }
            }
            is ExtractorStep.PackedJs -> {
                val source = state.vars[step.source] ?: step.source
                val packed = findPackedJsInPage(source)
                if (packed != null) {
                    val decoded = decodePackedJs(packed.first, packed.second, packed.third)
                    state.vars[step.store] = decoded
                    if (step.store.isBlank()) {
                        Regex("""(?i)(https?://[^"']+(?:m3u8|mp4|mpd)[^"']*)""")
                            .findAll(decoded)
                            .forEach { state.urls.add(it.value) }
                    }
                }
            }
            is ExtractorStep.Delegate -> {
                val target = state.template(step.url, config.mainUrl)
                registry.resolve(
                    url = target,
                    referer = state.referer(step.queryParam, config.mainUrl),
                    subtitleCallback = { subtitles += it },
                    callback = { stream ->
                        streams += stream
                        state.urls.add(stream.url)
                    },
                    callChain = chain + id
                )
            }
            is ExtractorStep.Iframe -> {
                val source = state.vars[step.source] ?: step.source
                val doc = Jsoup.parse(source, state.url)
                doc.select(step.selector).forEach { node ->
                    val value = node.attr(step.attribute).trim()
                    if (value.isBlank()) return@forEach
                    if (step.exclude.isNotBlank() &&
                        Regex(step.exclude, RegexOption.IGNORE_CASE).containsMatchIn(value)) return@forEach
                    if (step.include.isNotBlank() &&
                        !Regex(step.include, RegexOption.IGNORE_CASE).containsMatchIn(value)) return@forEach
                    val base = state.template(step.base, config.mainUrl)
                    val resolved = runCatching { URI(base).resolve(value).toString() }.getOrDefault(value)
                    state.vars["iframe"] = resolved
                    state.urls.add(resolved)
                }
            }
            is ExtractorStep.Redirect -> {
                val source = state.vars[step.source] ?: step.source
                val value = if (step.url == "{url}") source else state.template(step.url, config.mainUrl)
                if (value.isNotBlank()) state.urls.add(value)
            }
            is ExtractorStep.Webview -> {
                // Browser navigation/subresources do not inherit StreamixHttpContext.
                // Fail closed for Catalog-scoped calls rather than bypass its URL/DNS policy.
                if (catalogScoped) {
                    error("browser-assisted resolution is unavailable for Catalog-scoped requests")
                }
                val resolver = webResolver()
                    ?: error("StreamixWebResolver is not installed")
                val interceptRegex = runCatching {
                    Regex(step.interceptPattern, RegexOption.IGNORE_CASE)
                }.getOrNull()
                val result = resolver.resolve(
                    streamix.core.StreamixWebRequest(
                        url = state.template(step.url, config.mainUrl),
                        referer = state.referer(step.referer, config.mainUrl),
                        headers = state.headers(step.headers, config.mainUrl)
                    )
                ) { webRequest ->
                    interceptRegex?.containsMatchIn(webRequest.url) == true
                }
                result.intercepted
                    ?.takeIf { interceptRegex?.containsMatchIn(it.url) == true }
                    ?.let { state.urls.add(it.url) }
                result.additionalRequests
                    .filter { interceptRegex?.containsMatchIn(it.url) == true }
                    .forEach { state.urls.add(it.url) }
            }
            is ExtractorStep.AesGcm -> {
                val source = state.vars[step.source] ?: step.source
                val root = JsonParser.parseString(source)
                val keyParts = jsonPathValues(root, step.keyPartsPath)
                val iv = jsonPathValues(root, step.ivPath).firstOrNull()
                    ?: error("AesGcm IV missing")
                val payload = jsonPathValues(root, step.payloadPath).firstOrNull()
                    ?: error("AesGcm payload missing")
                require(keyParts.size >= 2) { "AesGcm requires at least two key parts" }

                val key = keyParts.take(2).fold(ByteArray(0)) { acc, part ->
                    acc + base64UrlDecode(part)
                }
                val plaintext = StreamixRuntime.requireCrypto().aesGcmDecrypt(
                    ciphertext = base64UrlDecode(payload),
                    key = key,
                    iv = base64UrlDecode(iv)
                ).toString(StandardCharsets.UTF_8)
                    .removePrefix("\uFEFF")

                state.vars[step.store] = plaintext
            }
            is ExtractorStep.RhinoEval -> {
                val source = state.vars[step.source] ?: step.source
                val script = source + "\nJSON.stringify(" + step.objectName + ")"
                state.vars[step.store] = StreamixRuntime.js
                    ?.evaluate(script)
                    ?.takeIf { it.isNotBlank() }
                    ?: error("RhinoEval produced no result")
            }
            is ExtractorStep.XorSig -> {
                val source = state.vars[step.source] ?: step.source
                val transformed = decodeVidguardSignature(source)
                state.vars[step.store.ifBlank { "value" }] = transformed
                state.urls.add(transformed)
            }
        }
    }

    private fun jsonPath(source: String, path: String): String =
        jsonPathValues(source, path).firstOrNull().orEmpty()

    private fun jsonPathValues(source: String, path: String): List<String> =
        runCatching { jsonPathValues(JsonParser.parseString(source), path) }.getOrDefault(emptyList())

    private fun jsonPathValues(root: JsonElement, path: String): List<String> {
        var nodes = listOf(root)
        path.trim()
            .trimStart('.')
            .split('.')
            .filter { it.isNotBlank() }
            .forEach { token ->
                val match = Regex("""^([^\\[]+)(?:\\[(\\d*)\\])?$""").matchEntire(token)
                    ?: return emptyList()
                val key = match.groupValues[1]
                val index = match.groupValues.getOrNull(2).orEmpty()
                nodes = nodes.flatMap { node ->
                    if (!node.isJsonObject || !node.asJsonObject.has(key)) return@flatMap emptyList()
                    val child = node.asJsonObject.get(key)
                    if (index.isBlank() && token.contains("[")) {
                        if (!child.isJsonArray) emptyList()
                        else child.asJsonArray.map { it }
                    } else if (index.isNotBlank()) {
                        child.takeIf { it.isJsonArray && index.toInt() < it.asJsonArray.size() }
                            ?.let { listOf(it.asJsonArray[index.toInt()]) }
                            ?: emptyList()
                    } else {
                        listOf(child)
                    }
                }
            }
        return nodes.mapNotNull {
            when {
                it.isJsonNull -> null
                it.isJsonPrimitive -> it.asString
                else -> it.toString()
            }
        }.filter { it.isNotBlank() }
    }

    private fun base64UrlDecode(value: String): ByteArray {
        val fixed = value.replace('-', '+').replace('_', '/')
        val padded = fixed + "=".repeat((4 - fixed.length % 4) % 4)
        return Base64.getDecoder().decode(padded)
    }

    private fun decodeVidguardSignature(url: String): String {
        val sig = url.substringAfter("sig=", "").substringBefore("&")
        if (sig.isBlank()) return url
        val decoded = sig.chunked(2)
            .joinToString("") { (it.toInt(16) xor 2).toChar().toString() }
            .let { raw ->
                val padding = when (raw.length % 4) {
                    2 -> "=="
                    3 -> "="
                    else -> ""
                }
                String(Base64.getDecoder().decode((raw + padding).toByteArray(StandardCharsets.UTF_8)))
            }
            .dropLast(5)
            .reversed()
            .toCharArray()
            .also { chars ->
                for (i in chars.indices step 2) {
                    if (i + 1 < chars.size) {
                        val next = chars[i + 1]
                        chars[i + 1] = chars[i]
                        chars[i] = next
                    }
                }
            }
            .concatToString()
            .dropLast(5)
        return url.replace(sig, decoded)
    }

    private suspend fun deliver(
        state: State,
        request: streamix.core.StreamixExtractorRequest,
        streams: MutableList<StreamixStream>
    ) {
        state.urls.forEach { url ->
            MasterLinkGenerator.createSmartStreamLink(
                source = config.name,
                url = url,
                referer = config.videoReferer.takeIf { it.isNotBlank() }?.let { state.template(it, config.mainUrl) }
                    ?: request.referer,
                headers = state.headers(emptyMap(), config.mainUrl),
                bareHeaders = false,
                callback = { streams.add(it) }
            )
        }
    }
}
