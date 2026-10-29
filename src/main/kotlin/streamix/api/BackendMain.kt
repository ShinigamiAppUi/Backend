package streamix.api

import streamix.auth.AuthRuntime
import streamix.catalog.BuiltInCatalogResolvers
import streamix.catalog.CatalogService
import streamix.catalog.FileCatalogRepository
import streamix.core.JvmStreamixHttp
import streamix.runtime.AnimeinApiProvider
import streamix.runtime.RuntimeProviderFallback
import streamix.runtime.StreamixRuntime
import streamix.runtime.StreamixService
import streamix.readingcatalog.FileReadingCatalogRepository
import streamix.readingcatalog.ReadingCatalogService
import streamix.readingcatalog.ReadingChapterEntry
import streamix.readingcatalog.ReadingChapterSource
import streamix.readingcatalog.VoraToonReadingRuntimeProvider
import streamix.readingcatalog.ComicasoReadingRuntimeProvider
import streamix.readingcatalog.TheMangaReadingRuntimeProvider
import streamix.readingcatalog.UlasComicReadingRuntimeProvider
import streamix.readingcatalog.ManhwaListReadingRuntimeProvider
import streamix.readingcatalog.CosmicScansIdReadingRuntimeProvider
import streamix.readingcatalog.WebtoonXYZReadingRuntimeProvider
import streamix.readingcatalog.ThunderScansReadingRuntimeProvider
import streamix.readingcatalog.SayManhwaReadingRuntimeProvider
import streamix.readingcatalog.KomikNextGOnlineReadingRuntimeProvider
import streamix.readingcatalog.NoromaxReadingRuntimeProvider
import streamix.readingcatalog.SekteKomikReadingRuntimeProvider
import streamix.readingcatalog.OtaScansReadingRuntimeProvider
import streamix.readingcatalog.MangaDistrictReadingRuntimeProvider
import streamix.readingcatalog.AsuraScansReadingRuntimeProvider
import streamix.readingcatalog.MangaFoxReadingRuntimeProvider
import streamix.readingcatalog.MangaBoltReadingRuntimeProvider
import streamix.readingcatalog.ManhwaGalaxyReadingRuntimeProvider
import streamix.readingcatalog.ManhwaHubReadingRuntimeProvider
import streamix.readingcatalog.ManhwaNexReadingRuntimeProvider
import streamix.readingcatalog.ManhwaOnlineReadingRuntimeProvider
import streamix.readingcatalog.ManhwaZoneReadingRuntimeProvider
import streamix.readingcatalog.RavenScansReadingRuntimeProvider
import streamix.readingcatalog.RageScansReadingRuntimeProvider
import streamix.readingcatalog.MangaHubReadingRuntimeProvider
import streamix.readingcatalog.MangaReaderReadingRuntimeProvider
import streamix.readingcatalog.MangaPlusReadingRuntimeProvider
import streamix.readingcatalog.VizReadingRuntimeProvider
import streamix.readingcatalog.MangaUpReadingRuntimeProvider
import streamix.readingcatalog.ReaperScansReadingRuntimeProvider
import streamix.readingcatalog.RoyalRoadReadingRuntimeProvider
import streamix.readingcatalog.NovelBuddyReadingRuntimeProvider
import streamix.readingcatalog.NovelBinReadingRuntimeProvider
import streamix.readingcatalog.NovelFireReadingRuntimeProvider
import streamix.readingcatalog.FreeWebNovelReadingRuntimeProvider
import streamix.readingcatalog.NovelFullReadingRuntimeProvider
import streamix.readingcatalog.NovelHiReadingRuntimeProvider
import streamix.readingcatalog.NovelLightReadingRuntimeProvider
import streamix.readingcatalog.ScribbleHubReadingRuntimeProvider
import streamix.readingcatalog.InkittReadingRuntimeProvider
import streamix.readingcatalog.AllNovelFullReadingRuntimeProvider
import streamix.readingcatalog.BoxNovelReadingRuntimeProvider
import streamix.readingcatalog.LightNovelCaveReadingRuntimeProvider
import streamix.readingcatalog.LightNovelHeavenReadingRuntimeProvider
import streamix.readingcatalog.NovelHallReadingRuntimeProvider
import streamix.readingcatalog.NovelLibReadingRuntimeProvider
import streamix.readingcatalog.NovelRestReadingRuntimeProvider
import streamix.readingcatalog.RanobesReadingRuntimeProvider
import streamix.readingcatalog.ReadNovelFullReadingRuntimeProvider
import streamix.readingcatalog.NovelNinjaReadingRuntimeProvider
import streamix.readingcatalog.JNovelClubReadingRuntimeProvider
import streamix.readingcatalog.BacaKomikReadingRuntimeProvider
import streamix.readingcatalog.MangaCanReadingRuntimeProvider
import streamix.readingcatalog.MangalayReadingRuntimeProvider
import streamix.readingcatalog.NgamenKomikReadingRuntimeProvider
import streamix.readingcatalog.NgomikReadingRuntimeProvider
import streamix.readingcatalog.OkyyKomikReadingRuntimeProvider
import streamix.readingcatalog.ShiyuraSubReadingRuntimeProvider
import streamix.readingcatalog.KomikuAsiaReadingRuntimeProvider
import streamix.readingcatalog.SiikomikReadingRuntimeProvider
import streamix.readingcatalog.NarasiNinjaReadingRuntimeProvider
import streamix.readingcatalog.KomikuReadingRuntimeProvider
import streamix.readingcatalog.BacamiReadingRuntimeProvider
import streamix.readingcatalog.KomikIndoIdReadingRuntimeProvider
import streamix.readingcatalog.KlikMangaReadingRuntimeProvider
import streamix.readingcatalog.ManhwaIndoReadingRuntimeProvider
import streamix.readingcatalog.HolotoonReadingRuntimeProvider
import streamix.readingcatalog.HwagoReadingRuntimeProvider
import streamix.readingcatalog.LumosKomikReadingRuntimeProvider
import streamix.readingcatalog.NatsuReadingRuntimeProvider
import streamix.readingcatalog.IzanamiScansReadingRuntimeProvider
import streamix.readingcatalog.RosyscansReadingRuntimeProvider
import streamix.readingcatalog.IndoWebNovelReadingRuntimeProvider
import streamix.readingcatalog.MtlNovelIdReadingRuntimeProvider
import streamix.readingcatalog.MeioNovelReadingRuntimeProvider
import streamix.readingcatalog.SakuraNovelReadingRuntimeProvider
import streamix.readingcatalog.SekteNovelReadingRuntimeProvider
import streamix.readingcatalog.VanovelReadingRuntimeProvider
import streamix.readingcatalog.WBNoveReadingRuntimeProvider
import streamix.readingcatalog.WestMangaReadingRuntimeProvider
import streamix.readingcatalog.RiztranslationReadingRuntimeProvider
import streamix.readingcatalog.MikoRokuReadingRuntimeProvider
import streamix.readingcatalog.KiryuuReadingRuntimeProvider
import streamix.readingcatalog.KomikStationReadingRuntimeProvider
import streamix.readingcatalog.ApkomikReadingRuntimeProvider
import streamix.readingcatalog.LepoyTlReadingRuntimeProvider
import streamix.readingcatalog.KomikNesiaReadingRuntimeProvider
import streamix.readingcatalog.KuroMangaReadingRuntimeProvider
import streamix.readingcatalog.AinzScansIdReadingRuntimeProvider
import streamix.readingcatalog.RoseveilReadingRuntimeProvider
import streamix.readingcatalog.SoulScansReadingRuntimeProvider
import streamix.readingcatalog.MGKomikReadingRuntimeProvider
import streamix.readingcatalog.MangaDexReadingRuntimeProvider
import streamix.readingcatalog.BatoReadingRuntimeProvider
import streamix.readingcatalog.ComickReadingRuntimeProvider
import streamix.readingcatalog.MangaParkTvReadingRuntimeProvider
import streamix.readingcatalog.MangaFireReadingRuntimeProvider
import streamix.readingcatalog.MangaKReadingRuntimeProvider
import streamix.readingcatalog.MangaPillReadingRuntimeProvider
import streamix.readingcatalog.MangaKatanaReadingRuntimeProvider
import streamix.readingcatalog.MangakakalotReadingRuntimeProvider
import streamix.readingcatalog.ManganatoReadingRuntimeProvider
import streamix.readingcatalog.MangaReadReadingRuntimeProvider
import streamix.readingcatalog.MangaBatReadingRuntimeProvider
import streamix.readingcatalog.MangaHereReadingRuntimeProvider
import streamix.readingcatalog.MangaTaroReadingRuntimeProvider
import streamix.readingcatalog.ToonilyReadingRuntimeProvider
import streamix.readingcatalog.WeebCentralReadingRuntimeProvider
import streamix.readingcatalog.FlameComicsReadingRuntimeProvider
import streamix.readingcatalog.ManhwaBuddyReadingRuntimeProvider
import streamix.readingcatalog.DragonTeaReadingRuntimeProvider
import streamix.readingcatalog.MangaGGReadingRuntimeProvider
import streamix.readingcatalog.MangaNowReadingRuntimeProvider
import streamix.readingcatalog.MangaGekoReadingRuntimeProvider
import streamix.readingcatalog.ManhwaReadReadingRuntimeProvider
import streamix.readingcatalog.ToonGodReadingRuntimeProvider
import streamix.readingcatalog.WebNovelReadingRuntimeProvider
import streamix.readingcatalog.WuxiaWorldReadingRuntimeProvider
import streamix.readingcatalog.BacaLNReadingRuntimeProvider
import streamix.readingcatalog.IdnovelandReadingRuntimeProvider
import streamix.readingcatalog.LexiNovelReadingRuntimeProvider
import streamix.readingcatalog.RanahnovelReadingRuntimeProvider
import streamix.readingcatalog.BubocReadingRuntimeProvider
import streamix.readingcatalog.KimiNovelReadingRuntimeProvider
import streamix.readingcatalog.BacaLightNovelReadingRuntimeProvider
import streamix.readingcatalog.NovelCoolReadingRuntimeProvider
import streamix.readingcatalog.NovelBookIdReadingRuntimeProvider
import streamix.readingcatalog.ShinigamiReadingRuntimeProvider
import streamix.readingcatalog.ReadingProviderRuntimeRace
import streamix.readingcatalog.ReadingRuntimeProvider
import streamix.readingcatalog.ReadingSourceType
import streamix.readingcatalog.ReadingSourceHandler
import java.nio.file.Path
import kotlin.io.path.createDirectories


