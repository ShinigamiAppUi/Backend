package streamix.catalog

import streamix.core.StreamixExtractorRegistry
import streamix.core.StreamixBuiltInExtractors
import streamix.core.StreamixHttp

/**
 * Built-in catalog resolver registry.
 *
 * Resolver selection is automatic from the catalog URL host. Adding a new
 * resolver here makes that host available to the admin/catalog flow without
 * introducing a provider picker or remote mapping layer.
 */
object BuiltInCatalogResolvers {
    fun create(
        extractors: StreamixExtractorRegistry = StreamixExtractorRegistry(),
        catalogHttp: StreamixHttp = CatalogSafeStreamixHttp()
    ): CatalogResolverRegistry {
        val catalogExtractors = StreamixExtractorRegistry(
            (extractors.all() + StreamixBuiltInExtractors.create(catalogHttp).all())
                .distinctBy { it.id.lowercase() }
        )
        return CatalogResolverRegistry(
            listOf(
                GofileCatalogResolver(),
                KrakenFilesCatalogResolver(),
                BuzzHeavierCatalogResolver(),
                MediafireCatalogResolver(),
                YourUploadCatalogResolver(),
                Mp4uploadCatalogResolver(),
                MixDropCatalogResolver(),
                StreamtapeCatalogResolver(),
                DoodCatalogResolver(),
                SendvidCatalogResolver(),
                BurstCloudCatalogResolver(),
                StreamHubCatalogResolver(),
                UqloadCatalogResolver(),
                StreamWishCatalogResolver(),
                FileLionsCatalogResolver(),
                VidHideCatalogResolver(),
                VidLandCatalogResolver(),
                VidMolyCatalogResolver(),
                VidoCatalogResolver(),
                VudeoCatalogResolver(),
                VkCatalogResolver(),
                StreamDavCatalogResolver(),
                StreamSilkCatalogResolver(),
                StreamUpCatalogResolver(),
                StreamLareCatalogResolver(),
                StreamPlayCatalogResolver(),
                KwikCatalogResolver(),
                KodikCatalogResolver(),


                NativeExtractorCatalogResolver(extractors, "Gdplayer", "gdrive-native-catalog", catalogHttp),
                NativeExtractorCatalogResolver(extractors, "OkRu", "odnoklassniki-native-catalog", catalogHttp),
                NativeExtractorCatalogResolver(extractors, "Linkbox", "linkbox-native-catalog", catalogHttp),
                NativeExtractorCatalogResolver(extractors, "BloggerVideo", "blogger-native-catalog", catalogHttp),
                NativeExtractorCatalogResolver(extractors, "Dailymotion", "dailymotion-native-catalog", catalogHttp),
                NativeExtractorCatalogResolver(extractors, "Filedon", "filedon-native-catalog", catalogHttp),
                NativeExtractorCatalogResolver(extractors, "DriveKurama", "kuramadrive-native-catalog", catalogHttp),
                NativeExtractorCatalogResolver(catalogExtractors, "jwplayer", "desustream-native-catalog", catalogHttp),
            )
        )
    }
}
