package streamix.readingcatalog

import java.net.URI
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode

/**
 * Sainokan TLS stores chapter prose in base64 data-content chunks with aria-hidden decoys.
 * Only public chapter routes are accepted; locked novel pages are not bypassed.
 */
class SainokanTlsReadingRuntimeProvider(
    private val documentFetcher: ((String) -> String?)? = null,
) : ReadingRuntimeProvider {
    override val id = "sainokantls"
    private val hosts = setOf("sainokantls.com", "www.sainokantls.com")

    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): Boolean =
        entry.kind == ReadingKind.NOVEL && chapter.sources.any {
            it.enabled && it.providerId.equals(id, true) && validChapterUrl(it.url)
        }

    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? =
        withContext(Dispatchers.IO) {
            if (entry.kind != ReadingKind.NOVEL) return@withContext null
            val source = chapter.sources.firstOrNull {
                it.enabled && it.providerId.equals(id, true) && validChapterUrl(it.url)
            } ?: return@withContext null
            val html = try {
                documentFetcher?.invoke(source.url) ?: if (documentFetcher == null) fetch(source.url) else null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: return@withContext null

            val document = Jsoup.parse(html, source.url)
            val content = document.selectFirst("#chapter-content") ?: return@withContext null
            content.select("[aria-hidden=true]").remove()
            content.select("span.cdh-chunk[data-content]").forEach { span ->
                val decoded = runCatching {
                    String(Base64.getDecoder().decode(span.attr("data-content")), Charsets.UTF_8)
                }.getOrNull() ?: return@forEach
                span.replaceWith(TextNode(decoded))
            }
            val text = content.text().trim()
            text.takeIf(String::isNotBlank)?.let {
                ReadingContent(entry.kind, "reading-provider:$id", source.url, text = it, format = "text")
            }
        }

    private fun validChapterUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", true) &&
            uri.host?.lowercase() in hosts &&
            uri.userInfo == null && uri.port in setOf(-1, 443) &&
            uri.path.matches(Regex("/chapter/[A-Za-z0-9_-]{1,180}/?"))
    }.getOrDefault(false)

    private fun fetch(value: String): String? = ReadingHttpTransport.getText(
        value,
        mapOf(
            "Accept" to "text/html,application/xhtml+xml",
            "Referer" to "https://sainokantls.com/",
            "User-Agent" to "ShinigamiReadingRuntime/1.0",
        ),
        followRedirects = false,
    )
}
