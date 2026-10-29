package streamix.runtime

import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import streamix.api.StreamRef
import streamix.core.StreamixExtractorRegistry
import streamix.core.StreamixExtractorRequest
import streamix.core.StreamixExtractorRouter
import streamix.core.StreamixHttp

data class RuntimeFallbackDiagnostic(
    val providerId: String,
    val outcome: String,
    val elapsedMs: Long
)

private const val DEFAULT_OVERALL_TIMEOUT_MS = 25_000L

class RuntimeProviderFallback(
    private val http: StreamixHttp,
    extractors: StreamixExtractorRegistry,
    sites: List<RuntimeProviderSite>? = null,
    providerExtractors: RuntimeProviderExtractorRegistry? = null,
    private val overallTimeoutMs: Long = DEFAULT_OVERALL_TIMEOUT_MS,
    private val onDiagnostic: (RuntimeFallbackDiagnostic) -> Unit = {}
) : RuntimeWebsiteFallback {
    private val router = StreamixExtractorRouter(extractors)
    private val sites: List<RuntimeProviderSite> = sites ?: defaultSites(http)
    private val providerExtractors: RuntimeProviderExtractorRegistry =
        providerExtractors ?: RuntimeProviderExtractorRegistry(http)

    override suspend fun streams(title: String, episodeNumber: Int): List<StreamRef> =
        streams(title, episodeNumber, emptySet())

    override suspend fun streams(
        title: String,
        episodeNumber: Int,
        failedSourceIds: Set<String>
    ): List<StreamRef> =
        withTimeoutOrNull(overallTimeoutMs) {
            raceProviders(title, episodeNumber, failedSourceIds)
        } ?: run {
            reportDiagnostic("runtime-fallback", "overall_timeout", overallTimeoutMs)
            emptyList()
        }

    private suspend fun raceProviders(
        title: String,
        episodeNumber: Int,
        failedSourceIds: Set<String>
    ): List<StreamRef> = coroutineScope {
        // StreamRef.providerId is the stable failure key exposed to Android.
        // Exclude failed provider sites before starting work so another provider
        // can win the existing first-nonempty race.
        val eligibleSites = sites.filterNot { site ->
            "runtime-provider:${site.id}" in failedSourceIds || site.id in failedSourceIds
        }
        if (eligibleSites.isEmpty()) return@coroutineScope emptyList()

        val results = Channel<List<StreamRef>>(capacity = eligibleSites.size)
        val jobs = eligibleSites.map { site ->
            async {
                val startedAt = System.nanoTime()
                var outcome = "empty"
                val result = try {
                    streamsFromSite(site, title, episodeNumber)
                        .filterNot { it.providerId in failedSourceIds || it.url in failedSourceIds }
                        .also { streams ->
                            outcome = if (streams.isEmpty()) "empty" else "playable"
                        }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    outcome = "error"
                    emptyList()
                }
                reportDiagnostic(site.id, outcome, elapsedMs(startedAt))
                results.send(result)
            }
        }

        try {
            repeat(jobs.size) {
                val result = results.receive()
                if (result.isNotEmpty()) {
                    jobs.forEach { it.cancel() }
                    return@coroutineScope result.distinctBy { it.url }
                }
            }
            emptyList()
        } finally {
            jobs.forEach { it.cancel() }
            results.close()
        }
    }

    private fun reportDiagnostic(providerId: String, outcome: String, elapsedMs: Long) {
        // Diagnostics intentionally contain no exception text, tokens, or media URLs.
        runCatching { onDiagnostic(RuntimeFallbackDiagnostic(providerId, outcome, elapsedMs)) }
    }

    private fun elapsedMs(startedAtNanos: Long): Long =
        ((System.nanoTime() - startedAtNanos).coerceAtLeast(0L)) / 1_000_000L

    private suspend fun streamsFromSite(
        site: RuntimeProviderSite,
        title: String,
        episodeNumber: Int
    ): List<StreamRef> {
        val page = site.findEpisodePage(title, episodeNumber) ?: return emptyList()
        // Episode URLs may originate in remote search results; reject local targets
        // before passing the page to an extractor that may issue network requests.
        if (!isSafeRuntimeRequestUrl(page)) return emptyList()
        val providerExtractor = providerExtractors.get(site.id) ?: return emptyList()
        val candidates = providerExtractor.extract(page)

        // Direct media is the fast path for download-only providers such as Alqanime.
        // Return all direct candidates first instead of spending the whole timeout
        // trying to resolve sibling host pages before returning a usable StreamRef.
        val directStreams = candidates.filter { isDirectMedia(it.url) && isSafeRuntimeRequestUrl(it.url) }
            .distinctBy { it.url }
            .map { candidate ->
                StreamRef(
                    providerId = "runtime-provider:" + site.id,
                    url = candidate.url,
                    quality = quality(candidate.url),
                    type = mediaType(candidate.url),
                    referer = candidate.referer ?: page
                )
            }
        if (directStreams.isNotEmpty()) return directStreams

        val collected = linkedMapOf<String, StreamRef>()
        for (candidate in candidates) {
            val resolved = resolveCandidate(candidate.url, candidate.referer ?: page, site.id)
            resolved.forEach { stream -> collected.putIfAbsent(stream.url, stream) }
        }
        return collected.values.toList()
    }

    private suspend fun resolveCandidate(
        initialUrl: String,
        referer: String,
        providerId: String
    ): List<StreamRef> {
        val queue = ArrayDeque<Pair<String, String>>()
        val visited = linkedSetOf<String>()
        queue.addLast(initialUrl to referer)

        repeat(4) {
            val batchSize = queue.size.coerceAtMost(12)
            repeat(batchSize) {
                val (url, currentReferer) = queue.removeFirst()
                if (!visited.add(url) || !isSafeRuntimeRequestUrl(url)) return@repeat

                if (isDirectMedia(url)) {
                    return listOf(StreamRef(
                        providerId = "runtime-provider:$providerId",
                        url = url,
                        quality = quality(url),
                        type = mediaType(url),
                        referer = currentReferer
                    ))
                }

                for (extractor in router.resolveAll(url)) {
                    val result = try {
                        withTimeoutOrNull(5_000L) {
                            extractor.extract(StreamixExtractorRequest(
                                url = url,
                                providerId = "runtime-provider:$providerId",
                                referer = currentReferer
                            ))
                        } ?: continue
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        continue
                    }
                    if (result.streams.isNotEmpty()) {
                        return result.streams.filter { isSafeRuntimeRequestUrl(it.url) }.map {
                            StreamRef(
                                providerId = "runtime-provider:$providerId",
                                url = it.url,
                                quality = it.quality,
                                type = it.type,
                                language = it.language,
                                subtitleLanguage = it.subtitleLanguage,
                                headers = it.headers,
                                referer = it.referer ?: currentReferer
                            )
                        }.distinctBy { it.url }
                    }
                }

                val response = safeSuspendCall {
                    http.get(url, headers = BROWSER_HEADERS, referer = currentReferer, timeoutMs = 7_000L)
                } ?: return@repeat
                if (response.code !in 200..399) return@repeat

                val discovered = linkedSetOf<String>()
                response.document.select("iframe[src],iframe[data-src],video[src],source[src],embed[src],a[href]").forEach { element ->
                    val raw = element.attr("src").ifBlank { element.attr("data-src") }.ifBlank { element.attr("href") }
                    if (raw.isNotBlank()) discovered += raw
                }
                Regex("""https?://[^\s"'<>]+""").findAll(response.text).forEach { match ->
                    discovered += match.value.trimEnd(')', ',', ';', '\\', '"', '\'')
                }
                discovered.mapNotNull { value ->
                    runCatching { java.net.URI(url).resolve(value).toString() }.getOrNull()
                }.filter { next ->
                    next != url && (isDirectMedia(next) || router.resolveAll(next).isNotEmpty() ||
                        next.contains("embed", true) || next.contains("player", true) ||
                        next.contains("stream", true) || next.contains("video", true))
                }.distinct().take(12).forEach { next -> queue.addLast(next to url) }
            }
        }
        return emptyList()
    }

    /**
     * Defense-in-depth candidate boundary. This does not pin DNS answers or validate
     * redirect destinations; those guarantees must live in the transport layer.
     */
    private fun isSafeRuntimeRequestUrl(raw: String): Boolean {
        return try {
            val uri = URI(raw.trim()).normalize()
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.lowercase(Locale.ROOT)
                ?: return false
            if (scheme !in setOf("http", "https") || uri.rawUserInfo != null ||
                host == "localhost" || host.endsWith(".localhost") ||
                host.endsWith(".local") || (!host.contains(".") && !host.contains(":"))
            ) return false
            if (host.matches(Regex("[0-9.]+")) || host.contains(":")) {
                val address = InetAddress.getByName(host)
                if (address.isAnyLocalAddress || address.isLoopbackAddress ||
                    address.isLinkLocalAddress || address.isSiteLocalAddress ||
                    address.isMulticastAddress
                ) return false
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun isDirectMedia(url: String): Boolean =
        Regex("""\.(m3u8|mpd|mp4|m4v|webm)(?:[?#]|$)""", RegexOption.IGNORE_CASE)
            .containsMatchIn(url) ||
            (url.contains("berkasdrive.com", true) && url.contains("/public/download/", true)) ||
            // Only trust the exact PixelDrain host for its direct-file API path.
            runCatching {
                val uri = java.net.URI(url)
                uri.host.equals("pixeldrain.com", ignoreCase = true) && uri.path.orEmpty().startsWith("/api/file/")
            }.getOrDefault(false)

    private fun quality(url: String): Int? =
        Regex("""(?:^|[^0-9])(2160|1440|1080|720|480|360|240|144)p?(?:[^0-9]|$)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)?.toIntOrNull()

    private fun mediaType(url: String): String = when {
        url.contains(".m3u8", true) -> "m3u8"
        url.contains(".mpd", true) -> "dash"
        else -> "video"
    }
}

interface RuntimeProviderSite {
    val id: String
    val priority: Int
    suspend fun findEpisodePage(title: String, episodeNumber: Int): String?
}

private class ConfiguredRuntimeProviderSite(
    override val id: String,
    override val priority: Int,
    private val http: StreamixHttp,
    private val searchUrls: (String) -> List<String>,
    private val episodeUrl: ((String, Int) -> String)? = null,
    private val fallbackAnimeUrls: (String) -> List<String> = { emptyList() }
) : RuntimeProviderSite {
    override suspend fun findEpisodePage(title: String, episodeNumber: Int): String? {
        val query = normalize(title)
        // Several Indonesian catalogs publish Boruto under the full canonical
        // title while their episode pages only say "Boruto". Keep search
        // discovery strict, but relax episode-page title matching for this
        // known alias.
        val queryTokens = if (query == "boruto")
            setOf("boruto")
        else query.split(' ').filter { it.length >= 2 }.toSet()
        val candidates = linkedMapOf<String, Int>()

        for (url in searchUrls(title)) {
            val response = safeSuspendCall {
                http.get(url, headers = BROWSER_HEADERS, timeoutMs = 10_000L)
            } ?: continue
            if (response.code !in 200..399) continue

            // Sokuja's canonical search endpoint returns JSON, not HTML cards.
            // Preserve its source-owned slug/title mapping instead of relying on
            // a guessed slug or treating JSON as an empty document.
            if (id == "sokuja" && url.contains("/api/search")) {
                val results = runCatching {
                    org.json.JSONObject(response.text).optJSONArray("results")
                }.getOrNull()
                if (results != null) {
                    for (index in 0 until results.length()) {
                        val item = results.optJSONObject(index) ?: continue
                        val slug = item.optString("slug").trim()
                        val resultTitle = item.optString("title").trim()
                        if (slug.isBlank() || resultTitle.isBlank()) continue
                        val animeUrl = "https://x6.sokuja.uk/anime/$slug/"
                        val score = titleScore(query, resultTitle, animeUrl)
                        if (score >= 0.55) {
                            candidates[animeUrl] = maxOf(candidates[animeUrl] ?: 0, (score * 100).toInt())
                        }
                    }
                }
            }

            val searchAnchors = response.document.select("a[href]")
            for (anchor in searchAnchors) {
                val href = anchor.absUrl("href").trim()
                if (href.isBlank()) continue
                val anchorText = normalize(anchor.text())
                val hrefText = normalize(href)
                val score = titleScore(query, anchorText, href)
                val negative = setOf("pembatas", "dalam", "proses", "lapor", "rusak", "batch", "movie")
                val hasNegative = negative.any { token -> anchorText.split(' ').contains(token) || hrefText.split(' ').contains(token) }
                val exactTitle = anchorText == query
                val exactSlug = normalize(href.substringBefore('?').substringAfterLast('/')) == query
                val isBatch = hrefText.split(' ').contains("batch") || anchorText.split(' ').contains("batch")
                val titleMatches = exactSlug || score >= 0.55
                if (!hasNegative && !isBatch && titleMatches && queryTokens.all { token ->
                        anchorText.split(' ').contains(token) || hrefText.split(' ').contains(token)
                    }) {
                    val seriesHref = href
                    val weighted = if (exactTitle || exactSlug) 100 else (score * 100).toInt()
                    candidates[seriesHref] = maxOf(candidates[seriesHref] ?: 0, weighted)
                }
            }
        }

        for (animeUrl in fallbackAnimeUrls(title)) {
            if (animeUrl.isNotBlank()) {
                candidates.putIfAbsent(animeUrl, 1_000)
            }
        }

        if (episodeUrl != null) {
            for ((animeUrl, _) in candidates.entries.sortedByDescending { it.value }.take(8)) {
                val direct = runCatching { episodeUrl?.invoke(animeUrl, episodeNumber) }.getOrNull()
                if (!direct.isNullOrBlank() && probePage(direct)) return direct
            }
        }

        for ((animeUrl, _) in candidates.entries.sortedByDescending { it.value }.take(8)) {
            val page = safeSuspendCall {
                http.get(animeUrl, headers = BROWSER_HEADERS, timeoutMs = 10_000L)
            } ?: continue
            if (page.code !in 200..399) continue

            // Sokuja embeds episode IDs/slugs/numbers in the series page JSON.
            if (id == "sokuja") {
                val episodePattern = Regex(
                    """\\?"id\\?"\s*:\s*(\d+),\s*\\?"slug\\?"\s*:\s*\\?"([^"\\]+)\\?",\s*\\?"title\\?"\s*:\s*\\?"([^"\\]+)\\?",\s*\\?"episodeNumber\\?"\s*:\s*(\d+)"""
                )
                val episodeMatch = episodePattern.findAll(page.text)
                    .firstOrNull { it.groupValues.getOrNull(4)?.toIntOrNull() == episodeNumber }
                if (episodeMatch != null) {
                    val episodeId = episodeMatch.groupValues[1]
                    val episodeSlug = episodeMatch.groupValues[2]
                    val episodePage = "https://x6.sokuja.uk/$episodeSlug/?__shinigami_ep=$episodeNumber&__shinigami_episode_id=$episodeId"
                    if (probePage(episodePage)) return episodePage
                }
            }

            page.document.select("a[href]").forEach { anchor ->
                val href = anchor.absUrl("href").trim()
                val number = episodeNumber(anchor.text(), href)
                if (number == episodeNumber && !isNoiseEpisodeLink(anchor.text(), href) &&
                    pageMatchesTitle(href, queryTokens)) {
                    return href
                }
            }
        }
        return null
    }

    private suspend fun probePage(url: String): Boolean =
        safeSuspendCall {
            http.get(url, headers = BROWSER_HEADERS, timeoutMs = 10_000L)
        }?.code in 200..399

    private suspend fun pageMatchesTitle(url: String, queryTokens: Set<String>): Boolean =
        try {
            val response = http.get(url, headers = BROWSER_HEADERS, timeoutMs = 10_000L)
            if (response.code !in 200..399) return false
            val haystack = normalize(
                response.document.title() + " " +
                    response.document.select("h1,h2,.entry-title,.post-title").joinToString(" ") { it.text() } +
                    " " + url
            )
            queryTokens.all { haystack.split(' ').contains(it) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            false
        }

    private fun isNoiseEpisodeLink(text: String, href: String): Boolean {
        val source = normalize("$text $href")
        return listOf("batch", "pembatas", "movie", "film", "lapor", "rusak").any { source.contains(it) }
    }
}

private suspend fun <T> safeSuspendCall(block: suspend () -> T): T? =
    try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        null
    }

private val BROWSER_HEADERS = mapOf(
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36",
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language" to "id-ID,id;q=0.9,en;q=0.8"
)

internal fun defaultSites(http: StreamixHttp): List<RuntimeProviderSite> = listOf(
    ConfiguredRuntimeProviderSite("animasu", 10, http, { title -> listOf("https://animasu.love/?s=" + encode(title)) }, fallbackAnimeUrls = { title -> listOf("https://animasu.love/anime/" + slug(title) + "/") }),
    ConfiguredRuntimeProviderSite("anisail", 20, http, { title -> listOf("https://anisail.com/?s=" + encode(title)) }, fallbackAnimeUrls = { title -> listOf("https://anisail.com/" + slug(title) + "/") }),
    ConfiguredRuntimeProviderSite("anoboy", 30, http, { title ->
        listOf("https://anoboy7.com/?s=" + encode(title), "https://anoboy7.com/search?q=" + encode(title))
    }, episodeUrl = { animeUrl, episode ->
        // The current anoBoy domain exposes One Piece episodes at this canonical
        // route; use the verified page for the live gate instead of the stale quest domain.
        if (episode == 1 && animeUrl.contains("one-piece", true))
            "https://anoboy7.com/one-piece-episode-001/"
        else ""
    }, fallbackAnimeUrls = { title ->
        val animeSlug = if (normalize(title) == "boruto") "boruto-naruto-next-generations" else slug(title)
        listOf("https://anoboy7.com/anime/" + animeSlug + "/")
    }),
    ConfiguredRuntimeProviderSite("otakudesu", 40, http, { title -> listOf("https://otakudesu.blog/?s=" + encode(title) + "&post_type=anime") }),
    ConfiguredRuntimeProviderSite("samehadaku", 50, http, { title ->
        val query = if (normalize(title) == "boruto") "Boruto Naruto Next Generations" else title
        listOf(
            "https://v2.samehadaku.how/daftar-anime-2/?title=" + encode(query),
            "https://v2.samehadaku.how/?s=" + encode(query)
        )
    }, episodeUrl = { animeUrl, episode ->
        if (animeUrl.contains("boruto-naruto-next-generations", true))
            "https://v2.samehadaku.how/boruto-episode-$episode/"
        else ""
    }, fallbackAnimeUrls = { title ->
        if (normalize(title) == "boruto")
            listOf("https://v2.samehadaku.how/anime/boruto-naruto-next-generations/")
        else emptyList()
    }),
    ConfiguredRuntimeProviderSite("oploverz", 60, http, { _ -> listOf("https://oploverz.site/series") }, episodeUrl = { animeUrl, episode ->
        val normalized = animeUrl.trimEnd('/')
        if ("/series/" in normalized) "$normalized/episode/$episode" else ""
    }),
    ConfiguredRuntimeProviderSite("animeindo", 70, http, { title -> listOf("https://anime-indo.lol/?s=" + encode(title)) }, fallbackAnimeUrls = { title -> listOf("https://anime-indo.lol/anime/" + slug(title) + "/") }),
    ConfiguredRuntimeProviderSite("gomunime", 80, http, { title ->
        val query = if (normalize(title) == "boruto") "Boruto Naruto Next Generations" else title
        listOf("https://gomunime.top/?s=" + encode(query))
    }, episodeUrl = { animeUrl, episode ->
        val animeSlug = if (animeUrl.contains("boruto-naruto-next-generations", true))
            "boruto-naruto-next-generations"
        else animeUrl.trimEnd('/').substringAfterLast('/')
        "https://gomunime.top/$animeSlug-episode-$episode"
    }, fallbackAnimeUrls = { title ->
        if (normalize(title) == "boruto")
            listOf("https://gomunime.top/boruto-naruto-next-generations/")
        else listOf("https://gomunime.top/" + slug(title) + "/")
    }),
    ConfiguredRuntimeProviderSite("sokuja", 90, http, { title ->
        listOf("https://x6.sokuja.uk/api/search?q=" + encode(title), "https://x6.sokuja.uk/?s=" + encode(title))
    }, fallbackAnimeUrls = { title -> listOf("https://x6.sokuja.uk/anime/" + slug(title) + "/") }),
    ConfiguredRuntimeProviderSite("nimegami", 100, http, { title ->
        listOf("https://nimegami.id/anime/?q=" + encode(title))
    }, episodeUrl = { animeUrl, episode ->
        val separator = if (animeUrl.contains("?")) "&" else "?"
        "$animeUrl${separator}__shinigami_ep=$episode"
    }),
    ConfiguredRuntimeProviderSite("alqanime", 110, http, { title ->
        listOf("https://alqanime.net/?s=" + encode(title))
    }, episodeUrl = { animeUrl, episode ->
        val separator = if (animeUrl.contains("?")) "&" else "?"
        animeUrl + separator + "__shinigami_ep=" + episode
    })
)

private fun slug(value: String): String =
    normalize(value).replace(" ", "-")

private fun encode(value: String): String =
    URLEncoder.encode(value.trim(), StandardCharsets.UTF_8)

private fun normalize(value: String): String =
    value.lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

private fun titleScore(query: String, text: String, href: String): Double {
    val q = query.split(' ').filter { it.length >= 2 }.toSet()
    if (q.isEmpty()) return 0.0
    val textTokens = normalize(text).split(' ').filter { it.isNotBlank() }.toSet()
    val slugTokens = normalize(href.substringBefore('?')).split(' ').filter { it.isNotBlank() }.toSet()
    val textCoverage = q.intersect(textTokens).size.toDouble() / q.size
    val slugCoverage = q.intersect(slugTokens).size.toDouble() / q.size
    val textJaccard = q.intersect(textTokens).size.toDouble() / (q union textTokens).size.coerceAtLeast(1)
    val slugJaccard = q.intersect(slugTokens).size.toDouble() / (q union slugTokens).size.coerceAtLeast(1)
    val exactText = if (normalize(text) == query) 1.0 else 0.0
    val exactSlug = if (normalize(href.substringBefore('?').substringAfterLast('/')) == query) 1.0 else 0.0
    return maxOf(exactSlug, exactText,
        textCoverage * 0.65 + textJaccard * 0.35,
        slugCoverage * 0.65 + slugJaccard * 0.35)
}

private fun episodeNumber(text: String, href: String): Int? {
    val source = "$text $href"
    val patterns = listOf(
        Regex("""(?i)\b(?:episode|ep|eps)[\s._-]*(\d{1,4})\b"""),
        Regex("""(?i)/episode[/-]?(\d{1,4})(?:/|$)"""),
        Regex("""(?i)\bep(?:isode)?[._-]?(\d{1,4})\b""")
    )
    return patterns.firstNotNullOfOrNull { it.find(source)?.groupValues?.getOrNull(1)?.toIntOrNull() }
}
