package com.baseprovider.config

/**
 * Konfigurasi extractor berbasis JSON (pola sama seperti [ProviderConfig]).
 *
 * Satu file JSON = satu extractor. Extractors tidak lagi wajib menulis class
 * Kotlin baru: engine [com.baseprovider.extractor.ConfigDrivenExtractor]
 * mengeksekusi [steps] secara berurutan dengan semua metode ekstraksi yang
 * tersedia (fetch / post / regex / jsonPath / constructUrl / substring).
 *
 * [variants] memungkinkan SATU extractor punya beberapa strategi header
 * sebagai fallback: engine mencoba variant pertama; jika tidak menghasilkan
 * link, lanjut ke variant berikutnya sampai ada hasil.
 */
data class ExtractorConfig(
    val id: String,
    val name: String = id,
    val mainUrl: String = "https://example.com",
    val requiresReferer: Boolean = true,
    val idSource: IdSource? = null,
    val variants: List<ExtractorVariant> = listOf(ExtractorVariant()),
    val steps: List<ExtractorStep> = emptyList(),
    val videoReferer: String = "",
    val outputFilter: String = "adaptive",
) {
    init { validate() }

    private fun validate() {
        val errors = mutableListOf<String>()
        if (mainUrl.isBlank() || !mainUrl.startsWith("http"))
            errors += "mainUrl must be a valid http URL"
        if (variants.isEmpty()) errors += "variants must not be empty"
        if (steps.isEmpty()) errors += "steps must not be empty"
        if (outputFilter !in listOf("adaptive", "master", "none"))
            errors += "invalid outputFilter: $outputFilter"
        if (errors.isNotEmpty()) {
            com.baseprovider.streamix.StreamixLogger.w(
                "ExtractorConfig[$id]",
                "Validation:\n  ${errors.joinToString("\n  ")}"
            )
        }
    }
}

data class IdSource(
    val type: String,
    val param: String = "",
    val pattern: String = "",
    val group: Int = 1,
    val selector: String = "",
    val attr: String = "src",
)

data class ExtractorVariant(
    val name: String = "default",
    val headers: Map<String, String> = emptyMap(),
    val referer: String = "",
    val userAgent: String = "",
)

sealed class ExtractorStep {
    data class Fetch(
        val url: String,
        val referer: String = "",
        val headers: Map<String, String> = emptyMap(),
        val store: String = "response",
        val urlReplace: Map<String, String> = emptyMap(),
        val storeFinalUrl: String = "",
    ) : ExtractorStep()
    data class PostForm(
        val url: String,
        val data: Map<String, String>,
        val referer: String = "",
        val headers: Map<String, String> = emptyMap(),
        val store: String = "response",
    ) : ExtractorStep()
    data class PostJson(
        val url: String,
        val jsonBody: String,
        val referer: String = "",
        val headers: Map<String, String> = emptyMap(),
        val store: String = "response",
    ) : ExtractorStep()
    data class Regex(
        val pattern: String,
        val group: Int = 1,
        val source: String = "response",
        val filter: String = "",
        val universal: Boolean = false,
        val decodeUnicode: Boolean = false,
        val store: String = "",
    ) : ExtractorStep()
    data class JsonPath(
        val path: String,
        val source: String = "response",
        val filter: String = "",
        val store: String = "",
    ) : ExtractorStep()
    data class ConstructUrl(
        val template: String,
        val store: String = "",
    ) : ExtractorStep()
    data class Substring(
        val startMarker: String,
        val endMarker: String,
        val source: String = "response",
        val store: String = "",
    ) : ExtractorStep()
    data class ResolveUrl(
        val base: String = "{url}",
        val source: String = "",
    ) : ExtractorStep()
    data class PackedJs(
        val source: String = "response",
        val store: String = "decoded",
    ) : ExtractorStep()
    data class AesGcm(
        val source: String = "response",
        val keyPartsPath: String = "playback.key_parts",
        val ivPath: String = "playback.iv",
        val payloadPath: String = "playback.payload",
        val store: String = "plaintext",
    ) : ExtractorStep()
    data class RhinoEval(
        val source: String = "response",
        val objectName: String = "svg",
        val store: String = "jsonResult",
    ) : ExtractorStep()
    data class XorSig(
        val source: String = "jsonResult",
        val store: String = "watchlink",
    ) : ExtractorStep()
    data class Delegate(
        val url: String = "{url}",
        val queryParam: String = "",
    ) : ExtractorStep()
    data class Iframe(
        val source: String = "response",
        val selector: String = "iframe[src]",
        val attribute: String = "src",
        val exclude: String = "",
        val include: String = "",
        val base: String = "{url}",
    ) : ExtractorStep()
    data class Redirect(
        val source: String = "finalUrl",
        val url: String = "{url}",
    ) : ExtractorStep()
    data class Webview(
        val url: String = "{url}",
        val referer: String = "",
        val headers: Map<String, String> = emptyMap(),
        val interceptPattern: String = "(m3u8|master\\.txt)",
        val timeoutMs: Long = 15000L,
    ) : ExtractorStep()
}