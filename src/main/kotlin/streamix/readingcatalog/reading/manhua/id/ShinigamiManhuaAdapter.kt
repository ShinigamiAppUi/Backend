package streamix.readingcatalog

/** Shinigami's native chapter-detail API adapter scoped to Indonesian Manhua Catalog entries. */
class ShinigamiManhuaReadingRuntimeProvider(
    jsonFetcher: ((String) -> String?)? = null,
) : ShinigamiReadingRuntimeProvider(
    jsonFetcher = jsonFetcher,
    supportedKind = ReadingKind.MANHUA,
    id = "shinigami-manhua",
)
