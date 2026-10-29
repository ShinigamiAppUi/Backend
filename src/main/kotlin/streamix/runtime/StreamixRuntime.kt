package streamix.runtime

import com.baseprovider.streamix.ProviderExtractorsNative
import com.baseprovider.streamix.jvm.JvmStreamixCloudflareResolver
import com.baseprovider.streamix.jvm.JvmStreamixCrypto
import com.baseprovider.streamix.jvm.JvmStreamixDispatchers
import com.baseprovider.streamix.jvm.JvmStreamixJs
import com.baseprovider.streamix.jvm.JvmStreamixJson
import com.baseprovider.streamix.jvm.JvmStreamixWebResolver
import streamix.core.JvmStreamixHttp
import streamix.core.StreamixBuiltInExtractors
import streamix.core.StreamixExtractorRegistry

/**
 * Shinigami runtime registry.
 *
 * No AnimeX/Hatsune provider registry is loaded here. Runtime infrastructure
 * contains extractors only; catalog sources decide DIRECT/RESOLVER/EXTRACTOR.
 */
class StreamixRuntime(
    val extractors: StreamixExtractorRegistry =
        StreamixBuiltInExtractors.create(JvmStreamixHttp()).also { registry ->
            val existingIds = registry.all().map { it.id.lowercase() }.toSet()
            ProviderExtractorsNative.all()
                .filterNot { it.id.lowercase() in existingIds }
                .distinctBy { it.id.lowercase() }
                .forEach(registry::register)
        }
) {
    companion object {
        /**
         * Installs the JVM services required by native OCE extractors.
         */
        fun installJvm(http: JvmStreamixHttp) {
            com.baseprovider.streamix.StreamixRuntime.install(
                http = http,
                json = JvmStreamixJson(),
                crypto = JvmStreamixCrypto(),
                js = JvmStreamixJs(),
                dispatchers = JvmStreamixDispatchers,
                webResolver = JvmStreamixWebResolver(),
                cloudflareResolver = JvmStreamixCloudflareResolver()
            )
        }

        fun native(): StreamixRuntime = StreamixRuntime()
    }
}
