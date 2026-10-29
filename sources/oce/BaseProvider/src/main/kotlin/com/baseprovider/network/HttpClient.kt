package com.baseprovider.network

import com.baseprovider.cache.ExpiringCache
import com.baseprovider.config.ProviderConfig
import com.baseprovider.streamix.StreamixLogger
import com.baseprovider.streamix.StreamixRuntime
import org.jsoup.Jsoup
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import streamix.core.StreamixHttpContext
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

import java.net.URI

private const val DEFAULT_TIMEOUT = 15000L
// Budget global per fetchDocument: waktu total untuk semua mirror + varian UA.
// Mencegah worst-case stall (~detik x UA x retry) saat host dead/lambat.
private const val GLOBAL_TIMEOUT = 25000L

private val streamixHttp get() = StreamixRuntime.http
private val streamixCloudflareResolver get() = StreamixRuntime.cloudflareResolver

/** Prefer the Catalog invocation's transport without changing ordinary provider runtime behavior. */
private suspend fun requestHttp(): streamix.core.StreamixHttp =
    currentCoroutineContext()[StreamixHttpContext]?.http
        ?: requireNotNull(streamixHttp) { "StreamixHttp is not installed" }

/**
 * Provider-global cookies, WebView session state, circuit breakers, and throttles
 * are outside the Catalog-pinned transport. Scoped Catalog calls must not share them.
 */
internal suspend fun shouldUseGlobalProviderState(): Boolean =
    currentCoroutineContext()[StreamixHttpContext] == null

internal suspend fun shouldUseGlobalCloudflareResolver(): Boolean =
    shouldUseGlobalProviderState()

/** Legacy document caches belong to ordinary provider execution, not Catalog scope. */
internal suspend fun shouldUseProviderDocumentCache(): Boolean =
    shouldUseGlobalProviderState()

private val FALLBACK_UA_POOL = listOf(
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:120.0) Gecko/20100101 Firefox/120.0",
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"
)

private fun resolveUaVariants(config: ProviderConfig): List<String> {
    val fromConfig = config.uaPool.filter { it.isNotBlank() }
    if (fromConfig.isNotEmpty()) return fromConfig.distinct()
    val configured = config.globalHeaders["User-Agent"]?.takeIf { it.isNotBlank() }
    return buildList {
        if (configured != null) add(configured)
        addAll(FALLBACK_UA_POOL)
    }.distinct().take(4)
}

private fun Map<String, String>.withUa(ua: String): Map<String, String> =
    if (ua.isBlank() || this["User-Agent"] == ua) this
    else this + ("User-Agent" to ua)

