package streamix.catalog

import streamix.api.StreamRef
import streamix.api.SubtitleRef
import streamix.core.StreamixExtractorRegistry
import streamix.core.StreamixExtractorRequest
import streamix.core.StreamixExtractorRouter
import streamix.core.StreamixHttp
import java.net.InetAddress
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.CancellationException

class CatalogService(
    private val repository: CatalogRepository,
    private val extractors: StreamixExtractorRegistry,
    private val resolvers: CatalogResolverRegistry = CatalogResolverRegistry(),
    private val catalogHttp: StreamixHttp = CatalogSafeStreamixHttp()
) {
    private val extractorRouter = StreamixExtractorRouter(extractors)
    private val directHttp = CatalogPinnedStreamixHttp()

    fun episodes(anilistId: Long): List<CatalogEpisode> = repository.listEpisodes(anilistId)

    fun upsertSource(source: EpisodeSource): EpisodeSource {
        val existing = source.id.takeIf { it.isNotBlank() }
            ?.let { id -> repository.listEpisodes(source.anilistId).flatMap { it.sources }.firstOrNull { it.id == id } }
        validateSource(source)
        val safeId = when {
            source.id.isBlank() -> UUID.randomUUID().toString()
            existing == null -> UUID.randomUUID().toString()
            else -> source.id
        }
        return repository.upsertSource(
            source.copy(
                id = safeId,
                health = existing?.health ?: source.health
            )
        )
    }

    fun bulkUpsertSources(sources: List<EpisodeSource>): List<EpisodeSource> {
        if (sources.isEmpty()) return emptyList()
        val existingById = sources.asSequence()
            .map { it.anilistId }
            .distinct()
            .flatMap { repository.listEpisodes(it).asSequence() }
            .flatMap { it.sources.asSequence() }
            .associateBy { it.id }
        val importedIds = mutableSetOf<String>()
        val prepared = sources.map { source ->
            validateSource(source)
            val requestedId = source.id.trim()
            val id = when {
                requestedId.isBlank() -> UUID.randomUUID().toString()
                requestedId in importedIds -> throw IllegalArgumentException("duplicate source id in bulk import: $requestedId")
                else -> requestedId
            }
            importedIds += id
            val existing = existingById[id]
            source.copy(
                id = id,
                health = existing?.health ?: SourceHealth.UNKNOWN,
                lastCheckedAt = existing?.lastCheckedAt ?: source.lastCheckedAt,
                lastHttpStatus = existing?.lastHttpStatus ?: source.lastHttpStatus,
                lastFailureReason = existing?.lastFailureReason ?: source.lastFailureReason
            )
        }
        return repository.upsertSources(prepared)
    }

    private fun validateSource(source: EpisodeSource) {
        require(source.anilistId > 0) { "anilistId must be positive" }
        require(source.episodeNumber > 0) { "episodeNumber must be positive" }
        require(source.sourceUrl.length <= 4096) { "sourceUrl is too long" }
        require(source.id.length <= 128) { "source id is too long" }
        require(source.headers.size <= 32) { "too many source headers" }
        require(source.headers.keys.all { it.length in 1..128 && !it.contains('\r') && !it.contains('\n') }) {
            "invalid source header name"
        }
        require(source.headers.values.all { it.length <= 4096 && !it.contains('\r') && !it.contains('\n') }) {
            "invalid source header value"
        }
        require(source.referer?.length?.let { it <= 4096 } != false) { "referer is too long" }
        require(source.quality == null || source.quality in 1..10_000) { "quality is invalid" }
        validateSourceUrl(source.sourceUrl)
    }

    /**
     * Catalog fallback is source-tier first, then quality descending within
     * that tier. Resolution is not capped at 720p: 2160p/4K, 1440p, 1080p,
     * 720p, 480p, 360p, and any other known numeric quality are supported.
     *
     * Example:
     *   DIRECT 2160p -> DIRECT 1080p -> DIRECT 720p -> ...
     *   -> RESOLVER 2160p -> RESOLVER 1080p -> RESOLVER 720p -> ...
     *   -> EXTRACTOR 2160p -> EXTRACTOR 1080p -> EXTRACTOR 720p -> ...
     *
     * Within each type/resolution bucket, mirrors are tried by priority then
     * id. Exhaust a bucket before moving on; return the first non-empty result
     * without mixing streams from lower-priority buckets.
     */
    suspend fun streams(anilistId: Long, episodeNumber: Int, failedSourceIds: Set<String> = emptySet()): List<StreamRef> {
        val sources = repository.getEpisode(anilistId, episodeNumber)?.sources.orEmpty()
            .filter { it.enabled && it.id !in failedSourceIds }

        val byTier = sources.groupBy { it.type }
        for (type in SOURCE_TIER_ORDER) {
            val resolutionBuckets = byTier[type].orEmpty()
                .groupBy(::resolutionBucket)
                .entries
                .sortedByDescending { it.key ?: Int.MIN_VALUE }

            for ((_, bucketSources) in resolutionBuckets) {
                val orderedSources = bucketSources
                    .sortedWith(compareByDescending<EpisodeSource> { it.priority }.thenBy { it.id })

                for (source in orderedSources) {
                    val candidate = resolve(source)
                    if (candidate.isNotEmpty()) {
                        return candidate.distinctBy { it.url }
                    }
                }
            }
        }

        return emptyList()
    }

    suspend fun checkSource(
        anilistId: Long,
        episodeNumber: Int,
        sourceId: String
    ): EpisodeSource? {
        val source = repository.getEpisode(anilistId, episodeNumber)
            ?.sources?.firstOrNull { it.id == sourceId }
            ?: return null

        val now = System.currentTimeMillis()
        return try {
            when (source.type) {
                SourceType.DIRECT -> {
                    val result = probeDirect(source)
                    repository.updateSourceHealth(
                        anilistId, episodeNumber, sourceId, result.first, now, result.second, result.third
                    )
                }
                SourceType.RESOLVER -> {
                    val result = resolveDetailed(source)
                    val health = when {
                        result.streams.isNotEmpty() -> SourceHealth.STREAMING
                        result.httpStatus in setOf(401, 403, 404, 410) -> SourceHealth.EXPIRED
                        else -> SourceHealth.FAILED
                    }
                    repository.updateSourceHealth(
                        anilistId,
                        episodeNumber,
                        sourceId,
                        health,
                        now,
                        result.httpStatus,
                        result.failureReason ?: if (result.streams.isEmpty()) "resolver returned no streams" else null
                    )
                }
                SourceType.EXTRACTOR -> {
                    val streams = resolve(source)
                    repository.updateSourceHealth(
                        anilistId, episodeNumber, sourceId,
                        if (streams.isNotEmpty()) SourceHealth.STREAMING else SourceHealth.FAILED,
                        now,
                        null,
                        if (streams.isEmpty()) "extractor returned no streams" else null
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            repository.updateSourceHealth(
                anilistId, episodeNumber, sourceId,
                SourceHealth.FAILED, now, null, error.message?.take(512)
            )
        }
    }

    fun setSourceEnabled(
        anilistId: Long,
        episodeNumber: Int,
        sourceId: String,
        enabled: Boolean
    ): EpisodeSource? {
        val source = repository.getEpisode(anilistId, episodeNumber)
            ?.sources?.firstOrNull { it.id == sourceId }
            ?: return null
        return repository.upsertSource(source.copy(enabled = enabled))
    }

    fun deleteSource(anilistId: Long, episodeNumber: Int, sourceId: String): Boolean =
        repository.deleteSource(anilistId, episodeNumber, sourceId)

    private data class ResolutionOutcome(
        val streams: List<StreamRef> = emptyList(),
        val httpStatus: Int? = null,
        val finalUrl: String? = null,
        val failureReason: String? = null
    )

    private suspend fun resolve(source: EpisodeSource): List<StreamRef> =
        resolveDetailed(source).streams

    private suspend fun resolveDetailed(source: EpisodeSource): ResolutionOutcome {
        // Persisted legacy records may predate write-time URL validation. Revalidate
        // every source at the point of use so old local/private URLs cannot bypass it.
        try {
            validateSource(source)
        } catch (error: IllegalArgumentException) {
            return ResolutionOutcome(failureReason = error.message?.take(512) ?: "source validation failed")
        }

        if (source.type == SourceType.RESOLVER) {
            val matches = resolvers.resolveAll(source.sourceUrl)
            var lastFailure: ResolutionOutcome? = null
            for (resolver in matches) {
                val resolved = try {
                    resolver.resolve(source)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    lastFailure = ResolutionOutcome(failureReason = error.message?.take(512))
                    continue
                }
                if (resolved.streams.isNotEmpty()) {
                    // Resolver output is untrusted too: validate every media URL before
                    // returning it to clients. A trusted page host must not be able to
                    // smuggle a private/local playback target into the catalog response.
                    val safeStreams = resolved.streams.filter { stream ->
                        runCatching { validateSourceUrl(stream.url) }.isSuccess
                    }
                    if (safeStreams.isNotEmpty()) {
                        return ResolutionOutcome(
                            streams = safeStreams.map { stream ->
                                stream.copy(
                                    providerId = "catalog:" + source.id,
                                    quality = stream.quality ?: source.quality ?: resolutionBucket(source),
                                    language = stream.language ?: source.language,
                                    subtitleLanguage = stream.subtitleLanguage ?: source.subtitleLanguage,
                                    headers = safePlaybackHeaders(source, stream),
                                    referer = safeReferer(source, stream.url, stream.referer),
                                    subtitles = stream.subtitles.filter { subtitle ->
                                        runCatching { validateSourceUrl(subtitle.url) }.isSuccess
                                    }.map { subtitle ->
                                        subtitle.copy(
                                            headers = safePlaybackHeaders(source, subtitle.url, subtitle.headers),
                                            referer = safeReferer(source, subtitle.url, subtitle.referer)
                                        )
                                    }
                                )
                            },
                            httpStatus = resolved.httpStatus,
                            finalUrl = resolved.finalUrl
                        )
                    }
                    lastFailure = ResolutionOutcome(
                        httpStatus = resolved.httpStatus,
                        finalUrl = resolved.finalUrl,
                        failureReason = "resolver returned only rejected media URLs"
                    )
                    continue
                }
                lastFailure = ResolutionOutcome(
                    httpStatus = resolved.httpStatus,
                    finalUrl = resolved.finalUrl,
                    failureReason = resolved.failureReason
                )
            }
            return lastFailure ?: ResolutionOutcome(failureReason = "no catalog resolver matched source host")
        }
        if (source.type == SourceType.DIRECT) {
            return ResolutionOutcome(
                streams = listOf(
                    StreamRef(
                        "catalog:" + source.id,
                        CatalogDirectLinkNormalizer.normalize(source.sourceUrl),
                        source.quality ?: resolutionBucket(source),
                        when {
                            CatalogDirectLinkNormalizer.normalize(source.sourceUrl).substringBefore('?').endsWith(".m3u8", true) -> "m3u8"
                            CatalogDirectLinkNormalizer.normalize(source.sourceUrl).substringBefore('?').endsWith(".mpd", true) -> "dash"
                            CatalogDirectLinkNormalizer.normalize(source.sourceUrl).substringBefore('?').endsWith(".mp4", true) -> "mp4"
                            CatalogDirectLinkNormalizer.normalize(source.sourceUrl).substringBefore('?').endsWith(".webm", true) -> "webm"
                            CatalogDirectLinkNormalizer.normalize(source.sourceUrl).substringBefore('?').endsWith(".mkv", true) -> "mkv"
                            else -> "video"
                        },
                        source.language,
                        source.subtitleLanguage,
                        source.headers,
                        source.referer
                    )
                )
            )
        }

        val result = mutableListOf<StreamRef>()
        for (extractor in extractorRouter.resolveAll(source.sourceUrl)) {
            val extracted = try {
                extractor.extract(
                    StreamixExtractorRequest(
                        source.sourceUrl,
                        providerId = "catalog:" + source.id,
                        referer = source.referer,
                        headers = source.headers,
                        scopedHttp = catalogHttp
                    )
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                continue
            }

            // Extractor output is untrusted just like resolver output. Never
            // expose private/local media or subtitle targets from a Catalog source.
            val safeSubtitles = extracted.subtitles
                .map { SubtitleRef(it.url, it.language, it.headers, it.referer) }
                .filter { subtitle ->
                    runCatching { validateSourceUrl(subtitle.url) }.isSuccess
                }
                .map { subtitle ->
                    subtitle.copy(
                        headers = safePlaybackHeaders(source, subtitle.url, subtitle.headers),
                        referer = safeReferer(source, subtitle.url, subtitle.referer)
                    )
                }
            result += extracted.streams.filter { stream ->
                runCatching { validateSourceUrl(stream.url) }.isSuccess
            }.map {
                StreamRef(
                    "catalog:" + source.id,
                    it.url,
                    it.quality ?: source.quality ?: resolutionBucket(source),
                    it.type,
                    it.language ?: source.language,
                    it.subtitleLanguage ?: source.subtitleLanguage,
                    safePlaybackHeaders(source, it.url, it.headers),
                    safeReferer(source, it.url, it.referer),
                    safeSubtitles
                )
            }
            if (result.isNotEmpty()) break
        }
        return ResolutionOutcome(streams = result)
    }

    private fun resolutionBucket(source: EpisodeSource): Int? {
        source.quality?.takeIf { it > 0 }?.let { return it }
        return source.resolution
            ?.trim()
            ?.lowercase()
            ?.let { resolution ->
                when {
                    Regex("""\b8k\b""").containsMatchIn(resolution) -> 4320
                    Regex("""\b4k\b""").containsMatchIn(resolution) -> 2160
                    Regex("""\b2k\b""").containsMatchIn(resolution) -> 1440
                    Regex("""\b\d{3,4}\s*[x×]\s*(\d{3,4})\b""").find(resolution) != null ->
                        Regex("""\b\d{3,4}\s*[x×]\s*(\d{3,4})\b""").find(resolution)?.groupValues?.get(1)?.toIntOrNull()
                    Regex("""\b(\d{3,4})p\b""").containsMatchIn(resolution) ->
                        Regex("""\b(\d{3,4})p\b""").find(resolution)?.groupValues?.get(1)?.toIntOrNull()
                    else -> Regex("""(\d{3,4})""").find(resolution)?.groupValues?.get(1)?.toIntOrNull()
                }
            }
            ?.takeIf { it > 0 }
    }
    private fun probeDirect(source: EpisodeSource): Triple<SourceHealth, Int?, String?> {
        val directUrl = CatalogDirectLinkNormalizer.normalize(source.sourceUrl)

        // Use the Catalog-only pinned transport for every hop. The DNS policy is
        // enforced by OkHttp at connection time, not just by URL preflight.
        // Never forward source credentials or custom headers to a different origin.
        fun requestStatus(method: String, range: Boolean): Pair<Int?, String?> {
            var currentUrl = directUrl
            val originalUri = URI(directUrl)
            val maxRedirects = 5
            var redirects = 0

            while (true) {
                validateSourceUrl(currentUrl)
                val currentUri = URI(currentUrl)
                val sameOriginAsSource =
                    originalUri.scheme.equals(currentUri.scheme, true) &&
                    originalUri.host.equals(currentUri.host, true) &&
                    effectivePort(originalUri) == effectivePort(currentUri)
                val headers = if (sameOriginAsSource) source.headers else emptyMap()
                val referer = source.referer.takeIf { sameOriginAsSource }

                val response = try {
                    if (method == "HEAD") {
                        directHttp.headProbe(currentUrl, headers, referer, emptyMap(), 10_000)
                    } else {
                        directHttp.rangeProbe(currentUrl, headers, referer, emptyMap(), 10_000)
                    }
                } catch (error: Exception) {
                    return null to (error.message?.take(512) ?: "direct probe failed")
                }
                val code = response.code
                val location = response.headers.entries
                    .firstOrNull { it.key.equals("location", true) }
                    ?.value?.firstOrNull()

                if (code !in REDIRECT_STATUS_CODES) return code to null
                if (redirects >= maxRedirects) return code to "too many redirects"
                if (location.isNullOrBlank()) return code to "redirect response missing Location"

                val nextUrl = runCatching { URI(currentUrl).resolve(location).toString() }.getOrNull()
                    ?: return code to "invalid redirect Location"
                try {
                    validateSourceUrl(nextUrl)
                } catch (_: IllegalArgumentException) {
                    return code to "redirect target rejected by URL validation"
                }
                currentUrl = nextUrl
                redirects++
            }
        }

        val (headCode, headFailure) = requestStatus("HEAD", false)
        if (headFailure != null) return Triple(SourceHealth.FAILED, headCode, headFailure)
        if (headCode != null && headCode in 200..299) {
            return Triple(SourceHealth.STREAMING, headCode, null)
        }
        if (headCode == 401 || headCode == 403 || headCode == 404 || headCode == 410) {
            return Triple(SourceHealth.EXPIRED, headCode, "HTTP $headCode")
        }

        val (getCode, getFailure) = requestStatus("GET", true)
        if (getFailure != null) return Triple(SourceHealth.FAILED, getCode, getFailure)
        return when {
            getCode != null && getCode in 200..299 -> Triple(SourceHealth.STREAMING, getCode, null)
            getCode == 401 || getCode == 403 || getCode == 404 || getCode == 410 ->
                Triple(SourceHealth.EXPIRED, getCode, "HTTP $getCode")
            getCode != null -> Triple(SourceHealth.FAILED, getCode, "HTTP $getCode")
            else -> Triple(SourceHealth.FAILED, null, "no HTTP response")
        }
    }

    private fun safePlaybackHeaders(source: EpisodeSource, stream: StreamRef): Map<String, String> =
        safePlaybackHeaders(source, stream.url, stream.headers)

    private fun safePlaybackHeaders(
        source: EpisodeSource,
        targetUrl: String,
        suppliedHeaders: Map<String, String>
    ): Map<String, String> {
        // Header names are case-insensitive. Collapse duplicates already present in
        // either map and let the last supplied value override the source value.
        val mergedHeaders = linkedMapOf<String, String>()
        (source.headers.entries + suppliedHeaders.entries).forEach { (name, value) ->
            mergedHeaders.keys.filter { it.equals(name, true) }.forEach(mergedHeaders::remove)
            mergedHeaders[name] = value
        }
        val headers = mergedHeaders
        if (sameOrigin(source.sourceUrl, targetUrl)) return headers
        return headers.filterKeys { key ->
            !key.equals("Authorization", true) &&
                !key.equals("Proxy-Authorization", true) &&
                !key.equals("Cookie", true) &&
                !key.equals("Host", true) &&
                !key.equals("Origin", true) &&
                !key.equals("Referer", true)
        }
    }

    private fun safeReferer(source: EpisodeSource, targetUrl: String, suppliedReferer: String?): String? {
        if (!sameOrigin(source.sourceUrl, targetUrl)) return null
        return suppliedReferer ?: source.referer
    }

    private fun sameOrigin(firstUrl: String, secondUrl: String): Boolean = runCatching {
        val first = URI(firstUrl)
        val second = URI(secondUrl)
        first.scheme.equals(second.scheme, true) &&
            first.host.equals(second.host, true) &&
            effectivePort(first) == effectivePort(second)
    }.getOrDefault(false)

    private fun effectivePort(uri: URI): Int =
        if (uri.port >= 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80

    private fun validateSourceUrl(rawUrl: String) {
        val uri = try {
            URI(rawUrl)
        } catch (_: Exception) {
            throw IllegalArgumentException("sourceUrl is invalid")
        }
        require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
            "sourceUrl must be http(s)"
        }
        require(uri.userInfo == null) { "sourceUrl userinfo is not allowed" }
        require(uri.port == -1 || uri.port in 1..65535) { "sourceUrl port is invalid" }
        val host = uri.host?.lowercase()
            ?: throw IllegalArgumentException("sourceUrl host is required")
        require(host != "localhost" && !host.endsWith(".localhost") && !host.endsWith(".local")) {
            "sourceUrl host is not allowed"
        }
        val addresses = try {
            InetAddress.getAllByName(host).toList()
        } catch (_: Exception) {
            throw IllegalArgumentException("sourceUrl host could not be resolved")
        }
        require(addresses.isNotEmpty()) { "sourceUrl host could not be resolved" }
        require(addresses.none { isPrivateOrLocal(it) }) {
            "sourceUrl host resolves to a private/local address"
        }
    }

    private fun isPrivateOrLocal(address: InetAddress): Boolean =
        CatalogPublicDns().isPrivateOrLocal(address)

    private companion object {
        val SOURCE_TIER_ORDER = listOf(SourceType.DIRECT, SourceType.RESOLVER, SourceType.EXTRACTOR)
        val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
    }
}
