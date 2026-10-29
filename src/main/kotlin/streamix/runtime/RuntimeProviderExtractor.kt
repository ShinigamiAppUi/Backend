package streamix.runtime

import java.net.URI
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import com.baseprovider.streamix.StreamixRuntime
import org.json.JSONObject
import org.jsoup.nodes.Element
import streamix.core.StreamixHttp

data class RuntimeProviderCandidate(
    val url: String,
    val referer: String? = null
)

interface RuntimeProviderExtractor {
    val providerId: String
    suspend fun extract(pageUrl: String): List<RuntimeProviderCandidate>
}

class RuntimeProviderExtractorRegistry(
    http: StreamixHttp,
    extractors: List<RuntimeProviderExtractor>? = null
) {
    private val extractors: List<RuntimeProviderExtractor> = extractors ?: listOf(
        AnimasuRuntimeExtractor(http),
        AnisailRuntimeExtractor(http),
        AnoboyRuntimeExtractor(http),
        OtakudesuRuntimeExtractor(http),
        SamehadakuRuntimeExtractor(http),
        OploverzRuntimeExtractor(http),
        AnimeIndoRuntimeExtractor(http),
        GomunimeRuntimeExtractor(http),
        SokujaRuntimeExtractor(http),
        NimegamiRuntimeExtractor(http),
        AlqanimeRuntimeExtractor(http)
    )

    fun get(providerId: String): RuntimeProviderExtractor? =
        extractors.firstOrNull { it.providerId == providerId }

    fun all(): List<RuntimeProviderExtractor> = extractors
}

private suspend fun <T> safeProviderCall(block: suspend () -> T): T? =
    try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        null
    }

abstract class BaseRuntimeProviderExtractor(
    protected val http: StreamixHttp
) : RuntimeProviderExtractor {
    protected val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en;q=0.8"
    )

    protected suspend fun page(url: String): streamix.core.StreamixHttpResponse? =
        safeProviderCall { http.get(url, headers = headers, referer = url, timeoutMs = 15_000L) }
            ?.takeIf { it.code in 200..399 }

    protected fun frameCandidates(
        pageUrl: String,
        doc: org.jsoup.nodes.Document
    ): List<RuntimeProviderCandidate> =
        doc.select("iframe[src],iframe[data-src],video[src],source[src],embed[src]")
            .mapNotNull { element ->
                absolute(
                    element.attr("src").ifBlank { element.attr("data-src") },
                    pageUrl
                )
            }
            .map { RuntimeProviderCandidate(it, pageUrl) }

    protected fun anchorCandidates(
        pageUrl: String,
        doc: org.jsoup.nodes.Document,
        predicate: (Element) -> Boolean
    ): List<RuntimeProviderCandidate> =
        doc.select("a[href]").filter(predicate).mapNotNull { element ->
            absolute(element.attr("href"), pageUrl)?.let { RuntimeProviderCandidate(it, pageUrl) }
        }

    /**
     * Collect known external server families exposed by a provider.
     *
     * Provider extraction is intentionally broader than a single host:
     * the provider owns discovery, while the global registry owns resolution.
     */
    protected enum class HostSurface { ANCHOR, IFRAME, MEDIA, SCRIPT }

    protected fun providerHostCandidates(
        pageUrl: String,
        doc: org.jsoup.nodes.Document,
        hostRules: Map<String, Set<HostSurface>>
    ): List<RuntimeProviderCandidate> {
        val result = linkedSetOf<RuntimeProviderCandidate>()
        fun add(raw: String, surface: HostSurface) {
            val url = absolute(raw, pageUrl) ?: return
            if (sameProviderHost(url, pageUrl)) return
            val host = runCatching { URI(url).host?.lowercase().orEmpty() }.getOrDefault("")
            if (hostRules.any { (token, surfaces) -> host.contains(token.lowercase()) && surface in surfaces }) {
                result += RuntimeProviderCandidate(url, pageUrl)
            }
        }
        doc.select("a[href]").forEach { add(it.attr("href"), HostSurface.ANCHOR) }
        doc.select("iframe[src],iframe[data-src]").forEach {
            add(it.attr("src").ifBlank { it.attr("data-src") }, HostSurface.IFRAME)
        }
        doc.select("video[src],source[src],embed[src]").forEach { add(it.attr("src"), HostSurface.MEDIA) }
        val urlRegex = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
        doc.select("script").forEach { script ->
            urlRegex.findAll(script.data() + "\n" + script.html()).forEach { match ->
                add(match.value.trimEnd(')', ',', ';', '\\', '"', '\''), HostSurface.SCRIPT)
            }
        }
        return result.toList()
    }

    protected fun hostRules(vararg entries: Pair<String, Set<HostSurface>>): Map<String, Set<HostSurface>> =
        linkedMapOf(*entries)

    protected fun scriptCandidates(pageUrl: String, doc: org.jsoup.nodes.Document): List<RuntimeProviderCandidate> {
        val result = linkedSetOf<RuntimeProviderCandidate>()
        val urlRegex = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
        doc.select("script").forEach { script ->
            urlRegex.findAll(script.data() + "\n" + script.html()).forEach { match ->
                val raw = match.value.trimEnd(')', ',', ';', '\\', '"', '\'')
                if (raw.contains("m3u8", true) || raw.contains("mp4", true) ||
                    raw.contains("embed", true) || raw.contains("player", true) ||
                    raw.contains("video", true) || raw.contains("stream", true)
                ) {
                    absolute(raw, pageUrl)
                        ?.takeUnless { sameProviderHost(it, pageUrl) }
                        ?.let { result += RuntimeProviderCandidate(it, pageUrl) }
                }
            }
        }
        return result.toList()
    }

    protected fun absolute(value: String, base: String): String? =
        value.trim().takeIf { it.isNotBlank() }?.let {
            runCatching { URI(base).resolve(it).toString() }.getOrNull()
        }

    protected fun decodeBase64(value: String): String? =
        runCatching {
            val normalized = value.trim().replace("\n", "").replace("\r", "")
            Base64.getDecoder().decode(normalized).toString(Charsets.UTF_8)
        }.getOrNull()

    protected fun sameProviderHost(candidateUrl: String, pageUrl: String): Boolean {
        val candidateHost = runCatching { URI(candidateUrl).host?.lowercase()?.removePrefix("www.") }.getOrNull()
        val pageHost = runCatching { URI(pageUrl).host?.lowercase()?.removePrefix("www.") }.getOrNull()
        return !candidateHost.isNullOrBlank() && candidateHost == pageHost
    }
}

class AnimasuRuntimeExtractor(http: StreamixHttp) : BaseRuntimeProviderExtractor(http) {
    override val providerId = "animasu"

