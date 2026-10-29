package streamix.core

import streamix.core.extractors.FiledonExtractor
import streamix.core.extractors.JwPlayerExtractor
import streamix.core.extractors.Mp4UploadExtractor
import streamix.core.extractors.OtakuAnimasuPageExtractor

object StreamixBuiltInExtractors {
    fun create(http: StreamixHttp = JvmStreamixHttp()): StreamixExtractorRegistry {
        val registry = StreamixExtractorRegistry(
            listOf(
                FiledonExtractor(http),
                Mp4UploadExtractor(http),
                JwPlayerExtractor(
                    http = http,
                    domains = setOf(
                        "desustream.me",
                        "desustream.info",
                        "desustream.net"
                    )
                )
            )
        )
        registry.register(OtakuAnimasuPageExtractor(http, registry))
        return registry
    }
}
