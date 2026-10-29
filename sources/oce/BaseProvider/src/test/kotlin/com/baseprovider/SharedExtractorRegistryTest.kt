package com.baseprovider

import com.baseprovider.streamix.ProviderExtractorsNative
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SharedExtractorRegistryTest {
    @Test
    fun sharedMirrorCatalogContainsReusableExtractors() {
        val ids = ProviderExtractorsNative.all().map { it.id.lowercase() }.toSet()
        listOf(
            "archiveorg", "turbovid", "yourupload", "pixeldrain",
            "gofile", "buzzheavier", "vtbe", "filemoonsx", "streamsb"
        ).forEach { assertTrue(it in ids, "missing shared extractor: $it") }
    }

    @Test
    fun missingConfigExtractorsAreReachable() {
        listOf(
            "CyberJunkiePage", "DTube", "EmbedPyrox", "Odysee",
            "PlayMogo", "PlayStreamVideo", "SamehadakuPage", "VeevTo"
        ).forEach { id ->
            assertNotNull(ProviderExtractorsNative.get(id), "missing config extractor: $id")
        }
    }

    @Test
    fun sharedDomainsDispatch() {
        assertNotNull(ProviderExtractorsNative.getMatchingStreamixExtractors("https://pixeldrain.com/u/abcdef"))
        assertNotNull(ProviderExtractorsNative.getMatchingStreamixExtractors("https://gofile.io/d/abcdef"))
        assertNotNull(ProviderExtractorsNative.getMatchingStreamixExtractors("https://waaw.to/e/abcdef"))
    }
}
