package streamix.catalog

import java.net.InetAddress
import java.net.URI
import streamix.core.StreamixHttp
import streamix.core.StreamixHttpProbeResponse
import streamix.core.StreamixHttpResponse

/**
 * Catalog-scoped HTTP client. Redirects are followed manually so every hop is
 * validated before a network request. Provider Runtime networking is unchanged.
 */
class CatalogSafeStreamixHttp(
    private val delegate: StreamixHttp = CatalogPinnedStreamixHttp(),
    private val maxRedirects: Int = 5,
    private val urlValidator: ((String) -> URI)? = null
) : StreamixHttp {
    override suspend fun get(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long): StreamixHttpResponse {
        var current = validateUrl(url)
        var hopHeaders = headers
        var hopReferer = referer
        var hopCookies = cookies
        repeat(maxRedirects + 1) { hop ->
            val response = delegate.get(current.toString(), hopHeaders, hopReferer, hopCookies, timeoutMs)
            val next = redirectTarget(response, current) ?: return response
            if (hop == maxRedirects) throw IllegalArgumentException("catalog redirect limit exceeded")
            val target = validateRedirectTarget(current, next.toString())
            if (!sameOrigin(current, target)) {
                hopHeaders = stripCrossOriginHeaders(hopHeaders)
                hopReferer = null
                hopCookies = emptyMap()
            }
            current = target
        }
        throw IllegalArgumentException("catalog redirect limit exceeded")
    }

    override suspend fun post(url: String, body: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long): StreamixHttpResponse {
        var current = validateUrl(url)
        var currentBody: String? = body
        var usePost = true
        var hopHeaders = headers
        var hopReferer = referer
        var hopCookies = cookies
        repeat(maxRedirects + 1) { hop ->
            val response = if (usePost) delegate.post(current.toString(), currentBody.orEmpty(), hopHeaders, hopReferer, hopCookies, timeoutMs)
                else delegate.get(current.toString(), hopHeaders, hopReferer, hopCookies, timeoutMs)
            val next = redirectTarget(response, current) ?: return response
            if (hop == maxRedirects) throw IllegalArgumentException("catalog redirect limit exceeded")
            val target = validateRedirectTarget(current, next.toString())
            if (!sameOrigin(current, target)) {
                if (usePost && response.code in setOf(307, 308)) throw IllegalArgumentException("cross-origin POST redirect rejected")
                hopHeaders = stripCrossOriginHeaders(hopHeaders)
                hopReferer = null
                hopCookies = emptyMap()
            }
            if (response.code in setOf(301, 302, 303)) { usePost = false; currentBody = null }
            current = target
        }
        throw IllegalArgumentException("catalog redirect limit exceeded")
    }

    override suspend fun probe(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long, maxBytes: Long): StreamixHttpProbeResponse {
        var current = validateUrl(url)
        var hopHeaders = headers
        var hopReferer = referer
        var hopCookies = cookies
        repeat(maxRedirects + 1) { hop ->
            val response = delegate.probe(current.toString(), hopHeaders, hopReferer, hopCookies, timeoutMs, maxBytes)
            val next = probeRedirectTarget(response, current) ?: return response
            if (hop == maxRedirects) throw IllegalArgumentException("catalog redirect limit exceeded")
            val target = validateRedirectTarget(current, next.toString())
            if (!sameOrigin(current, target)) {
                hopHeaders = stripCrossOriginHeaders(hopHeaders)
                hopReferer = null
                hopCookies = emptyMap()
            }
            current = target
        }
        throw IllegalArgumentException("catalog redirect limit exceeded")
    }

    private fun validateUrl(raw: String): URI = urlValidator?.invoke(raw) ?: validatePublicUrl(raw)

    private fun validateRedirectTarget(current: URI, raw: String): URI {
        val target = validateUrl(raw)
        require(!(current.scheme.equals("https", true) && target.scheme.equals("http", true))) {
            "catalog HTTPS-to-HTTP redirect rejected"
        }
        return target
    }

    private fun sameOrigin(first: URI, second: URI): Boolean =
        first.scheme.equals(second.scheme, true) && first.host.equals(second.host, true) && effectivePort(first) == effectivePort(second)

    private fun effectivePort(uri: URI): Int = if (uri.port >= 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80

    private fun stripCrossOriginHeaders(headers: Map<String, String>): Map<String, String> =
        headers.filterKeys { key ->
            !key.equals("Authorization", true) && !key.equals("Proxy-Authorization", true) &&
                !key.equals("Cookie", true) && !key.equals("Host", true) &&
                !key.equals("Origin", true) && !key.equals("Referer", true)
        }

    private fun redirectTarget(response: StreamixHttpResponse, current: URI): URI? {
        if (response.code !in REDIRECT_CODES) return null
        val location = response.headers.entries.firstOrNull { it.key.equals("location", true) }?.value?.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return current.resolve(location)
    }

    private fun probeRedirectTarget(response: StreamixHttpProbeResponse, current: URI): URI? {
        if (response.code !in REDIRECT_CODES) return null
        val location = response.headers.entries.firstOrNull { it.key.equals("location", true) }?.value?.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return current.resolve(location)
    }

    private fun validatePublicUrl(raw: String): URI {
        val uri = try { URI(raw) } catch (_: Exception) { throw IllegalArgumentException("catalog URL is invalid") }
        require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) { "catalog URL must be http(s)" }
        require(uri.userInfo == null) { "catalog URL userinfo is not allowed" }
        require(uri.port == -1 || uri.port in 1..65535) { "catalog URL port is invalid" }
        val rawHost = uri.host?.lowercase() ?: throw IllegalArgumentException("catalog URL host is required")
        require(rawHost != "localhost" && !rawHost.endsWith(".localhost") && !rawHost.endsWith(".local")) { "catalog URL host is not allowed" }
        val host = rawHost.removePrefix("[").removeSuffix("]")
        val addresses = try { InetAddress.getAllByName(host).toList() } catch (_: Exception) { throw IllegalArgumentException("catalog URL host could not be resolved") }
        require(addresses.isNotEmpty() && addresses.none(::isPrivateOrLocal)) { "catalog URL host resolves to a private/local address" }
        return uri
    }

    private fun isPrivateOrLocal(address: InetAddress): Boolean =
        CatalogPublicDns().isPrivateOrLocal(address)

    private companion object { val REDIRECT_CODES = setOf(301, 302, 303, 307, 308) }
}
