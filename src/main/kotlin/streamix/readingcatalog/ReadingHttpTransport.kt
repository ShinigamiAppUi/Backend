package streamix.readingcatalog

import streamix.catalog.CatalogPublicDns
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Shared HTTP transport for reading providers, following Keiyoushi's client-centric
 * request pattern. Adapters own parsing and site-specific headers; transport owns
 * connection policy, redirects, response lifecycle, and bounded body reads.
 *
 * Redirects follow normal HTTP-client behavior, but every connection uses DNS
 * validation/pinning so private/local redirect destinations are rejected centrally.
 */
internal object ReadingHttpTransport {
    private data class Payload(val bytes: ByteArray, val finalUrl: String)

    private fun createClient(
        followRedirects: Boolean,
        connectTimeoutMillis: Int = 8_000,
        readTimeoutMillis: Int = 12_000,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .dns(CatalogPublicDns())
            .connectTimeout(connectTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(maxOf(connectTimeoutMillis + readTimeoutMillis, 1_000).toLong(), TimeUnit.MILLISECONDS)
            .followRedirects(followRedirects)
            .followSslRedirects(false)
            .build()

    private val client: OkHttpClient by lazy { createClient(followRedirects = true) }
    private val noRedirectClient: OkHttpClient by lazy { createClient(followRedirects = false) }

    private fun getPayload(
        url: String,
        headers: Map<String, String>,
        maxBytes: Int,
        followRedirects: Boolean = true,
        connectTimeoutMillis: Int = 8_000,
        readTimeoutMillis: Int = 12_000,
    ): Payload? = runCatching {
        require(maxBytes > 0)
        if (!isSafeReadingHttpUrl(url)) return@runCatching null
        val request = Request.Builder().url(url).get().apply {
            headers.forEach { (name, value) -> header(name, value) }
            if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                header("User-Agent", "ShinigamiReadingRuntime/1.0")
            }
        }.build()
        val selectedClient = if (connectTimeoutMillis == 8_000 && readTimeoutMillis == 12_000) {
            if (followRedirects) client else noRedirectClient
        } else createClient(followRedirects, connectTimeoutMillis, readTimeoutMillis)
        selectedClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body ?: return@use null
            val declaredLength = body.contentLength()
            if (declaredLength > maxBytes) return@use null
            val bytes = body.byteStream().use { readBoundedReadingResponse(it, maxBytes) }
                ?: return@use null
            Payload(bytes, response.request.url.toString())
        }
    }.getOrNull()

    fun getBytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Int = MAX_READING_HTTP_RESPONSE_BYTES,
    ): ByteArray? = getPayload(url, headers, maxBytes)?.bytes

    /**
     * Some reading APIs set a same-origin proxy cookie only after their homepage is visited.
     * Keep that cookie in a request-scoped jar and retry once after a 403; do not weaken URL/DNS policy.
     */
    fun getTextWithCookieBootstrap(
        url: String,
        bootstrapUrl: String,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Int = MAX_READING_HTTP_RESPONSE_BYTES,
    ): String? = runCatching {
        if (!isSafeReadingHttpUrl(url) || !isSafeReadingHttpUrl(bootstrapUrl) || maxBytes <= 0) return@runCatching null
        val targetHost = URI(url).host
        if (!targetHost.equals(URI(bootstrapUrl).host, ignoreCase = true)) return@runCatching null
        val cookies = mutableListOf<Cookie>()
        val jar = object : CookieJar {
            override fun saveFromResponse(url: okhttp3.HttpUrl, cookiesFromResponse: List<Cookie>) {
                synchronized(cookies) {
                    cookies.removeAll { old -> cookiesFromResponse.any { it.name == old.name && it.domain == old.domain && it.path == old.path } }
                    cookies.addAll(cookiesFromResponse)
                }
            }
            override fun loadForRequest(url: okhttp3.HttpUrl): List<Cookie> = synchronized(cookies) {
                cookies.filter { it.matches(url) }
            }
        }
        val session = OkHttpClient.Builder()
            .dns(CatalogPublicDns())
            .cookieJar(jar)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(false)
            .build()
        fun request(target: String, requestHeaders: Map<String, String>): Pair<Int, String?> {
            val request = Request.Builder().url(target).get().apply {
                requestHeaders.forEach { (name, value) -> header(name, value) }
                if (requestHeaders.keys.none { it.equals("User-Agent", ignoreCase = true) }) header("User-Agent", "ShinigamiReadingRuntime/1.0")
            }.build()
            return session.newCall(request).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null || body.contentLength() > maxBytes) {
                    response.code to null
                } else {
                    val bytes = body.byteStream().use { readBoundedReadingResponse(it, maxBytes) }
                    response.code to bytes?.toString(Charsets.UTF_8)
                }
            }
        }
        val first = request(url, headers)
        if (first.first != 403) return@runCatching first.second
        request(bootstrapUrl, mapOf("Accept" to "text/html,application/xhtml+xml"))
        request(url, headers).second
    }.getOrNull()
    fun getText(
        url: String,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Int = MAX_READING_HTTP_RESPONSE_BYTES,
        followRedirects: Boolean = true,
        connectTimeoutMillis: Int = 8_000,
        readTimeoutMillis: Int = 12_000,
    ): String? = getPayload(url, headers, maxBytes, followRedirects, connectTimeoutMillis, readTimeoutMillis)?.bytes?.toString(Charsets.UTF_8)

    fun getHtml(
        url: String,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Int = MAX_READING_HTTP_RESPONSE_BYTES,
    ): Document? = getPayload(url, headers, maxBytes)?.let { payload ->
        Jsoup.parse(ByteArrayInputStream(payload.bytes), null, payload.finalUrl)
    }
}

/** Central policy for upstream reading HTTP targets; adapters need not duplicate it. */
internal fun isSafeReadingHttpUrl(value: String): Boolean = runCatching {
    URI(value).let { uri ->
        val host = uri.host?.trimEnd('.')?.lowercase()
        val validAuthority = !host.isNullOrBlank() &&
            uri.userInfo == null &&
            (uri.port == -1 || uri.port == 443) &&
            host != "localhost" &&
            !host.endsWith(".localhost") &&
            !host.endsWith(".local")
        val literalHost = host?.removePrefix("[")?.removeSuffix("]")
        val literalAddress = literalHost?.let {
            it.contains(':') || it.matches(Regex("[0-9.]+"))
        } == true
        val publicLiteral = if (literalAddress) {
            val addresses = java.net.InetAddress.getAllByName(literalHost)
            addresses.isNotEmpty() && addresses.none(CatalogPublicDns()::isPrivateOrLocal)
        } else true
        uri.scheme.equals("https", ignoreCase = true) &&
            validAuthority &&
            publicLiteral
    }
}.getOrDefault(false)

internal fun fetchReadingHtmlDocument(
    value: String,
    headers: Map<String, String> = mapOf("Accept" to "text/html,application/xhtml+xml"),
    maxBytes: Int = MAX_READING_HTTP_RESPONSE_BYTES,
): Document? = ReadingHttpTransport.getHtml(value, headers, maxBytes)