    private val knownHosts = hostRules(
        "blogger.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME, HostSurface.SCRIPT),
        "googleusercontent.com" to setOf(HostSurface.IFRAME, HostSurface.SCRIPT),
        "googlevideo.com" to setOf(HostSurface.IFRAME, HostSurface.SCRIPT),
        "yourupload.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "filedon.co" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "abyss" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "archivd.net" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "uservideo.xyz" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "vidhidepro.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "vectorx.top" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "mega.nz" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
    )

    override suspend fun extract(pageUrl: String): List<RuntimeProviderCandidate> {
        val response = page(pageUrl) ?: return emptyList()
        val result = linkedSetOf<RuntimeProviderCandidate>()

        // Animasu's authoritative player boundary is the mirror selector.
        // Do not rely on page-wide iframe scanning first: the actual player is
        // commonly nested inside the option payload and the page itself also
        // exposes a self-hosted #video surface which is not a playable host.
        response.document.select("select.mirror option[value], select.mirror option[data-content], option[data-content]")
            .forEach { option ->
                val rawValues = listOf(
                    option.attr("value").trim(),
                    option.attr("data-content").trim()
                ).filter { it.isNotBlank() }.distinct()

                rawValues.forEach { raw ->
                    decodeBase64(raw)?.let { decoded ->
                        val mirror = org.jsoup.Jsoup.parse(decoded, pageUrl)
                        mirror.select("iframe[src],iframe[data-src],video[src],source[src],embed[src]")
                            .forEach { frame ->
                                absolute(frame.attr("src").ifBlank { frame.attr("data-src") }, pageUrl)
                                    ?.takeUnless { sameProviderHost(it, pageUrl) }
                                    ?.let { result += RuntimeProviderCandidate(it, pageUrl) }
                            }
                    }

                    // Some mirror implementations put the player URL directly
                    // in value. Only external player/media URLs cross the global
                    // resolver boundary; never emit Animasu's own #video page.
                    absolute(raw, pageUrl)
                        ?.takeUnless { sameProviderHost(it, pageUrl) }
                        ?.let { result += RuntimeProviderCandidate(it, pageUrl) }
                }
            }


        val frameCandidates = frameCandidates(pageUrl, response.document)
        result += frameCandidates.filterNot { sameProviderHost(it.url, pageUrl) }

        // Anoboy sometimes places the actual player behind one same-host
        // iframe/endpoint before exposing the external mirror. Cross that
        // provider-owned boundary once, then only hand external hosts to the
        // global resolver.
        frameCandidates.filter { sameProviderHost(it.url, pageUrl) }.take(4).forEach { player ->
            page(player.url)?.let { playerResponse ->
                result += frameCandidates(player.url, playerResponse.document)
                    .filterNot { sameProviderHost(it.url, pageUrl) }
                result += providerHostCandidates(player.url, playerResponse.document, knownHosts)
            }
        }

        result += providerHostCandidates(pageUrl, response.document, knownHosts)
        result += anchorCandidates(pageUrl, response.document) {
            val href = it.attr("href")
            href.contains("blogger.com/video", true) || href.contains("yourupload", true) ||
                href.contains("filedon", true) || href.contains("abyss", true) ||
                href.contains("archivd", true) || href.contains("uservideo", true) ||
                href.contains("vidhide", true) || href.contains("vectorx", true) ||
                href.contains("mega", true) || href.contains("player", true) ||
                href.contains("embed", true) || href.contains("stream", true)
        }
        result += scriptCandidates(pageUrl, response.document)
        return result.toList()
    }
}

class AnisailRuntimeExtractor(http: StreamixHttp) : BaseRuntimeProviderExtractor(http) {
    override val providerId = "anisail"

    private val knownHosts = hostRules(
        "framezilla" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "animesail.xyz" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "mp4upload.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "miiiixdrop" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "uservideo.xyz" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "aghanim.xyz" to setOf(HostSurface.ANCHOR, HostSurface.SCRIPT),
        "rasa-cintaku-semakin-berantai.xyz" to setOf(HostSurface.ANCHOR, HostSurface.SCRIPT),
        "filemoon" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "vidhide" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "streamwish" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "yourupload" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "blogger.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME, HostSurface.SCRIPT),
    )

    override suspend fun extract(pageUrl: String): List<RuntimeProviderCandidate> {
        val response = safeProviderCall {
            http.get(
                pageUrl,
                headers = headers,
                referer = pageUrl,
                cookies = mapOf(
                    "_as_ipin_ct" to "ID",
                    "_as_ipin_tz" to "Asia/Jakarta",
                    "_as_ipin_lc" to "id-ID"
                ),
                timeoutMs = 15_000L
            )
        }?.takeIf { it.code in 200..399 } ?: return emptyList()

        val result = linkedSetOf<RuntimeProviderCandidate>()
        result += frameCandidates(pageUrl, response.document)
        result += providerHostCandidates(pageUrl, response.document, knownHosts)

        response.document.select(
            ".mobius .mirror option[value], .mirror option[value], option[data-em], option[data-src]"
        ).forEach { option ->
            val raw = option.attr("value")
                .ifBlank { option.attr("data-em") }
                .ifBlank { option.attr("data-src") }
                .ifBlank { option.attr("data-content") }
                .trim()

            decodeBase64(raw)?.let { decoded ->
                org.jsoup.Jsoup.parse(decoded, pageUrl)
                    .select("iframe[src],iframe[data-src],video[src],source[src],embed[src]")
                    .forEach { frame ->
                        absolute(frame.attr("src").ifBlank { frame.attr("data-src") }, pageUrl)
                            ?.let { result += RuntimeProviderCandidate(it, pageUrl) }
                    }
            }

            absolute(raw, pageUrl)?.let { result += RuntimeProviderCandidate(it, pageUrl) }
        }

        result += anchorCandidates(pageUrl, response.document) {
            val href = it.attr("href")
            it.text().contains("stream", true) ||
                it.text().contains("player", true) ||
                href.contains("framezilla", true) ||
                href.contains("mp4upload", true) ||
                href.contains("miiiixdrop", true) ||
                href.contains("uservideo", true) ||
                href.contains("aghanim", true) ||
                href.contains("redirect", true)
        }
        result += scriptCandidates(pageUrl, response.document)
        return result.toList()
    }
}

class AnoboyRuntimeExtractor(http: StreamixHttp) : BaseRuntimeProviderExtractor(http) {
    override val providerId = "anoboy"

    private val knownHosts = hostRules(
        "blogger.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME, HostSurface.SCRIPT),
        "googleusercontent.com" to setOf(HostSurface.IFRAME, HostSurface.SCRIPT),
        "googlevideo.com" to setOf(HostSurface.IFRAME, HostSurface.SCRIPT),
        "yourupload.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "filedon.co" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "mp4upload.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "abyss" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "gofile.io" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "gdrive" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "mega.nz" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "b-tube" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "btube" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
    )

