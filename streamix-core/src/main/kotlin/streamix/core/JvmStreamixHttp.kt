package streamix.core

import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class JvmStreamixHttp(
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .cookieHandler(CookieManager().apply { setCookiePolicy(CookiePolicy.ACCEPT_ALL) })
        .build()
) : StreamixHttp {
    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        referer: String?,
        cookies: Map<String, String>,
        timeoutMs: Long
    ) = request("GET", url, null, headers, referer, cookies, timeoutMs)

    override suspend fun post(
        url: String,
        body: String,
        headers: Map<String, String>,
        referer: String?,
        cookies: Map<String, String>,
        timeoutMs: Long
    ) = request("POST", url, body, headers, referer, cookies, timeoutMs)

    override suspend fun probe(
        url: String,
        headers: Map<String, String>,
        referer: String?,
        cookies: Map<String, String>,
        timeoutMs: Long,
        maxBytes: Long
    ): StreamixHttpProbeResponse {
        val builder = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofMillis(timeoutMs.coerceAtLeast(1)))

        headers.forEach { (name, value) -> builder.header(name, value) }

        if (referer != null && headers.keys.none { it.equals("Referer", true) }) {
            builder.header("Referer", referer)
        }

        if (cookies.isNotEmpty() && headers.keys.none { it.equals("Cookie", true) }) {
            builder.header(
                "Cookie",
                cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" }
            )
        }

        if (maxBytes in 1 until Long.MAX_VALUE) {
            builder.header("Range", "bytes=0-${maxBytes - 1}")
        }

        builder.GET()
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())

        response.body().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead = 0L
            while (bytesRead < maxBytes) {
                val remaining = maxBytes - bytesRead
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) {
                    return StreamixHttpProbeResponse(
                        code = response.statusCode(),
                        url = response.uri().toString(),
                        bytesRead = bytesRead,
                        eof = true,
                        headers = response.headers().map()
                    )
                }
                if (read == 0) continue
                bytesRead += read
            }
            return StreamixHttpProbeResponse(
                code = response.statusCode(),
                url = response.uri().toString(),
                bytesRead = bytesRead,
                eof = false,
                headers = response.headers().map()
            )
        }
    }

    private fun request(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>,
        referer: String?,
        cookies: Map<String, String>,
        timeoutMs: Long
    ): StreamixHttpResponse {
        val builder = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofMillis(timeoutMs.coerceAtLeast(1)))
        headers.forEach { (name, value) -> builder.header(name, value) }
        if (referer != null && headers.keys.none { it.equals("Referer", true) }) builder.header("Referer", referer)
        if (cookies.isNotEmpty() && headers.keys.none { it.equals("Cookie", true) }) {
            builder.header("Cookie", cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" })
        }
        when (method) {
            "GET" -> builder.GET()
            "POST" -> builder.POST(HttpRequest.BodyPublishers.ofString(body ?: ""))
            else -> error("Unsupported HTTP method: $method")
        }
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return StreamixHttpResponse(
            code = response.statusCode(),
            url = response.uri().toString(),
            text = response.body(),
            headers = response.headers().map(),
            cookies = response.headers().allValues("set-cookie").mapNotNull { value ->
                value.substringBefore(';').split('=', limit = 2)
                    .takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() }
            }.toMap()
        )
    }
}
