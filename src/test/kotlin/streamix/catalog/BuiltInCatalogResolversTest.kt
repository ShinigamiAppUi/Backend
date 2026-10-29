package streamix.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.baseprovider.streamix.ProviderExtractorsNative

class BuiltInCatalogResolversTest {
    @Test
    fun builtInRegistryContainsCurrentResolverPool() {
        val resolvers = BuiltInCatalogResolvers.create(
            streamix.core.StreamixExtractorRegistry(ProviderExtractorsNative.all())
        ).all()

        assertEquals(
            listOf(
                "gofile-catalog",
                "krakenfiles-catalog",
                "buzzheavier-catalog",
                "mediafire-catalog",
                "yourupload-catalog",
                "mp4upload-catalog",
                "mixdrop-catalog",
                "streamtape-catalog",
                "dood-catalog",
                "sendvid-catalog",
                "burstcloud-catalog",
                "streamhub-catalog",
                "uqload-catalog",
                "streamwish-catalog",
                "filelions-catalog",
                "vidhide-catalog",
                "vidland-catalog",
                "vidmoly-catalog",
                "vido-catalog",
                "vudeo-catalog",
                "vk-catalog",
                "streamdav-catalog",
                "streamsilk-catalog",
                "streamup-catalog",
                "streamlare-catalog",
                "streamplay-catalog",
                "kwik-catalog",
                "kodik-catalog",
                "gdrive-native-catalog",
                "odnoklassniki-native-catalog",
                "linkbox-native-catalog",
                "blogger-native-catalog",
                "dailymotion-native-catalog",
                "filedon-native-catalog",
                "kuramadrive-native-catalog",
                "desustream-native-catalog",
            ),
            resolvers.map { it.id }
        )
        assertTrue(resolvers.all { it.domains.isNotEmpty() })
    }

    @Test
    fun hostMatchingIsAutomatic() {
        val registry = BuiltInCatalogResolvers.create(
            streamix.core.StreamixExtractorRegistry(ProviderExtractorsNative.all())
        )

        assertEquals(listOf("gofile-catalog"), registry.resolveAll("https://gofile.io/d/example").map { it.id })
        assertEquals(listOf("krakenfiles-catalog"), registry.resolveAll("https://krakenfiles.com/view/example/file.html").map { it.id })
        assertEquals(listOf("buzzheavier-catalog"), registry.resolveAll("https://buzzheavier.com/example").map { it.id })
        assertEquals(listOf("mediafire-catalog"), registry.resolveAll("https://www.mediafire.com/file/example/video").map { it.id })
        assertEquals(listOf("yourupload-catalog"), registry.resolveAll("https://www.yourupload.com/watch/example").map { it.id })
        assertEquals(listOf("mp4upload-catalog"), registry.resolveAll("https://mp4upload.com/abc123").map { it.id })
        assertEquals(listOf("mixdrop-catalog"), registry.resolveAll("https://mixdrop.co/e/example").map { it.id })
        assertEquals(listOf("mixdrop-catalog"), registry.resolveAll("https://mixdrop.ag/e/example").map { it.id })
        assertEquals(listOf("mixdrop-catalog"), registry.resolveAll("https://mixdrop.to/e/example").map { it.id })
        assertEquals(listOf("streamtape-catalog"), registry.resolveAll("https://streamtape.com/v/example").map { it.id })
        assertEquals(listOf("streamtape-catalog"), registry.resolveAll("https://streamtape.to/v/example").map { it.id })
        assertEquals(listOf("dood-catalog"), registry.resolveAll("https://dood.to/e/example").map { it.id })
        assertEquals(listOf("dood-catalog"), registry.resolveAll("https://doodstream.com/e/example").map { it.id })
        assertEquals(listOf("sendvid-catalog"), registry.resolveAll("https://sendvid.com/example").map { it.id })
        assertEquals(listOf("burstcloud-catalog"), registry.resolveAll("https://www.burstcloud.co/e/example").map { it.id })
        assertEquals(listOf("streamhub-catalog"), registry.resolveAll("https://streamhub.ink/e/example").map { it.id })
        assertEquals(listOf("uqload-catalog"), registry.resolveAll("https://uqload.is/e/example").map { it.id })

        assertEquals(listOf("gdrive-native-catalog"), registry.resolveAll("https://gdplayer.to/embed/example").map { it.id })
        assertEquals(listOf("odnoklassniki-native-catalog"), registry.resolveAll("https://ok.ru/video/example").map { it.id })
        assertEquals(listOf("linkbox-native-catalog"), registry.resolveAll("https://www.linkbox.to/s/example").map { it.id })
        assertEquals(listOf("blogger-native-catalog"), registry.resolveAll("https://example.blogspot.com/p/video.html").map { it.id })
        assertEquals(listOf("dailymotion-native-catalog"), registry.resolveAll("https://www.dailymotion.com/video/example").map { it.id })
        assertEquals(listOf("filedon-native-catalog"), registry.resolveAll("https://filedon.co/example").map { it.id })
        assertEquals(listOf("kuramadrive-native-catalog"), registry.resolveAll("https://kuramadrive.com/example").map { it.id })
        assertEquals(listOf("desustream-native-catalog"), registry.resolveAll("https://desustream.me/moedesu/abc").map { it.id })
        assertEquals(listOf("desustream-native-catalog"), registry.resolveAll("https://desustream.info/dstream/updesu/abc").map { it.id })
    }
}