    override suspend fun extract(pageUrl: String): List<RuntimeProviderCandidate> {
        val response = page(pageUrl) ?: return emptyList()
        val result = linkedSetOf<RuntimeProviderCandidate>()

        response.document.select("select option[value]").forEach { option ->
            val value = option.attr("value").trim()
            absolute(value, pageUrl)?.takeUnless { sameProviderHost(it, pageUrl) }
                ?.let { result += RuntimeProviderCandidate(it, pageUrl) }

            decodeBase64(value)?.let { decoded ->
                org.jsoup.Jsoup.parse(decoded, pageUrl)
                    .select("iframe[src],iframe[data-src],video[src],source[src],embed[src]")
                    .forEach { frame ->
                        absolute(frame.attr("src").ifBlank { frame.attr("data-src") }, pageUrl)
                            ?.let { result += RuntimeProviderCandidate(it, pageUrl) }
                    }
            }
        }

        val frameCandidates = frameCandidates(pageUrl, response.document)
        result += frameCandidates.filterNot { sameProviderHost(it.url, pageUrl) }

        // Anoboy can keep the server/player endpoint on its own host. Cross that
        // provider-owned boundary once, then expose only external mirrors to the
        // global resolver.
        frameCandidates.filter { sameProviderHost(it.url, pageUrl) }.take(6).forEach { player ->
            page(player.url)?.let { playerResponse ->
                result += frameCandidates(player.url, playerResponse.document)
                    .filterNot { sameProviderHost(it.url, pageUrl) }
                result += providerHostCandidates(player.url, playerResponse.document, knownHosts)
                result += anchorCandidates(player.url, playerResponse.document) {
                    val href = it.attr("href")
                    val text = it.text()
                    text.contains("B-Tube", true) || text.contains("Gofile", true) ||
                        text.contains("MP4Upload", true) || href.contains("yourupload", true) ||
                        href.contains("filedon", true) || href.contains("abyss", true) ||
                        href.contains("gdrive", true) || href.contains("mega", true) ||
                        href.contains("player", true) || href.contains("embed", true) ||
                        href.contains("stream", true)
                }
                playerResponse.document.select(
                    "[data-src],[data-url],[data-link],[data-content],[data-embed],button[onclick],a[onclick]"
                ).forEach { element ->
                    val values = listOf(
                        element.attr("data-src"), element.attr("data-url"),
                        element.attr("data-link"), element.attr("data-content"),
                        element.attr("data-embed"),
                        Regex("""https?://[^"'\s)]+""").find(element.attr("onclick"))?.value.orEmpty()
                    )
                    values.filter { it.isNotBlank() }.forEach { raw ->
                        absolute(raw, player.url)
                            ?.takeUnless { sameProviderHost(it, pageUrl) }
                            ?.let { result += RuntimeProviderCandidate(it, player.url) }
                    }
                }
                result += scriptCandidates(player.url, playerResponse.document)
            }
        }

        // Older Anoboy posts link to a separate same-host player page
        // (for example /boruto-streaming/) instead of embedding the external
        // host on the episode page. Follow only player/watch/stream links.
        anchorCandidates(pageUrl, response.document) { element ->
            val href = element.attr("href")
            sameProviderHost(absolute(href, pageUrl).orEmpty(), pageUrl) &&
                listOf("stream", "player", "watch", "embed").any { token ->
                    href.contains(token, true) || element.text().contains(token, true)
                }
        }.take(6).forEach { player ->
            page(player.url)?.let { playerResponse ->
                result += frameCandidates(player.url, playerResponse.document)
                    .filterNot { sameProviderHost(it.url, pageUrl) }
                result += providerHostCandidates(player.url, playerResponse.document, knownHosts)
                result += anchorCandidates(player.url, playerResponse.document) { element ->
                    val href = element.attr("href")
                    listOf("yourupload", "filedon", "abyss", "gdrive", "mega", "gofile",
                        "mp4upload", "btube", "player", "embed", "stream").any { href.contains(it, true) }
                }
                result += scriptCandidates(player.url, playerResponse.document)
                playerResponse.document.select(
                    "[data-src],[data-url],[data-link],[data-content],[data-embed],button[onclick],a[onclick]"
                ).forEach { element ->
                    val values = listOf(
                        element.attr("data-src"), element.attr("data-url"), element.attr("data-link"),
                        element.attr("data-content"), element.attr("data-embed"),
                        Regex("""https?://[^"'\s)]+""").find(element.attr("onclick"))?.value.orEmpty()
                    )
                    values.filter { it.isNotBlank() }.forEach { raw ->
                        absolute(raw, player.url)?.takeUnless { sameProviderHost(it, pageUrl) }
                            ?.let { result += RuntimeProviderCandidate(it, player.url) }
                        decodeBase64(raw)?.let { decoded ->
                            val embedded = org.jsoup.Jsoup.parse(decoded, player.url)
                            result += frameCandidates(player.url, embedded)
                                .filterNot { sameProviderHost(it.url, pageUrl) }
                            result += providerHostCandidates(player.url, embedded, knownHosts)
                        }
                    }
                }
            }
        }

        // Some older Anoboy pages chain the episode post to a same-host
        // streaming index, which then links to a second same-host player page.
        // Follow that provider-owned hop once (bounded) before handing external
        // player hosts to the global resolver.
        anchorCandidates(pageUrl, response.document) { element ->
            val href = element.attr("href")
            sameProviderHost(absolute(href, pageUrl).orEmpty(), pageUrl) &&
                href.contains("stream", true)
        }.take(4).forEach { indexPage ->
            val indexResponse = page(indexPage.url) ?: return@forEach
            anchorCandidates(indexPage.url, indexResponse.document) { element ->
                val href = element.attr("href")
                val text = element.text()
                listOf("stream", "player", "watch", "embed").any {
                    href.contains(it, true) || text.contains(it, true)
                }
            }.filterNot { sameProviderHost(it.url, pageUrl) }.forEach { candidate ->
                result += candidate
            }
            result += frameCandidates(indexPage.url, indexResponse.document)
                .filterNot { sameProviderHost(it.url, pageUrl) }
            result += providerHostCandidates(indexPage.url, indexResponse.document, knownHosts)
            result += scriptCandidates(indexPage.url, indexResponse.document)

            // The streaming index may itself point to a same-host player route.
            // Follow that second provider-owned hop; only external URLs leave
            // this extractor and enter the global resolver.
            anchorCandidates(indexPage.url, indexResponse.document) { element ->
                val href = element.attr("href")
                sameProviderHost(absolute(href, indexPage.url).orEmpty(), pageUrl) &&
                    listOf("stream", "player", "watch", "embed").any { token ->
                        href.contains(token, true) || element.text().contains(token, true)
                    }
            }.take(4).forEach { nestedPlayer ->
                val nestedResponse = page(nestedPlayer.url) ?: return@forEach
                result += frameCandidates(nestedPlayer.url, nestedResponse.document)
                    .filterNot { sameProviderHost(it.url, pageUrl) }
                result += providerHostCandidates(nestedPlayer.url, nestedResponse.document, knownHosts)
                result += scriptCandidates(nestedPlayer.url, nestedResponse.document)
                anchorCandidates(nestedPlayer.url, nestedResponse.document) { element ->
                    val href = element.attr("href")
                    val text = element.text()
                    listOf(
                        "yourupload", "filedon", "abyss", "gdrive", "mega", "gofile",
                        "mp4upload", "btube", "vidhide", "streamwish", "filemoon",
                        "player", "embed", "stream"
                    ).any { token -> href.contains(token, true) || text.contains(token, true) }
                }.filterNot { sameProviderHost(it.url, pageUrl) }.forEach { result += it }
                nestedResponse.document.select(
                    "[data-src],[data-url],[data-link],[data-content],[data-embed],button[onclick],a[onclick]"
                ).forEach { element ->
                    val values = listOf(
                        element.attr("data-src"), element.attr("data-url"), element.attr("data-link"),
                        element.attr("data-content"), element.attr("data-embed"),
                        Regex("""https?://[^"'\s)]+""").find(element.attr("onclick"))?.value.orEmpty()
                    )
                    values.filter { it.isNotBlank() }.forEach { raw ->
                        absolute(raw, nestedPlayer.url)
                            ?.takeUnless { sameProviderHost(it, pageUrl) }
                            ?.let { result += RuntimeProviderCandidate(it, nestedPlayer.url) }
                        decodeBase64(raw)?.let { decoded ->
                            val embedded = org.jsoup.Jsoup.parse(decoded, nestedPlayer.url)
                            result += frameCandidates(nestedPlayer.url, embedded)
                                .filterNot { sameProviderHost(it.url, pageUrl) }
                            result += providerHostCandidates(nestedPlayer.url, embedded, knownHosts)
                        }
                    }
                }
            }

            indexResponse.document.select(
                "[data-src],[data-url],[data-link],[data-content],[data-embed],button[onclick],a[onclick]"
            ).forEach { element ->
                val values = listOf(
                    element.attr("data-src"), element.attr("data-url"), element.attr("data-link"),
                    element.attr("data-content"), element.attr("data-embed"),
                    Regex("""https?://[^"'\s)]+""").find(element.attr("onclick"))?.value.orEmpty()
                )
                values.filter { it.isNotBlank() }.forEach { raw ->
                    absolute(raw, indexPage.url)
                        ?.takeUnless { sameProviderHost(it, pageUrl) }
                        ?.let { result += RuntimeProviderCandidate(it, indexPage.url) }
                    decodeBase64(raw)?.let { decoded ->
                        val embedded = org.jsoup.Jsoup.parse(decoded, indexPage.url)
                        result += frameCandidates(indexPage.url, embedded)
                            .filterNot { sameProviderHost(it.url, pageUrl) }
                        result += providerHostCandidates(indexPage.url, embedded, knownHosts)
                    }
                }
            }
        }

        result += providerHostCandidates(pageUrl, response.document, knownHosts)
        result += anchorCandidates(pageUrl, response.document) {
            val href = it.attr("href")
            val text = it.text()
            text.contains("B-Tube", true) || text.contains("Gofile", true) ||
                text.contains("MP4Upload", true) || href.contains("yourupload", true) ||
                href.contains("filedon", true) || href.contains("abyss", true) ||
                href.contains("gdrive", true) || href.contains("mega", true) ||
                href.contains("player", true) || href.contains("embed", true) ||
                href.contains("stream", true)
        }

        // anoBoy renders server buttons with the actual player URL in data/*
        // attributes or onclick handlers instead of a normal href.
        response.document.select(
            "[data-src],[data-url],[data-link],[data-content],[data-embed],button[onclick],a[onclick]"
        ).forEach { element ->
            val values = listOf(
                element.attr("data-src"),
                element.attr("data-url"),
                element.attr("data-link"),
                element.attr("data-content"),
                element.attr("data-embed"),
                Regex("""https?://[^"'\s)]+""").find(element.attr("onclick"))?.value.orEmpty()
            )
            values.filter { it.isNotBlank() }.forEach { raw ->
                absolute(raw, pageUrl)
                    ?.takeUnless { sameProviderHost(it, pageUrl) }
                    ?.let { result += RuntimeProviderCandidate(it, pageUrl) }
            }
        }

        result += scriptCandidates(pageUrl, response.document)
        return result.toList()
    }
}

