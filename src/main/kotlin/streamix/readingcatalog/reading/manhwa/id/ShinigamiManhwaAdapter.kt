package streamix.readingcatalog

/** Shinigami's native chapter-detail API adapter scoped to Indonesian Manhwa Catalog entries. */
class ShinigamiManhwaReadingRuntimeProvider(
    jsonFetcher: ((String) -> String?)? = null,
) : ShinigamiReadingRuntimeProvider(
    jsonFetcher = jsonFetcher,
    supportedKind = ReadingKind.MANHWA,
    id = "shinigami-manhwa",
)
