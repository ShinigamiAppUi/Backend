package streamix.readingcatalog

import com.google.gson.JsonParser
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * KomikNesia reader uses its dedicated chapters/slug API. The current API can wrap
 * responses in an AES-CBC envelope, so both plain and encrypted responses are handled.
 */
class KomikNesiaReadingRuntimeProvider(
    private val jsonFetcher: ((String) -> String?)? = null
) : ReadingRuntimeProvider {
    override val id = "komiknesia"
    private val apiBase = "https://api-be.komiknesia.my.id/api"

    override fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): Boolean =
        entry.kind == ReadingKind.MANGA && chapter.sources.any {
            it.enabled && it.providerId.equals(id, true) && slug(it.url) != null
        }

    override suspend fun chapter(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): ReadingContent? =
        withContext(Dispatchers.IO) {
            if (entry.kind != ReadingKind.MANGA) return@withContext null
            val source = chapter.sources.firstOrNull { it.enabled && it.providerId.equals(id, true) && slug(it.url) != null }
                ?: return@withContext null
            val slug = slug(source.url) ?: return@withContext null
            val raw = try {
                fetch("$apiBase/chapters/slug/$slug")
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: return@withContext null
            val root = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return@withContext null
            val payload = if (root.get("encrypted")?.takeIf { it.isJsonPrimitive }?.asBoolean == true) {
                val encrypted = root.get("data")?.takeIf { it.isJsonPrimitive }?.asString ?: return@withContext null
                val time = root.get("time")?.takeIf { it.isJsonPrimitive }?.asDouble ?: return@withContext null
                runCatching { JsonParser.parseString(decrypt(encrypted, time)).asJsonObject }.getOrNull()
                    ?: return@withContext null
            } else root
            // API error envelopes and version drift can change data/images to non-object or
            // non-array values. Treat those shapes as an unavailable chapter instead of throwing.
            val data = payload.get("data")
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?: payload
            // Keep a root-level reader list as a fallback when a versioned API response
            // includes a partial data object without a usable images array.
            val images = sequenceOf(payload, data)
                .mapNotNull { candidate ->
                    candidate.get("images")
                        ?.takeIf { it.isJsonArray }
                        ?.asJsonArray
                        ?.mapNotNull { item ->
                            item.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                                ?.asString
                                ?.trim()
                                ?.takeIf(String::isNotBlank)
                                ?.takeIf(::validImage)
                        }
                }
                .flatten()
                .normalizedReadingPageUrls()
            images.takeIf { it.isNotEmpty() }?.let {
                ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages")
            }
        }

    private fun slug(value: String): String? {
        val allowedHosts = setOf("v2.komiknesiaku.com", "v1.komiknesiaku.com", "komiknesiaku.com")
        if (!isSafeReadingChapterUrl(value, allowedHosts) { it.contains("/view/", ignoreCase = true) }) return null
        return try {
            val path = URI(value).path.trimEnd('/')
            path.substringAfterLast('/')
                .takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,180}")) }
        } catch (_: Exception) {
            null
        }
    }

    private fun decrypt(encoded: String, time: Double): String {
        val keyText = String.format(java.util.Locale.US, "%.8f", time / 32.0).padEnd(32, '0').take(32)
        val raw = Base64.getDecoder().decode(encoded)
        require(raw.size > 16) { "Encrypted KomikNesia response is too short" }
        val iv = raw.copyOfRange(0, 16)
        val ciphertext = raw.copyOfRange(16, raw.size)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyText.toByteArray(StandardCharsets.UTF_8), "AES"), IvParameterSpec(iv))
        return cipher.doFinal(ciphertext).toString(StandardCharsets.UTF_8)
    }

    private fun validImage(value: String): Boolean = isSafeReadingImageUrl(value)

    private fun fetch(url: String): String? =
        jsonFetcher?.invoke(url) ?: if (jsonFetcher == null) {
            ReadingHttpTransport.getText(url, mapOf("Accept" to "application/json"))
        } else null
}
