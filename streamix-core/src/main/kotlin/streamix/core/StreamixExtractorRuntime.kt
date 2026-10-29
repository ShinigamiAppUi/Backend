package streamix.core

/**
 * Long-lived extractor runtime.
 *
 * Keeps the JVM extractor registry/router together so providers do not create
 * a new dispatcher for every extraction request.
 */
class StreamixExtractorRuntime(
    registry: StreamixExtractorRegistry
) {
    private val registry = registry
    private val router = StreamixExtractorRouter(registry)

    fun resolve(url: String): StreamixExtractor? =
        router.resolve(url)

    fun get(id: String): StreamixExtractor? =
        registry.get(id)

    suspend fun extract(request: StreamixExtractorRequest): StreamixExtractorResult =
        router.extract(request)

    suspend fun extract(id: String, request: StreamixExtractorRequest): StreamixExtractorResult =
        registry.get(id)?.extract(request) ?: StreamixExtractorResult()
}
