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
import streamix.readingcatalog.ReadingKind
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
import streamix.readingcatalog.ManhwaZReadingRuntimeProvider
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
import streamix.readingcatalog.LightNovelTranslationsReadingRuntimeProvider
import streamix.readingcatalog.LnMTLReadingRuntimeProvider
import streamix.readingcatalog.LNORIReadingRuntimeProvider
import streamix.readingcatalog.LulloBoxReadingRuntimeProvider
import streamix.readingcatalog.MistmintHavenReadingRuntimeProvider
import streamix.readingcatalog.NobleMTLReadingRuntimeProvider
import streamix.readingcatalog.NoiceTranslationsReadingRuntimeProvider
import streamix.readingcatalog.NovelArrowReadingRuntimeProvider
import streamix.readingcatalog.WuxiaSpaceReadingRuntimeProvider
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
import streamix.readingcatalog.HiperdexReadingRuntimeProvider
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
import streamix.readingcatalog.WBNovelReadingRuntimeProvider
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
import streamix.readingcatalog.ManhuaHotReadingRuntimeProvider
import streamix.readingcatalog.ManhuaNextReadingRuntimeProvider
import streamix.readingcatalog.ManhuaPlusReadingRuntimeProvider
import streamix.readingcatalog.ManhuaUSReadingRuntimeProvider
import streamix.readingcatalog.ManhuaTopReadingRuntimeProvider
import streamix.readingcatalog.AuntManhwaReadingRuntimeProvider
import streamix.readingcatalog.ManhwaManhuaReadingRuntimeProvider
import streamix.readingcatalog.ManhwaTopReadingRuntimeProvider
import streamix.readingcatalog.Manhwa68ReadingRuntimeProvider
import streamix.readingcatalog.ManhwaDenReadingRuntimeProvider
import streamix.readingcatalog.MurimScanReadingRuntimeProvider
import streamix.readingcatalog.TopManhuaReadingRuntimeProvider
import streamix.readingcatalog.TopManhuaFanReadingRuntimeProvider
import streamix.readingcatalog.TopManhuaNetReadingRuntimeProvider
import streamix.readingcatalog.ManhuaZongheReadingRuntimeProvider
import streamix.readingcatalog.ZinmangaReadingRuntimeProvider
import streamix.readingcatalog.ManhwaUSReadingRuntimeProvider
import streamix.readingcatalog.ManhwaddictReadingRuntimeProvider
import streamix.readingcatalog.KomiktapReadingRuntimeProvider
import streamix.readingcatalog.KomikmuReadingRuntimeProvider
import streamix.readingcatalog.MangasusuReadingRuntimeProvider
import streamix.readingcatalog.KomikzoidReadingRuntimeProvider
import streamix.readingcatalog.MangasushiReadingRuntimeProvider
import streamix.readingcatalog.MangaTrendReadingRuntimeProvider
import streamix.readingcatalog.MangaManiacsReadingRuntimeProvider
import streamix.readingcatalog.MangaDemonReadingRuntimeProvider
import streamix.readingcatalog.MangaUnoReadingRuntimeProvider
import streamix.readingcatalog.RyukomikReadingRuntimeProvider
import streamix.readingcatalog.MangatownReadingRuntimeProvider
import streamix.readingcatalog.ManhwaLandMomReadingRuntimeProvider
import streamix.readingcatalog.ManhwaDesuReadingRuntimeProvider
import streamix.readingcatalog.LianScansReadingRuntimeProvider
import streamix.readingcatalog.KomikmuManhwaReadingRuntimeProvider
import streamix.readingcatalog.ManhwaToonReadingRuntimeProvider
import streamix.readingcatalog.Manhwa18TodayReadingRuntimeProvider
import streamix.readingcatalog.Manhwa18TXReadingRuntimeProvider
import streamix.readingcatalog.ManhwaComicsReadingRuntimeProvider
import streamix.readingcatalog.Manhwa18ReadingRuntimeProvider
import streamix.readingcatalog.KomiktapManhuaReadingRuntimeProvider
import streamix.readingcatalog.KomikmuManhuaReadingRuntimeProvider
import streamix.readingcatalog.ManhwaReadsReadingRuntimeProvider
import streamix.readingcatalog.KomikuComManhuaReadingRuntimeProvider
import streamix.readingcatalog.CManhuaReadingRuntimeProvider
import streamix.readingcatalog.CitrusAuroraReadingRuntimeProvider
import streamix.readingcatalog.DaoTranslateReadingRuntimeProvider
import streamix.readingcatalog.DivineDaoLibraryReadingRuntimeProvider
import streamix.readingcatalog.DragonholicReadingRuntimeProvider
import streamix.readingcatalog.DuskBlossomsReadingRuntimeProvider
import streamix.readingcatalog.EtudeTranslationsReadingRuntimeProvider
import streamix.readingcatalog.FoxaholicReadingRuntimeProvider
import streamix.readingcatalog.WuxiafoxReadingRuntimeProvider
import streamix.readingcatalog.SweetEscapeReadingRuntimeProvider
import streamix.readingcatalog.GalaxyTranslationsReadingRuntimeProvider
import streamix.readingcatalog.FoxtellerReadingRuntimeProvider
import streamix.readingcatalog.IndraTranslationsReadingRuntimeProvider
import streamix.readingcatalog.LazyGirlTranslationsReadingRuntimeProvider
import streamix.readingcatalog.ArchiveOfOurOwnReadingRuntimeProvider
import streamix.readingcatalog.AkknovelReadingRuntimeProvider
import streamix.readingcatalog.AllNovelReadingRuntimeProvider
import streamix.readingcatalog.ArcaneTranslationsReadingRuntimeProvider
import streamix.readingcatalog.BlumeVerseReadingRuntimeProvider
import streamix.readingcatalog.ChrysanthemumGardenReadingRuntimeProvider
import streamix.readingcatalog.CPUNovelReadingRuntimeProvider
import streamix.readingcatalog.DaoistQuestReadingRuntimeProvider
import streamix.readingcatalog.EternaluneReadingRuntimeProvider
import streamix.readingcatalog.FanNovelReadingRuntimeProvider
import streamix.readingcatalog.FenrirRealmReadingRuntimeProvider
import streamix.readingcatalog.GuavareadReadingRuntimeProvider
import streamix.readingcatalog.HiraethTranslationReadingRuntimeProvider
import streamix.readingcatalog.HyacinthInBloomReadingRuntimeProvider
import streamix.readingcatalog.INovelTranslationReadingRuntimeProvider
import streamix.readingcatalog.IppoTranslationsReadingRuntimeProvider
import streamix.readingcatalog.KDTNovelsReadingRuntimeProvider
import streamix.readingcatalog.KeopiTranslationsReadingRuntimeProvider
import streamix.readingcatalog.KnoxtReadingRuntimeProvider
import streamix.readingcatalog.LibReadReadingRuntimeProvider
import streamix.readingcatalog.OceanTalesReadingRuntimeProvider
import streamix.readingcatalog.MTLNovelEnglishReadingRuntimeProvider
import streamix.readingcatalog.MVLEmpyrReadingRuntimeProvider
import streamix.readingcatalog.PastelTalesReadingRuntimeProvider
import streamix.readingcatalog.PawReadReadingRuntimeProvider
import streamix.readingcatalog.PenguinSquadReadingRuntimeProvider
import streamix.readingcatalog.RanovelReadingRuntimeProvider
import streamix.readingcatalog.ReadFromReadingRuntimeProvider
import streamix.readingcatalog.RequiemTranslationsReadingRuntimeProvider
import streamix.readingcatalog.SleepyTranslationsReadingRuntimeProvider
import streamix.readingcatalog.SonicMTLReadingRuntimeProvider
import streamix.readingcatalog.TransWeaverReadingRuntimeProvider
import streamix.readingcatalog.UniversalNovelReadingRuntimeProvider
import streamix.readingcatalog.VandyTranslateReadingRuntimeProvider
import streamix.readingcatalog.WebNovelTranslationReadingRuntimeProvider
import streamix.readingcatalog.WhiteMoonlightNovelsReadingRuntimeProvider
import streamix.readingcatalog.WitchCultTranslationsReadingRuntimeProvider
import streamix.readingcatalog.WooksTeahouseReadingRuntimeProvider
import streamix.readingcatalog.WTRLABReadingRuntimeProvider
import streamix.readingcatalog.WuxiaBoxReadingRuntimeProvider
import streamix.readingcatalog.ZetroTranslationReadingRuntimeProvider
import streamix.readingcatalog.AsianNovelReadingRuntimeProvider
import streamix.readingcatalog.BakaTsukiReadingRuntimeProvider
import streamix.readingcatalog.ByteTranslationsReadingRuntimeProvider
import streamix.readingcatalog.CrimsonScrollsReadingRuntimeProvider
import streamix.readingcatalog.HoneyfeedReadingRuntimeProvider
import streamix.readingcatalog.NoBadNovelReadingRuntimeProvider
import streamix.readingcatalog.FuckNovelpiaReadingRuntimeProvider
import streamix.readingcatalog.JadeScrollsReadingRuntimeProvider
import streamix.readingcatalog.NovelsPlReadingRuntimeProvider
import streamix.readingcatalog.OpenQuillReadingRuntimeProvider
import streamix.readingcatalog.ReadHiveReadingRuntimeProvider
import streamix.readingcatalog.ReLibraryReadingRuntimeProvider
import streamix.readingcatalog.RechaptersReadingRuntimeProvider
import streamix.readingcatalog.OrvReaderReadingRuntimeProvider
import streamix.readingcatalog.NyxScansReadingRuntimeProvider
import streamix.readingcatalog.SrankMangaReadingRuntimeProvider
import streamix.readingcatalog.StorySeedlingReadingRuntimeProvider
import streamix.readingcatalog.SufficientVelocityReadingRuntimeProvider
import streamix.readingcatalog.TomatoMTLReadingRuntimeProvider
import streamix.readingcatalog.TranslatinoReadingRuntimeProvider
import streamix.readingcatalog.WattpadReadingRuntimeProvider
import streamix.readingcatalog.WoopReadReadingRuntimeProvider
import streamix.readingcatalog.WordExcerptReadingRuntimeProvider
import streamix.readingcatalog.WuxiaClickReadingRuntimeProvider
import streamix.readingcatalog.WuxiaDreamsReadingRuntimeProvider
import streamix.readingcatalog.EmpireNovelReadingRuntimeProvider
import streamix.readingcatalog.FAQWikiReadingRuntimeProvider
import streamix.readingcatalog.R18NovelReadingRuntimeProvider
import streamix.readingcatalog.ReadNovelMTLReadingRuntimeProvider
import streamix.readingcatalog.ReigokaiTranslationsReadingRuntimeProvider
import streamix.readingcatalog.ShanghaiFantasyReadingRuntimeProvider
import streamix.readingcatalog.XNovelReadingRuntimeProvider
import streamix.readingcatalog.KomikhwaReadingRuntimeProvider
import streamix.readingcatalog.WeTriedTlsReadingRuntimeProvider
import streamix.readingcatalog.SainokanTlsReadingRuntimeProvider
import streamix.readingcatalog.ReadingProviderRuntimeRace
import streamix.readingcatalog.AtsumaruNovelsReadingRuntimeProvider
import streamix.readingcatalog.BotiTranslationReadingRuntimeProvider
import streamix.readingcatalog.BrightNovelsReadingRuntimeProvider
import streamix.readingcatalog.ChikariReadingRuntimeProvider
import streamix.readingcatalog.CyrisiaReadingRuntimeProvider
import streamix.readingcatalog.FansMTLReadingRuntimeProvider
import streamix.readingcatalog.FictionZoneReadingRuntimeProvider
import streamix.readingcatalog.FreeGoodNovelReadingRuntimeProvider
import streamix.readingcatalog.GenesisStudioReadingRuntimeProvider
import streamix.readingcatalog.GreenzReadingRuntimeProvider
import streamix.readingcatalog.HelioScansReadingRuntimeProvider
import streamix.readingcatalog.KatReadingCafeReadingRuntimeProvider
import streamix.readingcatalog.KonkonReadingRuntimeProvider
import streamix.readingcatalog.KuuPressReadingRuntimeProvider
import streamix.readingcatalog.LeafStudioReadingRuntimeProvider
import streamix.readingcatalog.LightNovelToReadingRuntimeProvider
import streamix.readingcatalog.LNCrawlerReadingRuntimeProvider
import streamix.readingcatalog.LunoxScansReadingRuntimeProvider
import streamix.readingcatalog.MTLBooksReadingRuntimeProvider
import streamix.readingcatalog.MTLWuxiaReadingRuntimeProvider
import streamix.readingcatalog.MZNovelsReadingRuntimeProvider
import streamix.readingcatalog.NovelArchiveReadingRuntimeProvider
import streamix.readingcatalog.NovelDexReadingRuntimeProvider
import streamix.readingcatalog.NovelLiveReadingRuntimeProvider
import streamix.readingcatalog.NovelLunarReadingRuntimeProvider
import streamix.readingcatalog.NovelPhoenixNetReadingRuntimeProvider
import streamix.readingcatalog.NovelPingReadingRuntimeProvider
import streamix.readingcatalog.NovelsHavenReadingRuntimeProvider
import streamix.readingcatalog.NovelsOnlineReadingRuntimeProvider
import streamix.readingcatalog.ReadingRuntimeProvider
import streamix.readingcatalog.ReadingSourceType
import streamix.readingcatalog.ReadingSourceHandler
import java.nio.file.Path
import kotlin.io.path.createDirectories