/** Single source of truth for registered backend reading adapters; exposed for contract tests. */
internal fun createSiteReadingProviders(): List<ReadingRuntimeProvider> {
    val providers = listOf(
        MangaDexReadingRuntimeProvider(),
        BatoReadingRuntimeProvider(),
        ComickReadingRuntimeProvider(),
        MangaParkTvReadingRuntimeProvider(),
        VoraToonReadingRuntimeProvider(),
        ComicasoReadingRuntimeProvider(),
        TheMangaReadingRuntimeProvider(),
        UlasComicReadingRuntimeProvider(),
        ManhwaListReadingRuntimeProvider(),
        CosmicScansIdReadingRuntimeProvider(),
        WebtoonXYZReadingRuntimeProvider(),
        ThunderScansReadingRuntimeProvider(),
        SayManhwaReadingRuntimeProvider(),
        KomikNextGOnlineReadingRuntimeProvider(),
        NoromaxReadingRuntimeProvider(),
        SekteKomikReadingRuntimeProvider(),
        OtaScansReadingRuntimeProvider(),
        MangaDistrictReadingRuntimeProvider(),
        AsuraScansReadingRuntimeProvider(),
        MangaFoxReadingRuntimeProvider(),
        MangaBoltReadingRuntimeProvider(),
        ManhwaGalaxyReadingRuntimeProvider(),
        ManhwaHubReadingRuntimeProvider(),
        ManhwaNexReadingRuntimeProvider(),
        ManhwaOnlineReadingRuntimeProvider(),
        ManhwaZoneReadingRuntimeProvider(),
        RavenScansReadingRuntimeProvider(),
        RageScansReadingRuntimeProvider(),
        MangaHubReadingRuntimeProvider(),
        MangaReaderReadingRuntimeProvider(),
        MangaPlusReadingRuntimeProvider(),
        VizReadingRuntimeProvider(),
        MangaUpReadingRuntimeProvider(),
        ReaperScansReadingRuntimeProvider(),
        JNovelClubReadingRuntimeProvider(),
        BacaKomikReadingRuntimeProvider(),
        MangaCanReadingRuntimeProvider(),
        MangalayReadingRuntimeProvider(),
        NgamenKomikReadingRuntimeProvider(),
        NgomikReadingRuntimeProvider(),
        OkyyKomikReadingRuntimeProvider(),
        ShiyuraSubReadingRuntimeProvider(),
        KomikuAsiaReadingRuntimeProvider(),
        SiikomikReadingRuntimeProvider(),
        NarasiNinjaReadingRuntimeProvider(),
        KomikuReadingRuntimeProvider(),
        BacamiReadingRuntimeProvider(),
        KomikIndoIdReadingRuntimeProvider(),
        KlikMangaReadingRuntimeProvider(),
        ManhwaIndoReadingRuntimeProvider(),
        HolotoonReadingRuntimeProvider(),
        HwagoReadingRuntimeProvider(),
        LumosKomikReadingRuntimeProvider(),
        NatsuReadingRuntimeProvider(),
        IzanamiScansReadingRuntimeProvider(),
        RosyscansReadingRuntimeProvider(),
        IndoWebNovelReadingRuntimeProvider(),
        MtlNovelIdReadingRuntimeProvider(),
        MeioNovelReadingRuntimeProvider(),
        SakuraNovelReadingRuntimeProvider(),
        SekteNovelReadingRuntimeProvider(),
        VanovelReadingRuntimeProvider(),
        WBNoveReadingRuntimeProvider(),
        WestMangaReadingRuntimeProvider(),
        RiztranslationReadingRuntimeProvider(),
        MikoRokuReadingRuntimeProvider(),
        KiryuuReadingRuntimeProvider(),
        KomikStationReadingRuntimeProvider(),
        ApkomikReadingRuntimeProvider(),
        LepoyTlReadingRuntimeProvider(),
        KomikNesiaReadingRuntimeProvider(),
        KuroMangaReadingRuntimeProvider(),
        AinzScansIdReadingRuntimeProvider(),
        RoseveilReadingRuntimeProvider(),
        SoulScansReadingRuntimeProvider(),
        MGKomikReadingRuntimeProvider(),
        MangaFireReadingRuntimeProvider(),
        MangaKReadingRuntimeProvider(),
        MangaPillReadingRuntimeProvider(),
        MangaKatanaReadingRuntimeProvider(),
        MangakakalotReadingRuntimeProvider(),
        ManganatoReadingRuntimeProvider(),
        MangaReadReadingRuntimeProvider(),
        MangaBatReadingRuntimeProvider(),
        MangaHereReadingRuntimeProvider(),
        MangaTaroReadingRuntimeProvider(),
        ToonilyReadingRuntimeProvider(),
        WeebCentralReadingRuntimeProvider(),
        FlameComicsReadingRuntimeProvider(),
        ManhwaBuddyReadingRuntimeProvider(),
        DragonTeaReadingRuntimeProvider(),
        MangaGGReadingRuntimeProvider(),
        MangaNowReadingRuntimeProvider(),
        MangaGekoReadingRuntimeProvider(),
        ManhwaReadReadingRuntimeProvider(),
        ToonGodReadingRuntimeProvider(),
        RoyalRoadReadingRuntimeProvider(),
        NovelBuddyReadingRuntimeProvider(),
        NovelBinReadingRuntimeProvider(),
        NovelFireReadingRuntimeProvider(),
        FreeWebNovelReadingRuntimeProvider(),
        NovelFullReadingRuntimeProvider(),
        NovelHiReadingRuntimeProvider(),
        NovelLightReadingRuntimeProvider(),
        ScribbleHubReadingRuntimeProvider(),
        InkittReadingRuntimeProvider(),
        AllNovelFullReadingRuntimeProvider(),
        BoxNovelReadingRuntimeProvider(),
        LightNovelCaveReadingRuntimeProvider(),
        LightNovelHeavenReadingRuntimeProvider(),
        NovelHallReadingRuntimeProvider(),
        NovelLibReadingRuntimeProvider(),
        NovelRestReadingRuntimeProvider(),
        RanobesReadingRuntimeProvider(),
        ReadNovelFullReadingRuntimeProvider(),
        NovelNinjaReadingRuntimeProvider(),
        WebNovelReadingRuntimeProvider(),
        WuxiaWorldReadingRuntimeProvider(),
        BacaLNReadingRuntimeProvider(),
        IdnovelandReadingRuntimeProvider(),
        LexiNovelReadingRuntimeProvider(),
        RanahnovelReadingRuntimeProvider(),
        BubocReadingRuntimeProvider(),
        KimiNovelReadingRuntimeProvider(),
        BacaLightNovelReadingRuntimeProvider(),
        NovelCoolReadingRuntimeProvider(),
        NovelBookIdReadingRuntimeProvider(),
        ShinigamiReadingRuntimeProvider()
    )
    return providers
}

