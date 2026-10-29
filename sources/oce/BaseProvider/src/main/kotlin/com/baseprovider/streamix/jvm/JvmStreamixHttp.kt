package com.baseprovider.streamix.jvm

import com.baseprovider.streamix.StreamixHttp
import com.baseprovider.streamix.StreamixHttpResponse
import com.baseprovider.streamix.StreamixHttpProbeResponse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class JvmStreamixHttp(
    private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).build()
) : StreamixHttp {
    override suspend fun get(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long): StreamixHttpResponse =
        execute(Request.Builder().url(url).apply { headers.forEach { (k,v)->header(k,v) }; referer?.let{header("Referer",it)}; cookies.forEach{(k,v)->header("Cookie","$k=$v")} }.get().build(), timeoutMs)
    override suspend fun post(url: String, body: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long): StreamixHttpResponse =
        execute(Request.Builder().url(url).apply { headers.forEach { (k,v)->header(k,v) }; referer?.let{header("Referer",it)}; cookies.forEach{(k,v)->header("Cookie","$k=$v")} }.post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType())).build(), timeoutMs)

    override suspend fun probe(url: String, headers: Map<String, String>, referer: String?, cookies: Map<String, String>, timeoutMs: Long, maxBytes: Long): StreamixHttpProbeResponse {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
            referer?.let { header("Referer", it) }
            cookies.forEach { (k, v) -> header("Cookie", "$k=$v") }
        }.get().build()
        val callClient = client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
        callClient.newCall(request).execute().use { response ->
            var bytesRead = 0L
            var eof = false
            response.body?.byteStream()?.use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (bytesRead < maxBytes) {
                    val n = stream.read(buffer, 0, minOf(buffer.size.toLong(), maxBytes - bytesRead).toInt())
                    if (n <= 0) {
                        eof = true
                        break
                    }
                    bytesRead += n
                }
            } ?: run { eof = true }
            return StreamixHttpProbeResponse(response.code, response.request.url.toString(), bytesRead, eof)
        }
    }
    private fun execute(request: Request, timeoutMs: Long): StreamixHttpResponse {
        val callClient=client.newBuilder().callTimeout(timeoutMs,TimeUnit.MILLISECONDS).build()
        callClient.newCall(request).execute().use { response ->
            val cookies=response.headers.values("Set-Cookie").mapNotNull{raw->val i=raw.indexOf('=');if(i<=0)null else raw.substring(0,i).trim() to raw.substring(i+1).substringBefore(';').trim()}.toMap()
            return StreamixHttpResponse(response.code,response.request.url.toString(),response.body?.string().orEmpty(),response.headers.toMultimap(),cookies)
        }
    }
}