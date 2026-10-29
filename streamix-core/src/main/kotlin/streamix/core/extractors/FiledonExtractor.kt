package streamix.core.extractors

import streamix.core.ProviderStream
import streamix.core.StreamixExtractor
import streamix.core.StreamixExtractorRequest
import streamix.core.StreamixExtractorResult
import streamix.core.StreamixHttp

class FiledonExtractor(
    private val http: StreamixHttp
) : StreamixExtractor {
    override val id: String = "filedon"

    override val domains: Set<String> = setOf("filedon.co")

    override suspend fun extract(request: StreamixExtractorRequest): StreamixExtractorResult {
        val fixed = normalize(request.url)
        val response = http.get(
            fixed,
            headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            ) + request.headers,
            referer = request.referer
        )
        val document = response.text

        val dataPage = Regex("""data-page=["']([^"']+)["']""")
            .find(document)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::unescapeHtml)

        val qualityHint = Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE)
            .find(dataPage ?: document)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        val urls = linkedSetOf<String>()

        if (!dataPage.isNullOrBlank()) {
            Regex(
                """https?://[^"\s]+\.(?:mp4|m3u8)[^"\s]*""",
                RegexOption.IGNORE_CASE
            ).findAll(dataPage)
                .map { unescapeUrl(it.value) }
                .forEach(urls::add)

            listOf("download_url", "stream_url", "url", "file_url", "direct_url")
                .forEach { key ->
                    Regex(""""$key"\s*:\s*"([^"]+)"""")
                        .findAll(dataPage)
                        .map { unescapeUrl(it.groupValues[1]) }
                        .filter {
                            it.startsWith("http") &&
                                (it.contains(".mp4", true) ||
                                 it.contains(".m3u8", true) ||
                                 it.contains("r2.cloudflare", true) ||
                                 it.contains("s3", true))
                        }
                        .forEach(urls::add)
                }
        }

        if (urls.isEmpty()) {
            Regex(
                """https?://[^\s"'<>]+\.(?:mp4|m3u8)[^\s"'<>]*""",
                RegexOption.IGNORE_CASE
            ).findAll(document)
                .map { unescapeUrl(it.value) }
                .forEach(urls::add)
        }

        return StreamixExtractorResult(
            streams = urls.map { stream ->
                ProviderStream(
                    providerId = request.providerId ?: id,
                    url = stream,
                    quality = request.quality ?: qualityHint,
                    type = inferType(stream),
                    headers = mapOf("Accept" to "*/*") + request.headers,
                    referer = fixed
                )
            }
        )
    }

    private fun normalize(url: String): String {
        val absolute = if (url.startsWith("//")) "https:$url" else url
        return absolute.replace("/view/", "/embed/")
    }

    private fun unescapeHtml(input: String): String =
        input
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("\\u0026", "&")
            .replace("\\/", "/")

    private fun unescapeUrl(input: String): String =
        unescapeHtml(input).replace("\\\"", "\"")

    private fun inferType(url: String): String =
        when {
            url.substringBefore('?').lowercase().endsWith(".m3u8") -> "M3U8"
            url.substringBefore('?').lowercase().endsWith(".mpd") -> "DASH"
            else -> "VIDEO"
        }
}