/**
 * Normalizes historical Catalog provider aliases for adapter dispatch while preserving
 * the rest of the chapter's source order and state. Site adapters select their own source
 * by providerId, so normalizing only the dispatch key is not enough.
 */
internal fun canonicalReadingProviderId(rawProviderId: String): String =
    when (rawProviderId.trim().lowercase(java.util.Locale.ROOT)) {
        "mangadex-manhwa", "mangadex-manhua" -> "mangadex"
        "weebcentral-manhwa" -> "weebcentral"
        "comick-unoriginal" -> "comick"
        "bbato" -> "bato"
        else -> rawProviderId.trim()
    }

internal fun normalizeReadingSourceAlias(
    chapter: ReadingChapterEntry,
    source: ReadingChapterSource,
    normalizedProviderId: String,
): ReadingChapterEntry = chapter.copy(
    sources = chapter.sources.map { candidate ->
        if (candidate.id == source.id) candidate.copy(providerId = normalizedProviderId) else candidate
    }
)

fun main() {
    val host = System.getenv("SHINIGAMI_HOST")?.trim().takeUnless { it.isNullOrBlank() } ?: "0.0.0.0"
    val port = System.getenv("SHINIGAMI_PORT")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 8080
    val dataRoot = Path.of(
        System.getenv("SHINIGAMI_DATA_DIR")?.trim().takeUnless { it.isNullOrBlank() } ?: "backend/data"
    ).toAbsolutePath().normalize()
    dataRoot.createDirectories()

    val http = JvmStreamixHttp()
    StreamixRuntime.installJvm(http)
    val runtime = StreamixRuntime.native()
    val authRuntime = AuthRuntime(dataRoot)
    val catalog = CatalogService(
        repository = FileCatalogRepository(dataRoot.resolve("catalog/episodes.json")),
        extractors = runtime.extractors,
        resolvers = BuiltInCatalogResolvers.create(runtime.extractors)
    )
    val siteReadingProviders = createSiteReadingProviders()
    val readingSourceHandler = ReadingSourceHandler { entry, chapter, source ->
        val providerId = canonicalReadingProviderId(source.providerId)
        val provider = siteReadingProviders.firstOrNull { it.id.equals(providerId, ignoreCase = true) }
        provider?.chapter(entry, normalizeReadingSourceAlias(chapter, source, providerId))
    }
    val readingCatalog = ReadingCatalogService(
        FileReadingCatalogRepository(dataRoot.resolve("catalog/reading.json")),
        handlers = mapOf(
            ReadingSourceType.RESOLVER to readingSourceHandler,
            ReadingSourceType.EXTRACTOR to readingSourceHandler
        ),
        providerRuntime = ReadingProviderRuntimeRace(siteReadingProviders)
    )
    val animeinApiProvider = AnimeinApiProvider(
        http = http,
        extractors = runtime.extractors
    )
    val providerFallback = RuntimeProviderFallback(
        http = http,
        extractors = runtime.extractors
    )
    val service = StreamixService(
        catalog = catalog,
        animeinApiProvider = animeinApiProvider,
        runtimeProviderFallback = providerFallback
    )
    val server = StreamixHttpServer(
        service = service,
        authRuntime = authRuntime,
        catalog = catalog,
        readingCatalog = readingCatalog,
        extractorRegistry = runtime.extractors,
        host = host,
        port = port
    )

    Runtime.getRuntime().addShutdownHook(Thread {
        service.close()
        server.close()
    })

    server.start()
    println("Shinigami backend listening on " + host + ":" + port)
    println("Shinigami backend data root: " + dataRoot)
    Thread.currentThread().join()
}
