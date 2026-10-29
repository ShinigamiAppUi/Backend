package streamix.core

class StreamixExtractorRegistry(extractors: Iterable<StreamixExtractor> = emptyList()) {
    private val entries = linkedMapOf<String, StreamixExtractor>()

    init {
        extractors.forEach(::register)
    }

    fun register(extractor: StreamixExtractor) {
        require(extractor.id.isNotBlank()) { "extractor id must not be blank" }
        require(entries.putIfAbsent(extractor.id.lowercase(), extractor) == null) {
            "Extractor already registered: " + extractor.id
        }
    }

    fun get(id: String): StreamixExtractor? = entries[id.lowercase()]

    fun all(): List<StreamixExtractor> = entries.values.toList()
}