class OtakudesuRuntimeExtractor(http: StreamixHttp) : BaseRuntimeProviderExtractor(http) {
    override val providerId = "otakudesu"

    private val knownHosts = hostRules(
        "desustream.me" to setOf(HostSurface.IFRAME, HostSurface.ANCHOR),
        "desustream.net" to setOf(HostSurface.IFRAME, HostSurface.ANCHOR),
        "desustream.info" to setOf(HostSurface.IFRAME, HostSurface.ANCHOR),
        "str.desustream.com" to setOf(HostSurface.IFRAME, HostSurface.ANCHOR),
        "desustream.com" to setOf(HostSurface.IFRAME, HostSurface.ANCHOR),
        "odvidhide.com" to setOf(HostSurface.IFRAME, HostSurface.ANCHOR),
        "vidhide" to setOf(HostSurface.IFRAME, HostSurface.ANCHOR),
        "yourupload.com" to setOf(HostSurface.IFRAME, HostSurface.ANCHOR),
        "streamwish" to setOf(HostSurface.IFRAME, HostSurface.ANCHOR),
        "filemoon" to setOf(HostSurface.IFRAME, HostSurface.ANCHOR),
        "filedon.co" to setOf(HostSurface.IFRAME, HostSurface.ANCHOR),
        "gofile.io" to setOf(HostSurface.IFRAME, HostSurface.ANCHOR),
    )

