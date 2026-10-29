package streamix.catalog

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import streamix.core.StreamixHttp
import streamix.core.StreamixHttpProbeResponse
import streamix.core.StreamixHttpResponse

/**
 * Catalog-only transport. OkHttp calls Dns.lookup and then connects using the
 * exact addresses returned by that lookup; validation therefore applies to the
 * actual connection candidates rather than to a separate preflight lookup.
 * The shared provider runtime transport is intentionally untouched.
 */
internal class CatalogPinnedStreamixHttp(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .dns(CatalogPublicDns())
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .cookieJar(CatalogCookieJar())
        .build()
) : StreamixHttp {
    override suspend fun get(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long) =
        execute("GET", url, null, headers, referer, cookies, timeoutMs)

    override suspend fun post(url: String, body: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long) =
        execute("POST", url, body, headers, referer, cookies, timeoutMs)

    internal fun headProbe(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long): StreamixHttpProbeResponse {
        val request = requestBuilder(url, headers, referer, cookies).head().build()
        client.newBuilder().callTimeout(timeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS).build()
            .newCall(request).execute().use { response ->
                return StreamixHttpProbeResponse(
                    code = response.code,
                    url = response.request.url.toString(),
                    bytesRead = 0L,
                    eof = response.body?.contentLength() == 0L,
                    headers = response.headers.toMultimap()
                )
            }
    }

    internal fun rangeProbe(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long): StreamixHttpProbeResponse {
        val request = requestBuilder(url, headers, referer, cookies)
            .header("Range", "bytes=0-0")
            .get()
            .build()
        client.newBuilder().callTimeout(timeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS).build()
            .newCall(request).execute().use { response ->
                val body = response.body
                val bytesRead = if (body == null) 0L else body.byteStream().use { input ->
                    val buffer = ByteArray(1)
                    if (input.read(buffer) < 0) 0L else 1L
                }
                val contentLength = body?.contentLength() ?: -1L
                return StreamixHttpProbeResponse(
                    code = response.code,
                    url = response.request.url.toString(),
                    bytesRead = bytesRead,
                    eof = contentLength >= 0 && bytesRead >= contentLength,
                    headers = response.headers.toMultimap()
                )
            }
    }

    override suspend fun probe(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long, maxBytes: Long): StreamixHttpProbeResponse {
        val request = requestBuilder(url, headers, referer, cookies)
            .header("Range", "bytes=0-${(maxBytes - 1).coerceAtLeast(0)}")
            .get()
            .build()
        client.newBuilder().callTimeout(timeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS).build()
            .newCall(request).execute().use { response ->
                val body = response.body
                var bytesRead = 0L
                if (body != null && maxBytes > 0) {
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        while (bytesRead < maxBytes) {
                            val remaining = maxBytes - bytesRead
                            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (count < 0) break
                            if (count == 0) continue
                            bytesRead += count
                        }
                    }
                }
                val contentLength = body?.contentLength() ?: -1L
                return StreamixHttpProbeResponse(
                    code = response.code,
                    url = response.request.url.toString(),
                    bytesRead = bytesRead,
                    eof = contentLength >= 0 && bytesRead >= contentLength,
                    headers = response.headers.toMultimap()
                )
            }
    }

    private fun execute(method: String, url: String, body: String?, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long): StreamixHttpResponse {
        val builder = requestBuilder(url, headers, referer, cookies)
        if (method == "POST") {
            builder.post((body ?: "").toRequestBody("text/plain; charset=utf-8".toMediaType()))
        } else {
            builder.get()
        }
        client.newBuilder().callTimeout(timeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS).build()
            .newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val responseCookies = response.headers.values("Set-Cookie").mapNotNull { value ->
                    value.substringBefore(';').split('=', limit = 2)
                        .takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() }
                }.toMap()
                return StreamixHttpResponse(
                    code = response.code,
                    url = response.request.url.toString(),
                    text = text,
                    headers = response.headers.toMultimap(),
                    cookies = responseCookies
                )
            }
    }

    private fun requestBuilder(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>): Request.Builder {
        val builder = Request.Builder().url(url)
        headers.forEach { (name, value) -> builder.header(name, value) }
        if (referer != null && headers.keys.none { it.equals("Referer", true) }) builder.header("Referer", referer)
        if (cookies.isNotEmpty() && headers.keys.none { it.equals("Cookie", true) }) {
            builder.header("Cookie", cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" })
        }
        return builder
    }
}

