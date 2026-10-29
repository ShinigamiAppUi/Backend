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
            val data = payload.getAsJsonObject("data") ?: payload
            val images = data.getAsJsonArray("images")?.mapNotNull { item ->
                item.takeIf { it.isJsonPrimitive }?.asString?.takeIf(::validImage)
            }.orEmpty().normalizedReadingPageUrls()
            images.takeIf { it.isNotEmpty() }?.let {
                ReadingContent(entry.kind, "reading-provider:$id", source.url, it, format = "image-pages")
            }
        }

    private fun slug(value: String): String? = runCatching {
        val uri = URI(value)
        if (!isSafeReadingHttpUrl(value) || uri.userInfo != null ||
            (uri.port != -1 && uri.port != 80 && uri.port != 443) ||
            !(uri.host.equals("v2.komiknesiaku.com", true) ||
                uri.host.equals("v1.komiknesiaku.com", true) || uri.host.equals("komiknesiaku.com", true))) return null
        val path = uri.path.trimEnd('/')
        val result = path.substringAfterLast('/')
        result.takeIf { path.contains("/view/") && it.matches(Regex("[A-Za-z0-9_-]{1,180}")) }
    }.getOrNull()

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
