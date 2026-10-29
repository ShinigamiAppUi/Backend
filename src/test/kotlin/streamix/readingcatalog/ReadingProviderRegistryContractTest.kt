package streamix.readingcatalog

import streamix.api.createSiteReadingProviders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadingProviderRegistryContractTest {
    @Test
    fun registeredProviderIdsAreNonBlankAndUnique() {
        val providers = createSiteReadingProviders()
        val ids = providers.map { it.id.trim().lowercase(java.util.Locale.ROOT) }

        assertTrue(providers.size >= 100, "Expected the established 100+ adapter registry")
        assertTrue(ids.all(String::isNotBlank), "Every registered provider must have a nonblank ID")
        assertEquals(ids.size, ids.toSet().size, "Provider IDs must be unique ignoring case")
    }

    @Test
    fun registeredComicProviderIdsMatchTheAuditedInventory() {
        val novelOnlyProviderIds = setOf(
            "j-novel-club", "indowebnovel", "mtl-novel-id", "meionovel",
            "sakuranovel", "sekte-novel", "vanovel", "wbnove", "webnovel",
            "wuxiaworld", "baca-light-novel", "novelcool", "novelbookid"
        )
        val actual = createSiteReadingProviders()
            .map { it.id.lowercase(java.util.Locale.ROOT) }
            .filterNot { it in novelOnlyProviderIds }
            .toSet()
        val expected = setOf(
            "ainz-scans-id",
            "apkomik",
            "asura-scans",
            "bacakomik",
            "bacami",
            "bato",
            "comicaso",
            "comick",
            "cosmicscans",
            "dragontea",
            "flame-comics",
            "holotoon",
            "hwago",
            "izanami-scans",
            "kiryuu",
            "klikmanga",
            "komik-next-g-online",
            "komik-station",
            "komikindoid",
            "komiknesia",
            "komiku",
            "komiku-asia",
            "kuro-manga",
            "lepoytl",
            "lumoskomik",
            "manga-can",
            "mangadistrict",
            "mangabat",
            "mangabolt",
            "mangadex",
            "mangafire",
            "mangafox",
            "mangageko",
            "mangahere",
            "mangahub",
            "mangak",
            "mangakakalot",
            "mangakatana",
            "mangalay",
            "manganato",
            "manganow",
            "mangapark-tv",
            "mangapill",
            "mangaplus",
            "mangaread",
            "mangareader",
            "mangataro",
            "mangaup",
            "manggagg",
            "manhwa-indo",
            "manhwa-list",
            "manhwabuddy",
            "manhwagalaxy",
            "manhwahub",
            "manhwanex",
            "manhwaonline",
            "manhwaread",
            "manhwazone",
            "mg-komik",
            "mikoroku",
            "narasininja",
            "natsu",
            "ngamenkomik",
            "ngomik",
            "noromax",
            "okyykomik",
            "ota-scans",
            "rage-scans",
            "raven-scans",
            "reaper-scans",
            "riztranslation",
            "roseveil",
            "rosyscans",
            "saymanhwa",
            "sekte-komik",
            "shinigami",
            "shiyurasub",
            "siikomik",
            "soul-scans",
            "the-manga",
            "thunder-scans",
            "toongod",
            "toonily",
            "ulas-comic",
            "viz",
            "voratoon",
            "webtoonxyz",
            "weebcentral",
            "west-manga"
        )
        assertEquals(expected, actual, "Comic provider registry differs from audited inventory; missing=${expected - actual}, unexpected=${actual - expected}")
        assertTrue("mangadistrict" in actual, "MangaDistrict runtime ID must match the Android catalog descriptor")
        assertTrue("manga-district" !in actual, "Do not introduce a noncanonical MangaDistrict alias")
    }

    @Test
    fun novelAdaptersRemainRegistered() {
        val ids = createSiteReadingProviders().map { it.id.lowercase(java.util.Locale.ROOT) }.toSet()

        assertTrue("indowebnovel" in ids)
        assertTrue("mtl-novel-id" in ids)
        assertTrue("meionovel" in ids)
        assertTrue("sakuranovel" in ids)
        assertTrue("sekte-novel" in ids)
        assertTrue("vanovel" in ids)
        assertTrue("wbnove" in ids)
        assertTrue("webnovel" in ids)
        assertTrue("wuxiaworld" in ids)
        assertTrue("baca-light-novel" in ids)
        assertTrue("novelcool" in ids)
        assertTrue("novelbookid" in ids)
    }
}
