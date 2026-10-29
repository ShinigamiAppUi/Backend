package streamix.catalog

import kotlinx.coroutines.CancellationException
import streamix.api.StreamRef
import streamix.core.StreamixHttp
import java.net.URI

class UqloadCatalogResolver(private val http: StreamixHttp = CatalogSafeStreamixHttp()) : CatalogResolver {
    override val id = "uqload-catalog"
    override val domains = setOf("uqload.is")
    override suspend fun resolve(source: EpisodeSource): CatalogResolverResult {
        val page = try { http.get(source.sourceUrl, source.headers, source.referer ?: "https://uqload.is/") }
        catch (e: CancellationException) { throw e }
        catch (e: Throwable) { return CatalogResolverResult(failureReason = e.message?.take(512)) }
        if (page.code !in 200..299) return CatalogResolverResult(httpStatus = page.code, finalUrl = page.url, failureReason = "HTTP ${page.code}")
        val direct = DIRECT.find(page.text)?.groupValues?.getOrNull(1)
        val packed = page.text.split("<script", ignoreCase = true).firstOrNull { it.contains("eval(function(p,a,c,k,e,d)", ignoreCase = false) }
        val urls = buildList {
            if (!direct.isNullOrBlank()) add(direct)
            if (packed != null) {
                PackedJsUnpacker.unpackAndCombine(packed)?.let { body ->
                    MEDIA.findAll(body).forEach { add(it.groupValues[1]) }
                    val terminal = body.trim().trim('"').trim('\'')
                    if (isMedia(terminal)) add(terminal)
                }
            }
        }.mapNotNull { runCatching { URI(page.url).resolve(it).toString() }.getOrNull() }.distinct()
        val media = urls.firstOrNull(::isMedia) ?: return CatalogResolverResult(httpStatus = page.code, finalUrl = page.url, failureReason = "Uqload media source not found")
        val type = if (URI(media).path.orEmpty().endsWith(".m3u8", true)) "hls" else "video"
        return CatalogResolverResult(streams = listOf(StreamRef(id, media, source.quality, type, source.language, source.subtitleLanguage, source.headers + ("Referer" to page.url), page.url)), httpStatus = page.code, finalUrl = page.url)
    }
    private fun isMedia(url: String): Boolean { val u = runCatching { URI(url) }.getOrNull() ?: return false; return u.scheme in setOf("http","https") && !u.host.isNullOrBlank() && (u.path.orEmpty().endsWith(".m3u8",true) || u.path.orEmpty().endsWith(".mp4",true)) }
    private companion object {
        val DIRECT = Regex("""sources\s*:\s*\[\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val MEDIA = Regex("""["']((?:https?:)?//[^"']+(?:\.m3u8|\.mp4)[^"']*)["']""", RegexOption.IGNORE_CASE)
    }
}