internal class CatalogPublicDns(
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() }
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val host = hostname.trim().trimEnd('.').lowercase()
        if (host.isBlank() || host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) {
            throw UnknownHostException("catalog host is not allowed")
        }
        val addresses = try { resolve(host) } catch (error: Exception) {
            throw UnknownHostException("catalog host could not be resolved").apply { initCause(error) }
        }
        if (addresses.isEmpty() || addresses.any(::isPrivateOrLocal)) {
            throw UnknownHostException("catalog host resolves to a private/local address")
        }
        // OkHttp uses this validated list as its connection candidates, avoiding
        // a second hostname resolution between validation and socket connect.
        return addresses.toList()
    }

    internal fun isPrivateOrLocal(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return true
        val bytes = address.address
        if (bytes.size == 4) {
            val a = bytes[0].toInt() and 0xff
            val b = bytes[1].toInt() and 0xff
            val c = bytes[2].toInt() and 0xff
            return a == 0 || a == 10 || (a == 172 && b in 16..31) ||
                (a == 192 && (b == 168 || (b == 0 && c in 0..2) || (b == 88 && c == 99))) ||
                (a == 198 && (b in 18..19 || (b == 51 && c == 100))) ||
                (a == 203 && b == 0 && c == 113) || (a == 100 && b in 64..127) ||
                a == 127 || a >= 224
        }
        if (bytes.size != 16) return false
        // Only global-unicast IPv6 (2000::/3) is eligible for Catalog HTTP.
        // This rejects NAT64 well-known-prefix destinations and other non-global
        // special-use ranges instead of trying to enumerate every transition prefix.
        if ((bytes[0].toInt() and 0xe0) != 0x20) return true
        // IPv4-mapped IPv6 can encode a private IPv4 destination; do not let it
        // bypass the IPv4 range checks above.
        val ipv4Mapped = bytes.take(10).all { it == 0.toByte() } &&
            bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()
        if (ipv4Mapped) {
            val mappedIpv4 = InetAddress.getByAddress(bytes.copyOfRange(12, 16))
            if (isPrivateOrLocal(mappedIpv4)) return true
        }
        // 6to4 and Teredo embed IPv4 addresses and should not be used as an
        // alternate route to an otherwise blocked private destination.
        val sixToFour = bytes[0] == 0x20.toByte() && bytes[1] == 0x02.toByte()
        val teredo = bytes[0] == 0x20.toByte() && bytes[1] == 0x01.toByte() &&
            bytes[2] == 0x00.toByte() && bytes[3] == 0x00.toByte()
        val uniqueLocal = (bytes[0].toInt() and 0xfe) == 0xfc
        val documentation = bytes[0] == 0x20.toByte() && bytes[1] == 0x01.toByte() &&
            bytes[2] == 0x0d.toByte() && bytes[3] == 0xb8.toByte()
        // Deprecated IPv4-compatible IPv6 literals can encode an IPv4 target
        // without using the IPv4-mapped ::ffff: form.
        val ipv4Compatible = bytes.take(12).all { it == 0.toByte() }
        // IANA special-purpose IPv6 ranges: discard-only 100::/64 and the
        // 2001::/23 protocol-assignment block are not public media destinations.
        val discardOnly = bytes[0] == 0x01.toByte() && bytes[1] == 0x00.toByte() &&
            bytes.sliceArray(2..7).all { it == 0.toByte() }
        val protocolAssignments = bytes[0] == 0x20.toByte() && bytes[1] == 0x01.toByte() &&
            bytes[2] == 0.toByte() && (bytes[3].toInt() and 0xfe) == 0
        return ipv4Mapped || ipv4Compatible || sixToFour || teredo || uniqueLocal ||
            documentation || discardOnly || protocolAssignments
    }
}

internal class CatalogCookieJar : CookieJar {
    private val lock = Any()
    private val cookies = mutableListOf<Cookie>()

    override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<Cookie>) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            this.cookies.removeAll { existing ->
                existing.expiresAt < now || cookies.any { fresh ->
                    fresh.name == existing.name && fresh.domain == existing.domain && fresh.path == existing.path
                }
            }
            this.cookies.addAll(cookies)
        }
    }

    override fun loadForRequest(url: okhttp3.HttpUrl): List<Cookie> = synchronized(lock) {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt < now }
        cookies.filter { it.matches(url) }
    }
}