/** Single source of truth for registered backend reading adapters; exposed for contract tests. */
internal fun createSiteReadingProviders(): List<ReadingRuntimeProvider> {
    val providers = listOf(
        MangaDexReadingRuntimeProvider(),
        CManhuaReadingRuntimeProvider(),
        KomikuComManhuaReadingRuntimeProvider(),
        ManhwaReadsReadingRuntimeProvider(),
        KomikmuManhuaReadingRuntimeProvider(),
        KomiktapManhuaReadingRuntimeProvider(),
        Manhwa18ReadingRuntimeProvider(),
        ManhwaComicsReadingRuntimeProvider(),
        Manhwa18TXReadingRuntimeProvider(),
        Manhwa18TodayReadingRuntimeProvider(),
        ManhwaToonReadingRuntimeProvider(),
        KomikmuManhwaReadingRuntimeProvider(),
        LianScansReadingRuntimeProvider(),
        ManhwaDesuReadingRuntimeProvider(),
        ManhwaLandMomReadingRuntimeProvider(),
        MangatownReadingRuntimeProvider(),
        RyukomikReadingRuntimeProvider(),
        MangaUnoReadingRuntimeProvider(),
        MangaDemonReadingRuntimeProvider(),
        MangaManiacsReadingRuntimeProvider(),
        MangaTrendReadingRuntimeProvider(),
        MangasushiReadingRuntimeProvider(),
        KomikzoidReadingRuntimeProvider(),
        MangasusuReadingRuntimeProvider(),
        KomikmuReadingRuntimeProvider(),
        KomiktapReadingRuntimeProvider(),
        ManhwaddictReadingRuntimeProvider(),
        ManhwaUSReadingRuntimeProvider(),
        ZinmangaReadingRuntimeProvider(),
        ManhuaZongheReadingRuntimeProvider(),
        TopManhuaNetReadingRuntimeProvider(),
        TopManhuaFanReadingRuntimeProvider(),
        TopManhuaReadingRuntimeProvider(),
        MurimScanReadingRuntimeProvider(),
        ManhwaDenReadingRuntimeProvider(),
        Manhwa68ReadingRuntimeProvider(),
        ManhwaTopReadingRuntimeProvider(),
        ManhwaManhuaReadingRuntimeProvider(),
        AuntManhwaReadingRuntimeProvider(),
        ManhuaUSReadingRuntimeProvider(),
        ManhuaTopReadingRuntimeProvider(),
        ManhuaPlusReadingRuntimeProvider(),
        ManhuaNextReadingRuntimeProvider(),
        ManhuaHotReadingRuntimeProvider(),
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
        ManhwaZReadingRuntimeProvider(),
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
        KomikhwaReadingRuntimeProvider(),
        HolotoonReadingRuntimeProvider(),
        HiperdexReadingRuntimeProvider(),
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
        WBNovelReadingRuntimeProvider(),
        WeTriedTlsReadingRuntimeProvider(),
        SainokanTlsReadingRuntimeProvider(),
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
        HoneyfeedReadingRuntimeProvider(),
        CrimsonScrollsReadingRuntimeProvider(),
        ByteTranslationsReadingRuntimeProvider(),
        BakaTsukiReadingRuntimeProvider(),
        AsianNovelReadingRuntimeProvider(),
        ZetroTranslationReadingRuntimeProvider(),
        WuxiaBoxReadingRuntimeProvider(),
        WTRLABReadingRuntimeProvider(),
        WooksTeahouseReadingRuntimeProvider(),
        WitchCultTranslationsReadingRuntimeProvider(),
        WhiteMoonlightNovelsReadingRuntimeProvider(),
        WebNovelTranslationReadingRuntimeProvider(),
        VandyTranslateReadingRuntimeProvider(),
        UniversalNovelReadingRuntimeProvider(),
        TransWeaverReadingRuntimeProvider(),
        SonicMTLReadingRuntimeProvider(),
        SleepyTranslationsReadingRuntimeProvider(),
        RequiemTranslationsReadingRuntimeProvider(),
        ReadFromReadingRuntimeProvider(),
        RanovelReadingRuntimeProvider(),
        PenguinSquadReadingRuntimeProvider(),
        PawReadReadingRuntimeProvider(),
        PastelTalesReadingRuntimeProvider(),
        MVLEmpyrReadingRuntimeProvider(),
        MTLNovelEnglishReadingRuntimeProvider(),
        OceanTalesReadingRuntimeProvider(),
        LibReadReadingRuntimeProvider(),
        KnoxtReadingRuntimeProvider(),
        KeopiTranslationsReadingRuntimeProvider(),
        KDTNovelsReadingRuntimeProvider(),
        IppoTranslationsReadingRuntimeProvider(),
        INovelTranslationReadingRuntimeProvider(),
        HyacinthInBloomReadingRuntimeProvider(),
        HiraethTranslationReadingRuntimeProvider(),
        GuavareadReadingRuntimeProvider(),
        GalaxyTranslationsReadingRuntimeProvider(),
        FenrirRealmReadingRuntimeProvider(),
        FanNovelReadingRuntimeProvider(),
        EternaluneReadingRuntimeProvider(),
        DaoistQuestReadingRuntimeProvider(),
        CPUNovelReadingRuntimeProvider(),
        ChrysanthemumGardenReadingRuntimeProvider(),
        BlumeVerseReadingRuntimeProvider(),
        ArcaneTranslationsReadingRuntimeProvider(),
        AllNovelReadingRuntimeProvider(),
        ArchiveOfOurOwnReadingRuntimeProvider(),
        AkknovelReadingRuntimeProvider(),
        LazyGirlTranslationsReadingRuntimeProvider(),
        IndraTranslationsReadingRuntimeProvider(),
        FoxaholicReadingRuntimeProvider(),
        FoxtellerReadingRuntimeProvider(),
        EtudeTranslationsReadingRuntimeProvider(),
        DuskBlossomsReadingRuntimeProvider(),
        DragonholicReadingRuntimeProvider(),
        DivineDaoLibraryReadingRuntimeProvider(),
        DaoTranslateReadingRuntimeProvider(),
        CitrusAuroraReadingRuntimeProvider(),
        AtsumaruNovelsReadingRuntimeProvider(),
        BotiTranslationReadingRuntimeProvider(),
        BrightNovelsReadingRuntimeProvider(),
        ChikariReadingRuntimeProvider(),
        CyrisiaReadingRuntimeProvider(),
        FansMTLReadingRuntimeProvider(),
        FictionZoneReadingRuntimeProvider(),
        FreeGoodNovelReadingRuntimeProvider(),
        GenesisStudioReadingRuntimeProvider(),
        GreenzReadingRuntimeProvider(),
        HelioScansReadingRuntimeProvider(),
        KatReadingCafeReadingRuntimeProvider(),
        KonkonReadingRuntimeProvider(),
        KuuPressReadingRuntimeProvider(),
        LeafStudioReadingRuntimeProvider(),
        LightNovelToReadingRuntimeProvider(),
        LNCrawlerReadingRuntimeProvider(),
        LunoxScansReadingRuntimeProvider(),
        MTLBooksReadingRuntimeProvider(),
        MTLWuxiaReadingRuntimeProvider(),
        MZNovelsReadingRuntimeProvider(),
        NoBadNovelReadingRuntimeProvider(),
        FuckNovelpiaReadingRuntimeProvider(),
        JadeScrollsReadingRuntimeProvider(),
        NovelsPlReadingRuntimeProvider(),
        OpenQuillReadingRuntimeProvider(),
        ReadHiveReadingRuntimeProvider(),
        ReLibraryReadingRuntimeProvider(),
        RechaptersReadingRuntimeProvider(),
        OrvReaderReadingRuntimeProvider(),
        NyxScansReadingRuntimeProvider(),
        SrankMangaReadingRuntimeProvider(),
        StorySeedlingReadingRuntimeProvider(),
        SweetEscapeReadingRuntimeProvider(),
        SufficientVelocityReadingRuntimeProvider(),
        TomatoMTLReadingRuntimeProvider(),
        TranslatinoReadingRuntimeProvider(),
        WattpadReadingRuntimeProvider(),
        WoopReadReadingRuntimeProvider(),
        WordExcerptReadingRuntimeProvider(),
        WuxiaClickReadingRuntimeProvider(),
        WuxiaDreamsReadingRuntimeProvider(),
        EmpireNovelReadingRuntimeProvider(),
        FAQWikiReadingRuntimeProvider(),
        R18NovelReadingRuntimeProvider(),
        ReadNovelMTLReadingRuntimeProvider(),
        ReigokaiTranslationsReadingRuntimeProvider(),
        ShanghaiFantasyReadingRuntimeProvider(),
        XNovelReadingRuntimeProvider(),
        NovelArchiveReadingRuntimeProvider(),
        NovelDexReadingRuntimeProvider(),
        NovelLiveReadingRuntimeProvider(),
        NovelLunarReadingRuntimeProvider(),
        NovelPhoenixNetReadingRuntimeProvider(),
        NovelPingReadingRuntimeProvider(),
        NovelsHavenReadingRuntimeProvider(),
        NovelsOnlineReadingRuntimeProvider(),
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
        LightNovelTranslationsReadingRuntimeProvider(),
        LnMTLReadingRuntimeProvider(),
        LNORIReadingRuntimeProvider(),
        LulloBoxReadingRuntimeProvider(),
        MistmintHavenReadingRuntimeProvider(),
        NobleMTLReadingRuntimeProvider(),
        NoiceTranslationsReadingRuntimeProvider(),
        NovelArrowReadingRuntimeProvider(),
        WuxiaSpaceReadingRuntimeProvider(),
        WebNovelReadingRuntimeProvider(),
        WuxiaWorldReadingRuntimeProvider(),
        WuxiafoxReadingRuntimeProvider(),
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
internal fun canonicalReadingProviderId(rawProviderId: String, kind: ReadingKind? = null): String =
    when (rawProviderId.trim().lowercase(java.util.Locale.ROOT)) {
        "mangadex-manhwa", "mangadex-manhua" -> "mangadex"
        "weebcentral-manhwa" -> "weebcentral"
        "new-manhwa" -> "saymanhwa"
        "komiku.com", "komiku-com" -> if (kind == ReadingKind.MANHUA) "komiku-com-manhua" else "komiku-asia"
        "mangaread.org", "manga-read-org" -> "mangaread"
        "comick-fanmade", "comick-fan-made" -> "comick"
        "comick-unoriginal" -> "comick"
        "bbato" -> "bato"
        "wbnove" -> "wbnovel"
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
        val providerId = canonicalReadingProviderId(source.providerId, entry.kind)
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