suspend fun fetchDocument(
    url: String,
    config: ProviderConfig,
    referer: String? = null,
    skipCache: Boolean = false,
    htmlCache: ExpiringCache<Document>? = null
): Document {
    val useGlobalProviderState = shouldUseGlobalProviderState()
    val fallbackUrls = resolveFallbackUrls(url, config)
    val uaVariants = resolveUaVariants(config)
    var lastError: Exception? = null

    // Budget global: total waktu utk semua mirror + varian UA. Host dead/lambat
    // tidak boleh membuat fetchDocument menggantung puluhan detik.
    val result = try {
        withTimeout<Document>(GLOBAL_TIMEOUT) {
            for ((attemptUrl, host) in fallbackUrls) {
                if (useGlobalProviderState && !skipCache) {
                    htmlCache?.get(attemptUrl)?.let { return@withTimeout it }
                }
                if (useGlobalProviderState && host.isNotBlank() && HostCircuitBreaker.isOpen(host)) continue
                var hostFailed = false
                // Track per host: host hanya dihukum (breaker/throttle) SEKALI per
                // fetchDocument dan HANYA untuk kegagalan level host (429/5xx/CF/connect),
                // bukan kegagalan level konten (404/410/451).
                var shouldPenalizeHost = false
                // Kalau Cloudflare sudah di-solve via WebView utk host ini, cf_clearance
                // terikat ke UA WebView - pakai UA itu dulu sebelum pool.
                hostLoop@ while (true) {
                    val webViewUa = if (useGlobalProviderState) {
                        WebViewCloudflareSolver.userAgentFor(host)
                    } else {
                        null
                    }
                    val uaOrder = buildList {
                        if (!webViewUa.isNullOrBlank()) add(webViewUa)
                        for (u in uaVariants) if (u != webViewUa) add(u)
                    }
                    for (ua in uaOrder) {
                        if (useGlobalProviderState && host.isNotBlank() && HostCircuitBreaker.isOpen(host)) break
                        val headers = config.globalHeaders.withUa(ua)
                        try {
                            val res = executeWithRetry {
                                if (useGlobalProviderState) rateLimitDelay(attemptUrl)
                                // StreamixHttp owns request timeout and status handling.
                                val r = requestHttp().get(
                                    url = attemptUrl,
                                    timeoutMs = DEFAULT_TIMEOUT,
                                    headers = headers,
                                    referer = referer ?: googleReferer(config),
                                    // Catalog's pinned transport owns its cookie jar. Do not
                                    // import or mutate the provider-global jar for scoped calls.
                                    cookies = if (useGlobalProviderState) {
                                        HostCookieJar.getFor(attemptUrl)
                                    } else {
                                        emptyMap()
                                    }
                                )
                                // Check status explicitly so retry policy sees 429/5xx.
                                if (r.code >= 400) {
                                    val retryAfter = parseRetryAfter(r.headers["Retry-After"]?.firstOrNull())
                                    val body = r.text
                                    throw HttpStatusException(
                                        r.code,
                                        retryAfter,
                                        "HTTP ${r.code} on $attemptUrl",
                                        body
                                    )
                                }
                                r
                            }
                            if (useGlobalProviderState) {
                                HostCookieJar.update(attemptUrl, res.cookies)
                            }
                            val doc = res.document
                            // Preserve the final response URL as Jsoup base URI.
                            doc.setBaseUri(res.url)
                            if (useGlobalProviderState && !skipCache) {
                                htmlCache?.put(attemptUrl, doc)
                            }
                            if (host.isNotBlank()) {
                                if (useGlobalProviderState) {
                                    HostCircuitBreaker.reportSuccess(host)
                                    SmartThrottle.reportSuccess(host)
                                }
                            }
                            return@withTimeout doc
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            lastError = e
                            hostFailed = true
                            when {
e is HttpStatusException -> {
                                     val msg = e.message.orEmpty()
                                     val body = e.body
                                     when {
                                         CLOUDFLARE_HTTP.containsMatchIn(msg) || CLOUDFLARE_HTTP.containsMatchIn(body) -> {
                                            shouldPenalizeHost = true
                                            // 403 CF: coba solve challenge via WebView (otomatis, tanpa
                                            // config). Kalau sukses, cf_clearance + UA WebView tersimpan -
                                            // restart sub-loop supaya request ulang diprioritaskan pakai UA WebView.
                                            if (useGlobalProviderState && WebViewCloudflareSolver.shouldAttempt(host)) {
                                                StreamixLogger.d("OCE", "fetchDocument CF/403 on $attemptUrl, trying WebView CF solver")
                                                val solved = WebViewCloudflareSolver.trySolve(attemptUrl, referer ?: config.mainUrl)
                                                StreamixLogger.d("OCE", "WebView CF solver for $attemptUrl: ${if (solved) "solved" else "failed"}")
                                                if (solved) continue@hostLoop
                                            }
                                            // Rotasi UA berikutnya, lalu mirror berikutnya.
                                            StreamixLogger.d("OCE", "fetchDocument CF/403 on $attemptUrl (UA=$ua), trying next variant/host")
                                            continue
                                        }
e.code == 429 -> {
                                             // Rate limit: hormati Retry-After via SmartThrottle
                                             shouldPenalizeHost = true
                                             if (useGlobalProviderState) SmartThrottle.reportRetryAfter(host, e.retryAfterSeconds ?: 0L)
                                             StreamixLogger.d("OCE", "fetchDocument 429 on $attemptUrl, trying next variant/host")
                                             continue
                                         }
e.code == 403 -> {
                                              // Plain 403 (geo-block, IP ban, etc.): try next UA variant
                                              // JANGAN set retryAfter — akan delay percobaan UA berikutnya via SmartThrottle.wait()
                                              // Failure di-report setelah SEMUA UA habis lewat reportFailure di akhir hostLoop.
                                              shouldPenalizeHost = true
                                              StreamixLogger.d("OCE", "fetchDocument 403 on $attemptUrl, trying next variant/host")
                                              continue
                                          }
                                         e.code == 404 || e.code == 410 || e.code == 451 -> {
                                             // Geo-block 404 / konten hilang: BUKAN kegagalan host,
                                             // jangan hukumi breaker/throttle. Coba mirror berikutnya.
                                             StreamixLogger.d("OCE", "fetchDocument HTTP ${e.code} on $attemptUrl, trying next host")
                                             break
                                         }
                                         e.code in 500..599 -> {
                                             // Server error = kegagalan level host
                                             shouldPenalizeHost = true
                                             StreamixLogger.d("OCE", "fetchDocument HTTP ${e.code} on $attemptUrl, trying next host")
                                             break
                                         }
                                         else -> throw e
                                    }
                                }
                                else -> {
                                    val msg = e.message ?: ""
                                    if (NON_RETRYABLE_HTTP.containsMatchIn(msg)) throw e
                                    if (CLOUDFLARE_HTTP.containsMatchIn(msg)) {
                                        shouldPenalizeHost = true
                                        StreamixLogger.d("OCE", "fetchDocument CF/403 on $attemptUrl (UA=$ua), trying next variant/host")
                                        continue
                                    }
                                    // Connection error / timeout = kegagalan level host
                                    shouldPenalizeHost = true
                                    break
                                }
                            }
                        }
                    }
                    break
                }
                if (useGlobalProviderState && hostFailed && host.isNotBlank() && shouldPenalizeHost) {
                    HostCircuitBreaker.reportFailure(host)
                    SmartThrottle.reportFailure(host)
                }
            }

            throw lastError ?: Exception("All mirrors failed for $url")
        }
    } catch (e: TimeoutCancellationException) {
        throw lastError ?: java.net.SocketTimeoutException(
            "Global fetch timeout for $url"
        )
    }
    return result
}