    override suspend fun extract(pageUrl: String): List<RuntimeProviderCandidate> {
        val response = page(pageUrl) ?: return emptyList()
        val result = linkedSetOf<RuntimeProviderCandidate>()

        result += frameCandidates(pageUrl, response.document)
        result += providerHostCandidates(pageUrl, response.document, knownHosts)
        result += scriptCandidates(pageUrl, response.document)

        val script = response.document.select("script").firstOrNull { it.data().contains("action:") }?.data().orEmpty()
        val actions = Regex("""action\s*:\s*["']([^"']+)["']""").findAll(script).map { it.groupValues[1] }.toList()
        val nonceAction = actions.firstOrNull()
        val action = actions.lastOrNull()
        val base = runCatching { URI(pageUrl).let { x -> x.scheme + "://" + x.authority } }.getOrNull()

        if (base != null && !nonceAction.isNullOrBlank() && !action.isNullOrBlank()) {
            val nonce = safeProviderCall {
                http.post("$base/wp-admin/admin-ajax.php", "action=$nonceAction",
                    headers + mapOf("X-Requested-With" to "XMLHttpRequest", "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"),
                    pageUrl).text
            }?.let { payload -> runCatching { JSONObject(payload).optString("data") }.getOrNull() }

            if (!nonce.isNullOrBlank()) {
                response.document.select("div.mirrorstream ul li > a[data-content]").forEach { mirror ->
                    val decoded = decodeBase64(mirror.attr("data-content")) ?: return@forEach
                    val values = decoded.removePrefix("[").removeSuffix("]")
                        .split(",").map { it.substringAfter(":").trim().trim('"') }
                    if (values.size < 3) return@forEach

                    val body = listOf(
                        "id" to values[0], "i" to values[1], "q" to values[2],
                        "nonce" to nonce, "action" to action
                    ).joinToString("&") { (k, v) ->
                        java.net.URLEncoder.encode(k, "UTF-8") + "=" +
                            java.net.URLEncoder.encode(v, "UTF-8")
                    }

                    safeProviderCall {
                        http.post(
                            "$base/wp-admin/admin-ajax.php",
                            body,
                            headers + mapOf(
                                "X-Requested-With" to "XMLHttpRequest",
                                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
                            ),
                            pageUrl
                        ).text
                    }?.let { payload ->
                        decodeBase64(runCatching { JSONObject(payload).optString("data") }.getOrDefault(""))
                            ?.let { html ->
                                org.jsoup.Jsoup.parse(html, pageUrl)
                                    .select("iframe[src],iframe[data-src],video[src],source[src],embed[src]")
                                    .forEach { frame ->
                                        absolute(frame.attr("src").ifBlank { frame.attr("data-src") }, pageUrl)
                                            ?.let { result += RuntimeProviderCandidate(it, pageUrl) }
                                    }
                            }
                    }
                }
            }
        }

        result += scriptCandidates(pageUrl, response.document)
        return result.toList()
    }
}

class SamehadakuRuntimeExtractor(http: StreamixHttp) : BaseRuntimeProviderExtractor(http) {
    override val providerId = "samehadaku"

    private val knownHosts = hostRules(
        "blogger.com" to setOf(HostSurface.IFRAME, HostSurface.ANCHOR, HostSurface.SCRIPT),
        "googleusercontent.com" to setOf(HostSurface.IFRAME, HostSurface.SCRIPT),
        "googlevideo.com" to setOf(HostSurface.IFRAME, HostSurface.SCRIPT),
        "gdrive" to setOf(HostSurface.IFRAME, HostSurface.ANCHOR),
        "drive.google.com" to setOf(HostSurface.IFRAME, HostSurface.ANCHOR),
        "gofile.io" to setOf(HostSurface.IFRAME, HostSurface.ANCHOR),
        "filedon.co" to setOf(HostSurface.IFRAME, HostSurface.ANCHOR),
        "qiwi.gg" to setOf(HostSurface.IFRAME, HostSurface.ANCHOR),
        "buzzheavier.com" to setOf(HostSurface.IFRAME, HostSurface.ANCHOR),
        "vidhide" to setOf(HostSurface.IFRAME, HostSurface.ANCHOR),
        "streamwish" to setOf(HostSurface.IFRAME, HostSurface.ANCHOR),
        "filemoon" to setOf(HostSurface.IFRAME, HostSurface.ANCHOR),
        "yourupload.com" to setOf(HostSurface.IFRAME, HostSurface.ANCHOR),
        "mega.nz" to setOf(HostSurface.IFRAME, HostSurface.ANCHOR),
        "b-tube" to setOf(HostSurface.IFRAME, HostSurface.ANCHOR),
        "btube" to setOf(HostSurface.IFRAME, HostSurface.ANCHOR),
    )

    override suspend fun extract(pageUrl: String): List<RuntimeProviderCandidate> {
        val response = page(pageUrl) ?: return emptyList()
        val result = linkedSetOf<RuntimeProviderCandidate>()

        // Samehadaku's authoritative mirror surface is the server/player AJAX
        // response below. Generic page-wide iframe/script scanning produces many
        // unrelated URLs and makes the global resolver spend the entire timeout.
        val base = runCatching { URI(pageUrl).let { x -> x.scheme + "://" + x.authority } }.getOrNull()
        if (base != null) {
            // WordPress themes have moved the server controls between #server,
            // #playeroptionsul and other wrappers. Select by the AJAX contract,
            // not by one wrapper that may disappear between releases.
            response.document.select("[data-post][data-nume][data-type]").forEach { server ->
                val post = server.attr("data-post")
                val nume = server.attr("data-nume")
                val type = server.attr("data-type")
                if (post.isBlank() || nume.isBlank() || type.isBlank()) return@forEach

                val body = listOf("action" to "player_ajax", "post" to post, "nume" to nume, "type" to type)
                    .joinToString("&") { (k, v) ->
                        java.net.URLEncoder.encode(k, "UTF-8") + "=" +
                            java.net.URLEncoder.encode(v, "UTF-8")
                    }

                safeProviderCall {
                    http.post(
                        "$base/wp-admin/admin-ajax.php",
                        body,
                        headers + mapOf("X-Requested-With" to "XMLHttpRequest"),
                        pageUrl
                    ).text
                }?.let { payload ->
                    val ajaxDoc = org.jsoup.Jsoup.parse(payload, pageUrl)
                    frameCandidates(pageUrl, ajaxDoc).forEach { result += it }
                    providerHostCandidates(pageUrl, ajaxDoc, knownHosts).forEach { result += it }
                    Regex("""src\s*=\s*["']([^"']+)["']""").findAll(payload).forEach { match ->
                        absolute(match.groupValues[1], pageUrl)
                            ?.let { result += RuntimeProviderCandidate(it, pageUrl) }
                    }
                }
            }
        }

        // Some current pages render the server iframe directly and do not
        // expose the legacy player_ajax attributes. Keep the provider-specific
        // known-host filter as a safe fallback instead of returning no mirrors.
        result += frameCandidates(pageUrl, response.document).filterNot { sameProviderHost(it.url, pageUrl) }
        result += providerHostCandidates(pageUrl, response.document, knownHosts)
        result += scriptCandidates(pageUrl, response.document)
        return result.toList()
    }
}

class OploverzRuntimeExtractor(http: StreamixHttp) : BaseRuntimeProviderExtractor(http) {
    override val providerId = "oploverz"

