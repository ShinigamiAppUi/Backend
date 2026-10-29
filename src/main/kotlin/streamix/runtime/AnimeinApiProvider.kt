package streamix.runtime

import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import streamix.api.StreamRef
import streamix.api.SubtitleRef
import streamix.core.StreamixExtractorRegistry
import streamix.core.StreamixExtractorRequest
import streamix.core.StreamixExtractorRouter
import streamix.core.StreamixHttp

/**
 * Dedicated adapter for Animein's app API.
 *
 * This is intentionally separate from website RuntimeProviderFallback and does
 * not load the retired AnimeX/Hatsune provider registry. Catalog remains first;
 * this adapter is invoked only when Catalog has no playable result.
 */
class AnimeinApiProvider(
    private val http: StreamixHttp,
    extractors: StreamixExtractorRegistry
) : AnimeinStreamProvider {
    private val router = StreamixExtractorRouter(extractors)
    private val animeIdCache = ConcurrentHashMap<String, String>()
    private val episodeIdCache = ConcurrentHashMap<String, String>()
    @Volatile
    private var apiBase: String = API_BASE

    override suspend fun streams(title: String, episodeNumber: Int): List<StreamRef> =
        withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
            val normalizedTitle = normalize(title)
            if (normalizedTitle.isBlank() || episodeNumber < 1) return@withTimeoutOrNull emptyList()

            val animeId = animeIdCache[normalizedTitle] ?: findAnimeId(title)?.also {
                animeIdCache[normalizedTitle] = it
            } ?: return@withTimeoutOrNull emptyList()

            val episodeCacheKey = "$animeId:$episodeNumber"
            val episodeId = episodeIdCache[episodeCacheKey] ?: findEpisodeId(animeId, episodeNumber)?.also {
                episodeIdCache[episodeCacheKey] = it
            } ?: return@withTimeoutOrNull emptyList()

            loadStreams(episodeId, episodeNumber).distinctBy { it.url }
        } ?: emptyList()

    private suspend fun findAnimeId(title: String): String? {
        for (base in listOf(apiBase, API_BASE, ALT_API_BASE).distinct()) {
            val found = searchAtBase(base, title)
            if (found != null) {
                apiBase = base
                return found
            }
        }

        // The app API can advertise a replacement API domain. Only pay this
        // extra request after both configured API hosts failed to yield a match.
        val replacement = resolveApiBase() ?: return null
        if (replacement in listOf(apiBase, API_BASE, ALT_API_BASE)) return null
        return searchAtBase(replacement, title)?.also { apiBase = replacement }
    }

    private suspend fun searchAtBase(base: String, title: String): String? {
        // Live API preflight confirms this endpoint/key pair returns an exact
        // One Piece result. Prefer the deterministic exact-title request before
        // the broad concurrent search, whose 2.2s wave can expire before the
        // useful response arrives among unrelated result sets.
        val preferred = requestJson(
            base,
            "data/movie/find",
            mapOf("page" to "1", "title" to title)
        )
        parseMovies(preferred ?: JSONObject())
            .firstOrNull { normalize(it.title) == normalize(title) }
            ?.let { return it.id }

        return withTimeoutOrNull(SEARCH_WAVE_TIMEOUT_MS) {
            coroutineScope {
                val capacity = SEARCH_PATHS.size * SEARCH_KEYS.size
                val results = Channel<List<MovieCandidate>>(capacity = capacity)
                val jobs = SEARCH_PATHS.flatMap { path ->
                    SEARCH_KEYS.map { key ->
                        async {
                            val json = requestJson(
                                base,
                                path,
                                mapOf("page" to "1", key to title)
                            )
                            results.send(if (json == null) emptyList() else parseMovies(json))
                        }
                    }
                }

                var best: Pair<MovieCandidate, Double>? = null
                repeat(jobs.size) {
                    val candidates = results.receive()
                    val exact = candidates.firstOrNull {
                        normalize(it.title) == normalize(title)
                    }
                    if (exact != null) {
                        jobs.forEach { it.cancel() }
                        return@coroutineScope exact.id
                    }
                    for (candidate in candidates) {
                        val score = titleScore(title, candidate.title)
                        if (score >= MIN_TITLE_SCORE && (best == null || score > best!!.second)) {
                            best = candidate to score
                        }
                    }
                }
                best?.first?.id
            }
        }
    }

    private suspend fun findEpisodeId(animeId: String, episodeNumber: Int): String? {
        // Match AnimeX's source contract: the API episode record owns the
        // canonical database id; streamnew must receive that id, not a guessed
        // display number. The live API returns 30 episodes per slice and ignores
        // limit=100. Its unpaged response is the newest slice; page=1 is the
        // next older slice, so walk directly to the likely page instead of
        // spending the 25s provider deadline scanning from page 1.
        fun episodeIdIn(json: JSONObject?): String? {
            val episodes = json?.let { arrayUnder(it, "episode", "episodes", "list") } ?: return null
            for (index in 0 until episodes.length()) {
                val episode = episodes.optJSONObject(index) ?: continue
                val id = string(episode, "id") ?: continue
                val number = string(episode, "index", "episode", "number")
                    ?.toDoubleOrNull()?.toInt() ?: continue
                if (number == episodeNumber) return id
            }
            return null
        }

        val latest = api("3/2/movie/episode/$animeId") ?: return null
        episodeIdIn(latest)?.let { return it }

        val latestEpisodes = arrayUnder(latest, "episode", "episodes", "list")
        val latestNumber = (0 until latestEpisodes.length())
            .mapNotNull { index ->
                string(latestEpisodes.optJSONObject(index), "index", "episode", "number")
                    ?.toDoubleOrNull()?.toInt()
            }.maxOrNull() ?: return null

        val estimatedPage = ((latestNumber - episodeNumber).coerceAtLeast(0) /
            EPISODES_PER_PAGE).coerceAtLeast(1)
        // Neighbor pages absorb occasional numbering gaps and boundary shifts
        // without turning a single episode lookup into dozens of serial calls.
        val pages = listOf(estimatedPage, estimatedPage - 1, estimatedPage + 1,
            estimatedPage - 2, estimatedPage + 2).filter { it >= 1 }.distinct()
        for (page in pages) {
            val json = api(
                "3/2/movie/episode/$animeId",
                mapOf("page" to page.toString())
            ) ?: continue
            episodeIdIn(json)?.let { return it }
        }
        return null
    }

    private suspend fun loadStreams(episodeId: String, episodeNumber: Int): List<StreamRef> {
        // The API has returned episode records keyed by both database id and
        // display index across versions. Probe the canonical episode id first,
        // then the requested index if the first response has no server list.
        val roots = listOf(episodeId, episodeNumber.toString()).distinct()
            .mapNotNull { id -> api("3/2/episode/streamnew/$id") }
        val servers = roots.asSequence()
            .mapNotNull { it.optJSONObject("data")?.optJSONArray("server") }
            .firstOrNull { it.length() > 0 }
            ?: roots.firstNotNullOfOrNull { it.optJSONObject("data")?.optJSONArray("server") }
            ?: return emptyList()
        val directStreams = mutableListOf<StreamRef>()
        val resolverCandidates = mutableListOf<Pair<String, Int?>>()
        val seen = mutableSetOf<String>()

        // Collect direct media first so slow embed resolution can never discard
        // already usable direct candidates.
        for (index in 0 until servers.length()) {
            val server = servers.optJSONObject(index) ?: continue
            val rawUrl = string(server, "link", "url") ?: continue
            val url = fullUrl(rawUrl) ?: continue
            if (!seen.add(url)) continue
            val quality = string(server, "quality")?.let(::parseQuality)
            val serverType = string(server, "type").orEmpty()
            if (isDirect(url, serverType)) {
                directStreams += StreamRef(
                    providerId = PROVIDER_ID,
                    url = url,
                    quality = quality,
                    type = mediaType(url),
                    headers = STREAM_HEADERS,
                    referer = apiBase
                )
            } else {
                resolverCandidates += url to quality
            }
        }

        if (resolverCandidates.isEmpty()) return directStreams.distinctBy { it.url }
        val resolved = withTimeoutOrNull(RESOLUTION_BUDGET_MS) {
            coroutineScope {
                resolverCandidates.map { (url, quality) ->
                    async { resolveCandidate(url, quality) }
                }.awaitAll().flatten()
            }
        }.orEmpty()
        return (directStreams + resolved).distinctBy { it.url }
    }

    private suspend fun resolveCandidate(url: String, quality: Int?): List<StreamRef> {
        // Match the runtime provider contract: resolve an API-provided embed to
        // a media URL, but stop at StreamRef. Media3 playback is not part of this adapter.
        val queue = ArrayDeque<Triple<String, String, Int>>()
        val visited = linkedSetOf<String>()
        queue.addLast(Triple(url, apiBase, 0))

        while (queue.isNotEmpty() && visited.size < MAX_RESOLUTION_URLS) {
            val (candidateUrl, referer, depth) = queue.removeFirst()
            // API/page-derived embeds are untrusted; block obvious local targets before fetching.
            if (!isSafeResolutionUrl(candidateUrl)) continue
            if (!visited.add(candidateUrl)) continue

            if (isDirect(candidateUrl, "")) {
                return listOf(
                    StreamRef(
                        providerId = PROVIDER_ID,
                        url = candidateUrl,
                        quality = quality ?: parseQuality(candidateUrl),
                        type = mediaType(candidateUrl),
                        headers = STREAM_HEADERS,
                        referer = referer
                    )
                )
            }

            for (extractor in router.resolveAll(candidateUrl)) {
                val extracted = try {
                    withTimeoutOrNull(EXTRACTOR_TIMEOUT_MS) {
                        extractor.extract(
                            StreamixExtractorRequest(
                                url = candidateUrl,
                                providerId = PROVIDER_ID,
                                referer = referer,
                                headers = STREAM_HEADERS,
                                quality = quality
                            )
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    null
                } ?: continue

                val subtitles = extracted.subtitles.map {
                    SubtitleRef(it.url, it.language, it.headers, it.referer ?: referer)
                }
                val streams = extracted.streams.map { stream ->
                    StreamRef(
                        providerId = PROVIDER_ID,
                        url = stream.url,
                        quality = stream.quality ?: quality,
                        type = stream.type,
                        language = stream.language,
                        subtitleLanguage = stream.subtitleLanguage,
                        headers = stream.headers.ifEmpty { STREAM_HEADERS },
                        referer = stream.referer ?: referer,
                        subtitles = subtitles
                    )
                }
                if (streams.isNotEmpty()) return streams
            }

            if (depth >= MAX_RESOLUTION_DEPTH) continue
            val response = try {
                http.get(
                    candidateUrl,
                    headers = BROWSER_HEADERS,
                    referer = referer,
                    timeoutMs = REQUEST_TIMEOUT_MS
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                continue
            }
            if (response.code !in 200..399) continue

            val discovered = linkedSetOf<String>()
            response.document.select("iframe[src],iframe[data-src],video[src],source[src],embed[src],a[href]").forEach { element ->
                val raw = element.attr("src")
                    .ifBlank { element.attr("data-src") }
                    .ifBlank { element.attr("href") }
                if (raw.isNotBlank()) {
                    runCatching { java.net.URI(candidateUrl).resolve(raw).toString() }
                        .getOrNull()?.let(discovered::add)
                }
            }
            Regex("""https?://[^\s"'<>]+""").findAll(response.text).forEach { match ->
                discovered += match.value.trimEnd(')', ',', ';', '\\', '"', '\'')
            }
            discovered.asSequence()
                .filter { it != candidateUrl && it !in visited }
                .filter { next ->
                    isDirect(next, "") || router.resolveAll(next).isNotEmpty() ||
                        listOf("embed", "player", "stream", "video", "watch").any { next.contains(it, true) }
                }
                .take(MAX_RESOLUTION_URLS - visited.size)
                .forEach { next -> queue.addLast(Triple(next, candidateUrl, depth + 1)) }
        }
        return emptyList()
    }

    private suspend fun api(path: String, params: Map<String, String> = emptyMap()): JSONObject? {
        val bases = listOf(apiBase, API_BASE, ALT_API_BASE).distinct()
        for (base in bases) {
            val json = requestJson(base, path, params) ?: continue
            if (json.optBoolean("error", false)) continue
            apiBase = base
            return json
        }

        val replacement = resolveApiBase() ?: return null
        if (replacement in bases) return null
        val json = requestJson(replacement, path, params) ?: return null
        if (json.optBoolean("error", false)) return null
        apiBase = replacement
        return json
    }

    private suspend fun requestJson(
        base: String,
        path: String,
        params: Map<String, String> = emptyMap()
    ): JSONObject? {
        val allParams = linkedMapOf(
            "id_user" to "0",
            "key_client" to "guest",
            "apk_ver" to APK_VERSION
        ).apply { putAll(params) }
        val query = allParams.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        val url = "${base.trimEnd('/')}/${path.trimStart('/')}?$query"
        val response = try {
            http.get(url, headers = API_HEADERS, timeoutMs = REQUEST_TIMEOUT_MS)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            return null
        }
        if (response.code !in 200..299 || !response.text.trimStart().startsWith("{")) return null
        return try {
            JSONObject(response.text)
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun resolveApiBase(): String? {
        val url = "$GATE_URL/data/setup/data?id_user=0&key_client=guest&apk_ver=$APK_VERSION"
        val response = try {
            http.get(url, headers = API_HEADERS, timeoutMs = REQUEST_TIMEOUT_MS)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            return null
        }
        if (response.code !in 200..299) return null
        val raw = try {
            JSONObject(response.text)
                .optJSONObject("data")
                ?.optJSONObject("domain_api")
                ?.opt("value")
                ?.toString()
        } catch (_: Throwable) {
            null
        }?.trim()?.trimEnd('/') ?: return null
        if (raw.isBlank() || raw.equals("null", true)) return null
        val candidate = if (raw.contains("://")) raw else "https://$raw"
        return validateApiBase(candidate)
    }

    /**
     * The setup endpoint is remote-controlled configuration. Accept only a
     * well-formed HTTPS origin; do not let it switch the API client to HTTP,
     * embed credentials, or obvious local/private literal destinations.
     *
     * This is URL-level validation only. DNS pinning and redirect enforcement
     * belong at the transport boundary and remain a separate security task.
     */
    private fun validateApiBase(raw: String): String? {
        return try {
            val uri = URI(raw.trim()).normalize()
            val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.lowercase(Locale.ROOT)
                ?: return null
            if (!uri.scheme.equals("https", true) ||
                uri.rawUserInfo != null ||
                (uri.port != -1 && uri.port != 443) ||
                uri.rawQuery != null ||
                uri.rawFragment != null ||
                (uri.rawPath.isNotEmpty() && uri.rawPath != "/") ||
                host == "localhost" ||
                host.endsWith(".localhost") ||
                host.endsWith(".local") ||
                (!host.contains(".") && !host.contains(":"))
            ) return null

            // Resolve only syntactically numeric IP literals here. Do not perform a
            // second DNS lookup for hostnames: that would neither pin nor secure the
            // later connection and could create a false sense of SSRF protection.
            if (host.matches(Regex("[0-9.]+")) || host.contains(":")) {
                val address = InetAddress.getByName(host)
                if (address.isAnyLocalAddress || address.isLoopbackAddress ||
                    address.isLinkLocalAddress || address.isSiteLocalAddress ||
                    address.isMulticastAddress
                ) return null
            }
            "https://" + uri.rawAuthority
        } catch (_: Exception) {
            null
        }
    }

    private data class MovieCandidate(val id: String, val title: String)

    private fun parseMovies(root: JSONObject): List<MovieCandidate> {
        val array = arrayUnder(root, "movie", "movies", "list", "items", "results")
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = string(item, "id", "id_movie") ?: continue
                val title = string(item, "title", "movie_title") ?: continue
                add(MovieCandidate(id, title))
            }
        }
    }

    private fun arrayUnder(root: JSONObject, vararg keys: String): JSONArray {
        val data = root.opt("data")
        if (data is JSONArray) return data
        if (data is JSONObject) {
            for (key in keys) data.optJSONArray(key)?.let { return it }
            val names = data.keys()
            while (names.hasNext()) {
                val array = data.optJSONArray(names.next()) ?: continue
                if (array.length() > 0 && array.opt(0) is JSONObject) return array
            }
        }
        for (key in keys) root.optJSONArray(key)?.let { return it }
        return JSONArray()
    }

    private fun string(obj: JSONObject?, vararg keys: String): String? {
        if (obj == null) return null
        for (key in keys) {
            val value = obj.opt(key)?.toString()?.trim()
            if (!value.isNullOrBlank() && !value.equals("null", true)) return value
        }
        return null
    }

    private fun isSafeResolutionUrl(raw: String): Boolean {
        return try {
            val uri = URI(raw.trim()).normalize()
            val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.lowercase(Locale.ROOT) ?: return false
            if (!(uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) || uri.rawUserInfo != null ||
                host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") ||
                (!host.contains(".") && !host.contains(":"))
            ) return false
            if (host.matches(Regex("[0-9.]+")) || host.contains(":")) {
                val address = InetAddress.getByName(host)
                if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                    address.isSiteLocalAddress || address.isMulticastAddress) return false
            }
            true
        } catch (_: Exception) { false }
    }
    private fun fullUrl(raw: String): String? {
        val value = raw.trim()
        if (value.isBlank() || value.equals("null", true)) return null
        return when {
            value.startsWith("//") -> "https:$value"
            value.contains("://") -> value
            else -> "${apiBase.trimEnd('/')}/${value.trimStart('/')}"
        }
    }

    private fun isDirect(url: String, type: String): Boolean =
        type.equals("direct", true) ||
            Regex("""\.(m3u8|mpd|mp4|m4v|webm)(?:[?#]|$)""", RegexOption.IGNORE_CASE).containsMatchIn(url) ||
            listOf("googlevideo", "storages.animein", "assets_xyz").any { url.contains(it, true) }

    private fun mediaType(url: String): String = when {
        url.contains(".m3u8", true) -> "m3u8"
        url.contains(".mpd", true) -> "dash"
        url.contains(".mp4", true) -> "mp4"
        url.contains(".webm", true) -> "webm"
        else -> "video"
    }

    private fun parseQuality(value: String): Int? =
        Regex("""(\d{3,4})""").find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun titleScore(expected: String, candidate: String): Double {
        val expectedNormalized = normalize(expected)
        val candidateNormalized = normalize(candidate)
        if (expectedNormalized.isBlank() || candidateNormalized.isBlank()) return 0.0
        if (expectedNormalized == candidateNormalized) return 1.0
        val expectedTokens = expectedNormalized.split(' ').filter { it.length >= 2 }.toSet()
        val candidateTokens = candidateNormalized.split(' ').filter { it.length >= 2 }.toSet()
        if (expectedTokens.isEmpty() || candidateTokens.isEmpty()) return 0.0
        val overlap = expectedTokens.intersect(candidateTokens).size.toDouble()
        val coverage = overlap / expectedTokens.size
        val jaccard = overlap / expectedTokens.union(candidateTokens).size.coerceAtLeast(1)
        return coverage * 0.7 + jaccard * 0.3
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private companion object {
        const val PROVIDER_ID = "animein-api"
        const val API_BASE = "https://xyz-api.animein.net"
        const val ALT_API_BASE = "https://api.animein.net"
        const val GATE_URL = "https://gate.nextanimelist.com"
        const val APK_VERSION = "5.2.2"
        const val MIN_TITLE_SCORE = 0.55
        const val TOTAL_TIMEOUT_MS = 25_000L
        const val SEARCH_WAVE_TIMEOUT_MS = 4_500L
        const val MAX_EPISODE_PAGES = 45
        const val EPISODES_PER_PAGE = 30
        const val REQUEST_TIMEOUT_MS = 3_000L
        const val EXTRACTOR_TIMEOUT_MS = 1_500L
        const val RESOLUTION_BUDGET_MS = 8_000L
        val SEARCH_PATHS = listOf("data/movie/find", "3/2/explore/movie")
        val SEARCH_KEYS = listOf("query", "q", "search", "keyword", "title", "name")
        val API_HEADERS = mapOf(
            "User-Agent" to "okhttp/4.12.0",
            "apk_ver" to APK_VERSION
        )
        val STREAM_HEADERS = mapOf("User-Agent" to "okhttp/4.12.0")
        val BROWSER_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "id-ID,id;q=0.9,en;q=0.8"
        )
        const val MAX_RESOLUTION_DEPTH = 3
        const val MAX_RESOLUTION_URLS = 24
    }
}
