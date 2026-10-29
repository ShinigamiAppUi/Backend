package streamix.catalog

import kotlin.test.Test
import kotlin.test.assertEquals

class CatalogDirectLinkNormalizerTest {
    @Test
    fun pixeldrainUserUrlBecomesDirectApiDownload() {
        assertEquals(
            "https://pixeldrain.com/api/file/ABC123?download",
            CatalogDirectLinkNormalizer.normalize("https://pixeldrain.com/u/ABC123")
        )
    }

    @Test
    fun pixeldrainLuUserUrlAlsoBecomesCanonicalDirectApiDownload() {
        assertEquals(
            "https://pixeldrain.com/api/file/ABC123?download",
            CatalogDirectLinkNormalizer.normalize("https://pixeldrain.lu/u/ABC123")
        )
    }

    @Test
    fun nonPixeldrainUrlsRemainUntouched() {
        val url = "https://example.com/video.mp4"
        assertEquals(url, CatalogDirectLinkNormalizer.normalize(url))
    }
}