    private val knownHosts = hostRules(
        "blogger.com" to setOf(HostSurface.IFRAME, HostSurface.ANCHOR, HostSurface.SCRIPT),
        "googleusercontent.com" to setOf(HostSurface.IFRAME, HostSurface.SCRIPT),
        "googlevideo.com" to setOf(HostSurface.IFRAME, HostSurface.SCRIPT),
        "qiwi.gg" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "filedon.co" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "buzzheavier.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "gofile.io" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "vidhide" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "streamwish" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "filemoon" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "yourupload.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "upbolt" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
    )

    override suspend fun extract(pageUrl: String): List<RuntimeProviderCandidate> {
        val response = page(pageUrl) ?: return emptyList()
        val result = linkedSetOf<RuntimeProviderCandidate>()

        val match = Regex("""/series/([^/?#]+)/episode/(\d+)""").find(pageUrl)
        if (match != null) {
            val slug = match.groupValues[1]
            val episode = match.groupValues[2]
            val apiUrl = "https://backapi.oploverz.ac/api/series/$slug/episodes/$episode"
            val api = safeProviderCall {
                http.get(apiUrl, headers = headers, referer = pageUrl, timeoutMs = 15_000L)
            }

            if (api != null && api.code in 200..399) {
                val data = runCatching { JSONObject(api.text).optJSONObject("data") }.getOrNull()
                val streams = data?.optJSONArray("streamUrl")
                if (streams != null) {
                    for (i in 0 until streams.length()) {
                        val item = streams.optJSONObject(i) ?: continue
                        val url = item.optString("url").trim()
                        if (url.isNotBlank()) result += RuntimeProviderCandidate(url, pageUrl)
                    }
                }
            }
        }

        // Oploverz exposes the playback mirrors through its episode API.
        // Do not rescan the whole page when the API already returned mirrors:
        // the page contains many unrelated Blogger/embed/script URLs and can
        // explode into hundreds of candidates.
        if (result.isEmpty()) {
            result += providerHostCandidates(pageUrl, response.document, knownHosts)
                .take(16)
        }
        return result.toList()
    }
}

class AnimeIndoRuntimeExtractor(http: StreamixHttp) : BaseRuntimeProviderExtractor(http) {
    override val providerId = "animeindo"

    private val knownHosts = hostRules(
        "puterin.biz" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME, HostSurface.SCRIPT),
        "putarin.biz" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME, HostSurface.SCRIPT),
        "putarin.xyz" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME, HostSurface.SCRIPT),
        "play.putar.in" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME, HostSurface.SCRIPT),
        "mp4upload.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME, HostSurface.SCRIPT),
        "b-tube" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME, HostSurface.SCRIPT),
        "btube" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME, HostSurface.SCRIPT),
        "filedon.co" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "gofile.io" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "yourupload.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "vidhide" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "streamwish" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "filemoon" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "blogger.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME, HostSurface.SCRIPT),
        "gdrive" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
    )

    override suspend fun extract(pageUrl: String): List<RuntimeProviderCandidate> {
        val response = page(pageUrl) ?: return emptyList()
        val result = linkedSetOf<RuntimeProviderCandidate>()

        result += frameCandidates(pageUrl, response.document).filterNot { sameProviderHost(it.url, pageUrl) }
        result += providerHostCandidates(pageUrl, response.document, knownHosts)
        result += anchorCandidates(pageUrl, response.document) {
            val href = it.attr("href")
            href.contains("puterin", true) || href.contains("putarin", true) ||
                href.contains("play.putar.in", true) || href.contains("filedon", true) ||
                href.contains("gofile", true) || href.contains("yourupload", true) ||
                href.contains("mp4upload", true) || href.contains("b-tube", true) ||
                href.contains("btube", true) || href.contains("vidhide", true) ||
                href.contains("streamwish", true) || href.contains("filemoon", true) ||
                href.contains("player", true) || it.text().contains("B-TUBE", true) ||
                it.text().contains("MP4", true) || it.text().contains("YUP", true) ||
                it.text().contains("stream", true)
        }
        result += scriptCandidates(pageUrl, response.document)
        return result.toList()
    }
}

class GomunimeRuntimeExtractor(http: StreamixHttp) : BaseRuntimeProviderExtractor(http) {
    override val providerId = "gomunime"

    private val knownHosts = hostRules(
        "gdrive" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "drive.google.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "gofile.io" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "b-tube" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "btube" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "cepat" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "vidhide" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "streamwish" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "filemoon" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "filedon.co" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "yourupload.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "qiwi.gg" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "buzzheavier.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "blogger.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME, HostSurface.SCRIPT),
        "mega.nz" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
    )

    override suspend fun extract(pageUrl: String): List<RuntimeProviderCandidate> {
        val response = page(pageUrl) ?: return emptyList()
        val result = linkedSetOf<RuntimeProviderCandidate>()

        // Gomunime can put the real mirror behind a provider-owned player
        // endpoint/iframe. Cross that boundary once, then only expose external
        // hosts to the global resolver. This mirrors the proven tool-host flow
        // without importing the Node-side player parser into runtime.
        val frameCandidates = frameCandidates(pageUrl, response.document)
        result += frameCandidates.filterNot { sameProviderHost(it.url, pageUrl) }

        frameCandidates.filter { sameProviderHost(it.url, pageUrl) }.take(4).forEach { player ->
            page(player.url)?.let { playerResponse ->
                result += frameCandidates(player.url, playerResponse.document)
                    .filterNot { sameProviderHost(it.url, pageUrl) }
                result += providerHostCandidates(player.url, playerResponse.document, knownHosts)
                result += scriptCandidates(player.url, playerResponse.document)
            }
        }

        result += providerHostCandidates(pageUrl, response.document, knownHosts)
        result += anchorCandidates(pageUrl, response.document) {
            val text = it.text()
            val href = it.attr("href")
            text.contains("GDRIVE", true) || text.contains("B-TUBE", true) ||
                text.contains("CEPAT", true) || href.contains("gdrive", true) ||
                href.contains("gofile", true) || href.contains("btube", true) ||
                href.contains("cepat", true) || href.contains("vidhide", true) ||
                href.contains("streamwish", true) || href.contains("filemoon", true) ||
                href.contains("filedon", true) || href.contains("yourupload", true) ||
                href.contains("qiwi", true) || href.contains("buzzheavier", true) ||
                href.contains("mega", true) || href.contains("player", true)
        }

        // Some Gomunime server buttons keep the player URL in data-* or
        // onclick instead of href. Only accept provider-known/external targets.
        response.document.select(
            "[data-src],[data-url],[data-link],[data-content],[data-embed],button[onclick],a[onclick]"
        ).forEach { element ->
            val values = listOf(
                element.attr("data-src"),
                element.attr("data-url"),
                element.attr("data-link"),
                element.attr("data-content"),
                element.attr("data-embed"),
                Regex("""https?://[^"'\s)]+""").find(element.attr("onclick"))?.value.orEmpty()
            )
            values.filter { it.isNotBlank() }.forEach { raw ->
                absolute(raw, pageUrl)
                    ?.takeUnless { sameProviderHost(it, pageUrl) }
                    ?.let { result += RuntimeProviderCandidate(it, pageUrl) }
            }
        }

        result += scriptCandidates(pageUrl, response.document)
        return result.toList()
    }
}