private fun googleReferer(config: ProviderConfig): String? =
    if (config.googleReferer) "https://www.google.com/" else null

/**
 * Solver Cloudflare via WebView (pola resmi Cloudstream3 `CloudflareKiller`).
 * Otomatis aktif saat halaman kena Managed Challenge / Turnstile (403 CF) —
 * tanpa config flag. WebView dimuat untuk menjalankan JS challenge hingga
 * Cloudflare me-set `cf_clearance`. Cookie hasil disimpan ke [HostCookieJar]
 * dan UA WebView dicatat per-host agar request berikutnya memakai UA yang
 * sama dengan sesi solve (cf_clearance terikat ke UA).
 */
object WebViewCloudflareSolver {
    private val solvedUserAgents = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val failedUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val FAIL_COOLDOWN_MS = 30 * 60_000L
    private const val MAX_HOSTS = 200

    fun userAgentFor(host: String): String? = solvedUserAgents[host]

    fun isSolved(host: String): Boolean = solvedUserAgents.containsKey(host)

    fun shouldAttempt(host: String): Boolean {
        if (isSolved(host)) return false
        val failCooldown = failedUntil[host] ?: 0L
        return System.currentTimeMillis() >= failCooldown
    }

    suspend fun trySolve(url: String, referer: String? = null): Boolean {
        val host = runCatching { URI(url).host }.getOrNull() ?: return false
        if (!shouldAttempt(host)) return false
        return runCatching {
            val resolver = streamixCloudflareResolver ?: return false
            val result = resolver.solve(url, referer)
            if (result.solved) {
                if (result.cookies.isNotEmpty()) HostCookieJar.update(url, result.cookies)
                result.userAgent?.let { solvedUserAgents[host] = it }
            } else {
                failedUntil[host] = System.currentTimeMillis() + FAIL_COOLDOWN_MS
            }
            capMap(failedUntil)
            capMap(solvedUserAgents)
            result.solved
        }.getOrElse { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            failedUntil[host] = System.currentTimeMillis() + FAIL_COOLDOWN_MS
            capMap(failedUntil)
            false
        }
    }

    fun reset() {
        solvedUserAgents.clear()
        failedUntil.clear()
    }

    private fun <K> capMap(map: java.util.concurrent.ConcurrentHashMap<K, *>) {
        if (map.size <= MAX_HOSTS) return
        val toRemove = map.keys.take(map.size - MAX_HOSTS)
        toRemove.forEach { map.remove(it) }
    }
}

/**
 * Cookie jar per-host in-memory (ringan). Mengumpulkan `Set-Cookie` dari
 * respon dan mengirimnya kembali pada request berikutnya ke host yang sama.
 * Inspirasi: `user_data_dir` / session Scrapling, tapi tanpa persistensi disk.
 */
object HostCookieJar {
    private val jars = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()
    // L8: cap host cookie jar — jumlah host tak terbatas selama sesi.
    private const val MAX_HOSTS = 200

    fun getFor(url: String): Map<String, String> {
        val host = runCatching { URI(url).host }.getOrNull() ?: return emptyMap()
        return jars[host] ?: emptyMap()
    }

    fun update(url: String, setCookies: Map<String, String>) {
        if (setCookies.isEmpty()) return
        val host = runCatching { URI(url).host }.getOrNull() ?: return
        jars.compute(host) { _, prev -> (prev ?: emptyMap()) + setCookies }
        if (jars.size > MAX_HOSTS) {
            val toRemove = jars.keys.take(jars.size - MAX_HOSTS)
            toRemove.forEach { jars.remove(it) }
        }
    }

    fun clear() = jars.clear()
}

private suspend fun resolveFallbackUrls(url: String,
    config: ProviderConfig): List<Pair<String, String>> {
    val originalUri = runCatching { URI(url) }
        .getOrNull() ?: return listOf(url to "")
    val host = originalUri.host ?: return listOf(url to "")
    val candidates = mutableListOf(url to host)
    val portPart = if (originalUri.port > 0 && originalUri.port != 80
        && originalUri.port != 443) ":${originalUri.port}" else ""
    val pathPart = originalUri.rawPath ?: ""
    val queryPart = if (originalUri.query !=
        null) "?${originalUri.query}" else ""
    val fragmentPart = if (originalUri.fragment != null) "#${originalUri.fragment}" else ""
    for (mirror in config.mirrorUrls) {
        val mirrorHost = runCatching { URI(mirror).host }
            .getOrNull() ?: continue
        if (mirrorHost == host) continue
        candidates.add("${originalUri.scheme}://$mirrorHost$portPart$pathPart$queryPart$fragmentPart" to mirrorHost)
    }
    return candidates
}

fun Element.selectAttr(attrNames: List<String>): String? {
    for (name in attrNames) {
        val v = attr(name)
        if (v.isNotBlank() && v != "about:blank") return v
    }
    return null
}