class SokujaRuntimeExtractor(http: StreamixHttp) : BaseRuntimeProviderExtractor(http) {
    override val providerId = "sokuja"
    private val knownHosts = hostRules(
        "vidhide" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "streamwish" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "filemoon" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "krakenfiles" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "gofile.io" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "pixeldrain.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "blogger.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME, HostSurface.SCRIPT),
        "googlevideo.com" to setOf(HostSurface.IFRAME, HostSurface.SCRIPT),
        "yourupload.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "mp4upload.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "terabox.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME)
    )
    override suspend fun extract(pageUrl: String): List<RuntimeProviderCandidate> {
        val cleanPageUrl = pageUrl.replace(Regex("([?&])__shinigami_(?:ep|episode_id)=\\d+"), "").trimEnd('?', '&')
        val response = page(cleanPageUrl) ?: return emptyList()
        val result = linkedSetOf<RuntimeProviderCandidate>()
        val episodeId = Regex("[?&]__shinigami_episode_id=(\\d+)").find(pageUrl)?.groupValues?.getOrNull(1)
            ?: Regex("""\\?["']episodeId\\?["']\s*:\s*\\?["']?(\d+)""")
            .find(response.text)?.groupValues?.getOrNull(1)
            ?: Regex("""data-episode-id=["'](\d+)["']""", RegexOption.IGNORE_CASE)
                .find(response.text)?.groupValues?.getOrNull(1)
        if (!episodeId.isNullOrBlank()) {
            val apiUrl = "https://x6.sokuja.uk/api/video-mirrors?e=$episodeId"
            val api = safeProviderCall { http.get(apiUrl, headers = headers, referer = pageUrl, timeoutMs = 10_000L) }
            if (api != null && api.code in 200..399) {
                val mirrors = runCatching { JSONObject(api.text).optJSONArray("mirrors") }.getOrNull()
                if (mirrors != null) for (i in 0 until mirrors.length()) {
                    val raw = mirrors.optJSONObject(i)?.optString("embedUrl")?.trim().orEmpty()
                    if (raw.isNotBlank()) absolute(raw, pageUrl)?.let { result += RuntimeProviderCandidate(it, pageUrl) }
                }
            }
        }
        result += frameCandidates(pageUrl, response.document).filterNot { sameProviderHost(it.url, pageUrl) }
        result += providerHostCandidates(pageUrl, response.document, knownHosts)
        result += anchorCandidates(pageUrl, response.document) { element ->
            val href = element.attr("href"); val text = element.text()
            listOf("stream", "player", "mirror", "download").any { text.contains(it, true) } ||
                listOf("embed", "player", "stream", "vidhide", "streamwish", "filemoon").any { href.contains(it, true) }
        }.filterNot { sameProviderHost(it.url, pageUrl) }
        result += scriptCandidates(pageUrl, response.document)
        return result.distinctBy { it.url }.toList()
    }
}

class NimegamiRuntimeExtractor(http: StreamixHttp) : BaseRuntimeProviderExtractor(http) {
    override val providerId = "nimegami"
    private val knownHosts = hostRules(
        "krakenfiles" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "berkasdrive.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "vidhide" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "streamwish" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "filemoon" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "gofile.io" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "pixeldrain.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "blogger.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME, HostSurface.SCRIPT)
    )
    override suspend fun extract(pageUrl: String): List<RuntimeProviderCandidate> {
        val episodeNumber = Regex("[?&]__shinigami_ep=(\\d+)").find(pageUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val cleanPageUrl = pageUrl.replace(Regex("([?&])__shinigami_ep=\\d+"), "").trimEnd('?', '&')
        val response = page(cleanPageUrl) ?: return emptyList()
        val result = linkedSetOf<RuntimeProviderCandidate>()
        val container = if (episodeNumber != null) {
            response.document.selectFirst("#download_episode_$episodeNumber") ?: return emptyList()
        } else {
            response.document.selectFirst("#download") ?: response.document
        }
        val containerDocument = org.jsoup.Jsoup.parse(container.outerHtml(), cleanPageUrl)
        val rows = container.select(".download-row")
        val downloadRows = if (rows.isNotEmpty()) rows else listOf(container)
        // Probe only a small number of mirrors and bound each redirect lookup.
        // Nimegami pages may list many slow download hosts; probing all of them
        // can stall the runtime fallback before another provider returns a stream.
        for (row in downloadRows) for (anchor in row.select("a[href*='/download/']").take(6)) {
            val href = absolute(anchor.attr("href"), cleanPageUrl) ?: continue
            val resolved = withTimeoutOrNull(4_000L) {
                safeProviderCall { http.get(href, headers = headers, referer = cleanPageUrl, timeoutMs = 3_500L) }
            }
            if (resolved != null) {
                val location = resolved.headers.entries.firstOrNull { it.key.equals("location", true) }?.value?.firstOrNull()
                val finalUrl = location?.let { absolute(it, href) } ?: resolved.url
                if (finalUrl.isNotBlank()) {
                    val direct = if (finalUrl.contains("berkasdrive.com", true)) {
                        finalUrl.replace("/hal/download/", "/public/download/").replace("/hal/streaming/", "/public/download/")
                    } else finalUrl
                    if (!sameProviderHost(direct, cleanPageUrl)) result += RuntimeProviderCandidate(direct, cleanPageUrl)
                }
            }
        }
        result += frameCandidates(cleanPageUrl, containerDocument).filterNot { sameProviderHost(it.url, cleanPageUrl) }
        result += providerHostCandidates(cleanPageUrl, containerDocument, knownHosts)
        result += anchorCandidates(cleanPageUrl, containerDocument) { element ->
            val href = element.attr("href")
            href.contains("/download/", true) || listOf("krakenfiles", "berkasdrive", "vidhide", "streamwish", "filemoon", "gofile", "pixeldrain").any { href.contains(it, true) }
        }.filterNot { sameProviderHost(it.url, cleanPageUrl) }
        result += scriptCandidates(cleanPageUrl, containerDocument)
        return result.distinctBy { it.url }.toList()
    }
}

class AlqanimeRuntimeExtractor(http: StreamixHttp) : BaseRuntimeProviderExtractor(http) {
    override val providerId = "alqanime"
    private val knownHosts = hostRules(
        "pixeldrain.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "mediafire.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "acefile.co" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "resharer.org" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "yurinime.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "yourupload.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "filedon" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "krakenfiles" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "gofile.io" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "vidhide" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "mixdrop" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "streamtape" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "filemoon" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "streamwish" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "terabox" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        // AnimeX/CloudStream-compatible host coverage: Alqanime's episode
        // download section can expose more than PixelDrain and GoFile.
        "mp4upload.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "buzzheavier.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "krakenfiles.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "filedon.co" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "yourupload.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "filesim.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "hxfile.co" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "linkbox.to" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "lbx.to" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "mega.nz" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "ok.ru" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "vk.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "doodstream.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "dood.to" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "streamhide.to" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "kuramadrive.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "vtbe.to" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "waaw.to" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "wishfast.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "streampoi.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "nyomo.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "dailymotion.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "archive.org" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        // Additional native AnimeX-compatible extractors already registered by Shinigami.
        "odnoklassniki.ru" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "rumble.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "streamruby.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "svilla.net" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "byse.sx" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "hownetwork.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "cloudhownetwork.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "playstreamplay.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "dhcplay.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "voe.sx" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "xtwap.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "movearnpre.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "videonode.net" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "streamhg.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME),
        "blogger.com" to setOf(HostSurface.ANCHOR, HostSurface.IFRAME, HostSurface.SCRIPT),
        "googlevideo.com" to setOf(HostSurface.IFRAME, HostSurface.SCRIPT)
    )

    override suspend fun extract(pageUrl: String): List<RuntimeProviderCandidate> {
        // Alqanime stores episode mirrors inside a collapsible section on the anime page.
        // The marker is internal to Shinigami and is stripped before the source request.
        val episodeNumber = Regex("[?&]__shinigami_ep=(\\d+)").find(pageUrl)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
        val cleanPageUrl = pageUrl.replace(Regex("([?&])__shinigami_ep=\\d+"), "").trimEnd('?', '&')
        val response = page(cleanPageUrl) ?: return emptyList()
        val scopedDocument = if (episodeNumber != null) {
            val section = response.document.select("div.sorattl.collapsible").firstOrNull { element ->
                // Alqanime's accordion heading is not consistently an h3; some pages
                // render it as a plain wrapper. Match the section text as a fallback.
                val title = element.selectFirst("h1, h2, h3, h4, h5, h6, .title, .panel-title")?.text()
                    ?.takeIf { it.isNotBlank() } ?: element.text()
                Regex("(?i)episode\\s*0*(\\d+)").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull() == episodeNumber
            } ?: return emptyList()
            val content = section.nextElementSibling()?.takeIf { it.hasClass("content") } ?: return emptyList()
            org.jsoup.Jsoup.parse(content.outerHtml(), cleanPageUrl)
        } else response.document

        val result = linkedSetOf<RuntimeProviderCandidate>()
        if (episodeNumber != null) {
            // Alqanime's download buttons can link to a single PixelDrain file
            // (/u/{id} or /d/{id}) or a folder (/l/{id}). Normalize only
            // single-file URLs here; folder links are enumerated below so the
            // selected episode is matched against each filename before emitting.
            scopedDocument.select("a[href]").forEach { anchor ->
                val href = absolute(anchor.attr("href"), cleanPageUrl) ?: return@forEach
                val uri = runCatching { java.net.URI(href) }.getOrNull() ?: return@forEach
                if (!uri.host.equals("pixeldrain.com", ignoreCase = true)) return@forEach
                val fileId = Regex("""^/(?:u|d|file)/([A-Za-z0-9]+)$""")
                    .find(uri.path.orEmpty())?.groupValues?.getOrNull(1)
                if (!fileId.isNullOrBlank()) {
                    result += RuntimeProviderCandidate("https://pixeldrain.com/api/file/" + fileId, cleanPageUrl)
                }
            }
            val folderIds = scopedDocument.select("a[href]").mapNotNull { anchor ->
                Regex("""pixeldrain\.com/l/([A-Za-z0-9]+)""").find(anchor.attr("href"))?.groupValues?.getOrNull(1)
            }.distinct()
            for (folderId in folderIds) {
                val listResponse = safeProviderCall {
                    http.get(
                        "https://pixeldrain.com/api/list/" + folderId,
                        headers = headers,
                        referer = cleanPageUrl,
                        timeoutMs = 10_000L
                    )
                } ?: continue
                if (listResponse.code !in 200..399) continue
                val files = runCatching { JSONObject(listResponse.text).optJSONArray("files") }.getOrNull() ?: continue
                for (index in 0 until files.length()) {
                    val file = files.optJSONObject(index) ?: continue
                    if (!file.optString("mime_type").startsWith("video/", true)) continue
                    val fileEpisode = Regex("""(?:_|-)0*(\d+)(?:_|-)""")
                        .find(file.optString("name"))?.groupValues?.getOrNull(1)?.toIntOrNull()
                    if (fileEpisode != episodeNumber) continue
                    val fileId = file.optString("id").trim()
                    if (fileId.isNotBlank()) {
                        result += RuntimeProviderCandidate("https://pixeldrain.com/api/file/" + fileId, cleanPageUrl)
                    }
                }
            }
        }
        result += frameCandidates(cleanPageUrl, scopedDocument).filterNot { sameProviderHost(it.url, cleanPageUrl) }
        result += providerHostCandidates(cleanPageUrl, scopedDocument, knownHosts)
        result += anchorCandidates(cleanPageUrl, scopedDocument) { element ->
            val href = element.attr("href")
            val text = element.text()
            element.closest("div.slink") != null ||
                listOf("player", "stream", "mirror", "pixeldrain", "mediafire", "acefile", "resharer", "vidhide", "filemoon")
                    .any { href.contains(it, true) || text.contains(it, true) }
        }.filterNot { sameProviderHost(it.url, cleanPageUrl) }
        scopedDocument.select("[data-url],[data-link],[data-src],[data-content],[data-embed],button[onclick],a[onclick]").forEach { element ->
            val values = listOf(
                element.attr("data-url"), element.attr("data-link"), element.attr("data-src"),
                element.attr("data-content"), element.attr("data-embed"),
                Regex("""https?://[^"'\s)]+""").find(element.attr("onclick"))?.value.orEmpty()
            )
            values.filter { it.isNotBlank() }.forEach { raw ->
                absolute(raw, cleanPageUrl)?.takeUnless { sameProviderHost(it, cleanPageUrl) }
                    ?.let { result += RuntimeProviderCandidate(it, cleanPageUrl) }
                decodeBase64(raw)?.let { decoded ->
                    val parsed = org.jsoup.Jsoup.parse(decoded, cleanPageUrl)
                    result += frameCandidates(cleanPageUrl, parsed).filterNot { sameProviderHost(it.url, cleanPageUrl) }
                    result += providerHostCandidates(cleanPageUrl, parsed, knownHosts)
                }
            }
        }
        result += scriptCandidates(cleanPageUrl, scopedDocument)
        return result.distinctBy { it.url }.toList()
    }
}
