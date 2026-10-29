package streamix.runtime

import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import streamix.core.JvmStreamixHttp
import streamix.core.StreamixBuiltInExtractors
import streamix.runtime.StreamixRuntime

class A002LiveProviderStreamTest {
    private val probes = listOf("One Piece", "Naruto", "Boruto")
    private val providerTimeoutMs = 60_000L

    @Test fun animasuMustProduceAStream() = runBlocking { assertAnimasuHostBoundary() }
    @Test fun anisailMustProduceAStream() = runBlocking { assertProviderStream("anisail") }
    @Test fun anoboyMustProduceAStream() = runBlocking { assertProviderStream("anoboy") }
    @Test fun otakudesuMustProduceAStream() = runBlocking { assertProviderStream("otakudesu") }
    @Test fun samehadakuMustProduceAStream() = runBlocking { assertProviderStream("samehadaku") }
    @Test fun oploverzMustProduceAStream() = runBlocking { assertProviderStream("oploverz") }
    @Test fun animeindoMustProduceAStream() = runBlocking { assertProviderStream("animeindo") }
    @Test fun gomunimeMustProduceAStream() = runBlocking { assertProviderStream("gomunime") }

    private suspend fun assertAnimasuHostBoundary() {
        val http = JvmStreamixHttp()
        StreamixRuntime.installJvm(http)
        val site = defaultSites(http).first { it.id == "animasu" }
        val providerExtractors = RuntimeProviderExtractorRegistry(http)
        val providerExtractor = providerExtractors.get("animasu")
            ?: error("PROVIDER_EXTRACTOR_NOT_REGISTERED")
        val episodePage = site.findEpisodePage("One Piece", 1)
            ?: error("EPISODE_PAGE_NOT_FOUND:One Piece")
        val candidates = providerExtractor.extract(episodePage)
        println("A-002 animasu HOST_AUDIT EPISODE_PAGE=" + episodePage + " CANDIDATES=" + candidates.size)

        val nativeRegistryClass = Class.forName("com.baseprovider.streamix.ProviderExtractorsNative")
        val nativeInstance = nativeRegistryClass.getField("INSTANCE").get(null)
        @Suppress("UNCHECKED_CAST")
        val nativeExtractors =
            nativeRegistryClass.getMethod("all").invoke(nativeInstance) as List<streamix.core.StreamixExtractor>
        val extractors = StreamixBuiltInExtractors.create(http).also { registry ->
            val existingIds = registry.all().map { it.id.lowercase() }.toSet()
            nativeExtractors
                .filterNot { it.id.lowercase() in existingIds }
                .distinctBy { it.id.lowercase() }
                .forEach(registry::register)
        }
        val router = streamix.core.StreamixExtractorRouter(extractors)

        var attempted = 0
        var hostStreamObserved = false
        for ((index, candidate) in candidates.take(8).withIndex()) {
            val matches = router.resolveAll(candidate.url)
            println(
                "A-002 animasu HOST_AUDIT CANDIDATE[" + index + "]=" + candidate.url +
                    " EXTRACTORS=" + matches.joinToString(",") { it.id }
            )
            for (extractor in matches) {
                attempted++
                val result = runCatching {
                    withTimeout(5_000L) {
                        extractor.extract(
                            streamix.core.StreamixExtractorRequest(
                                url = candidate.url,
                                providerId = "runtime-provider:animasu",
                                referer = candidate.referer ?: episodePage
                            )
                        )
                    }
                }.getOrElse { error ->
                    println(
                        "A-002 animasu HOST_AUDIT EXTRACTOR=" + extractor.id +
                            " ERROR=" + error::class.simpleName + ":" + error.message
                    )
                    null
                }
                if (result != null) {
                    println(
                        "A-002 animasu HOST_AUDIT EXTRACTOR=" + extractor.id +
                            " STREAMS=" + result.streams.size
                    )
                    result.streams.take(4).forEachIndexed { streamIndex, stream ->
                        println(
                            "A-002 animasu HOST_AUDIT STREAM[" + streamIndex + "]" +
                                " type=" + stream.type + " quality=" + stream.quality +
                                " url=" + stream.url
                        )
                    }
                    if (result.streams.isNotEmpty()) {
                        hostStreamObserved = true
                        break
                    }
                }
            }
            if (hostStreamObserved) break
        }

        // A host extractor result alone is diagnostic evidence, not an A-002
        // pass. The production gate must prove the provider fallback returns
        // the same playable StreamRef contract consumed by the runtime.
        val streamRefs = withTimeout(providerTimeoutMs) {
            RuntimeProviderFallback(
                http = http,
                extractors = extractors,
                sites = listOf(site),
                providerExtractors = providerExtractors
            ).streams("One Piece", 1)
        }
        println(
            "A-002 animasu STREAMREF_AUDIT count=" + streamRefs.size +
                " hostStreamObserved=" + hostStreamObserved + " hostExtractorsAttempted=" + attempted
        )
        streamRefs.take(8).forEachIndexed { streamIndex, stream ->
            println(
                "A-002 animasu STREAMREF[" + streamIndex + "]" +
                    " type=" + stream.type + " quality=" + stream.quality + " url=" + stream.url
            )
        }
        if (streamRefs.isEmpty()) {
            error("ANIMASU_RUNTIME_NO_STREAMREF_AFTER_GLOBAL_RESOLUTION")
        }
        println("A-002 RESULT animasu=PASS")
    }

    private suspend fun assertProviderStream(providerId: String) {
        val http = JvmStreamixHttp()
        StreamixRuntime.installJvm(http)
        val sites = defaultSites(http)
        val site = sites.firstOrNull { it.id == providerId }
            ?: throw IllegalStateException("A-002 PROVIDER_NOT_REGISTERED:$providerId")
        val providerExtractors = RuntimeProviderExtractorRegistry(http)
        val nativeRegistryClass = Class.forName("com.baseprovider.streamix.ProviderExtractorsNative")
        val nativeInstance = nativeRegistryClass.getField("INSTANCE").get(null)
        @Suppress("UNCHECKED_CAST")
        val nativeExtractors =
            nativeRegistryClass.getMethod("all").invoke(nativeInstance) as List<streamix.core.StreamixExtractor>

        val extractors = StreamixBuiltInExtractors.create(http).also { registry ->
            val existingIds = registry.all().map { it.id.lowercase() }.toSet()
            nativeExtractors
                .filterNot { it.id.lowercase() in existingIds }
                .distinctBy { it.id.lowercase() }
                .forEach(registry::register)
        }

        var lastFailure: Throwable = IllegalStateException("NO_PROBE_ATTEMPTED")
        var passed = false
        withTimeout(providerTimeoutMs) {
            for (probe in probes) {
                try {
                    val episodePage = site.findEpisodePage(probe, 1)
                        ?: throw IllegalStateException("EPISODE_PAGE_NOT_FOUND:$probe")
                    println("A-002 $providerId PROBE=$probe EPISODE_PAGE=$episodePage")

                    val providerExtractor = providerExtractors.get(providerId)
                        ?: throw IllegalStateException("PROVIDER_EXTRACTOR_NOT_REGISTERED")
                    val candidates = providerExtractor.extract(episodePage)
                    println("A-002 $providerId PROBE=$probe CANDIDATES=${candidates.size}")
                    candidates.take(8).forEachIndexed { index, candidate ->
                        println("A-002 $providerId PROBE=$probe CANDIDATE[\$index]=${candidate.url}")
                    }
                    if (candidates.isEmpty()) {
                        throw IllegalStateException("PROVIDER_EXTRACTOR_EMPTY:$probe")
                    }

                    val streams = RuntimeProviderFallback(
                        http = http,
                        extractors = extractors,
                        sites = listOf(site),
                        providerExtractors = providerExtractors
                    ).streams(probe, 1)

                    if (streams.isNotEmpty()) {
                        streams.take(8).forEachIndexed { index, stream ->
                            println(
                                "A-002 $providerId PROBE=$probe STREAM[\$index] " +
                                    "type=${stream.type} quality=${stream.quality} url=${stream.url}"
                            )
                        }
                        println("A-002 RESULT $providerId=PASS")
                        passed = true
                        return@withTimeout
                    }

                    lastFailure = IllegalStateException("NO_STREAMREF_AFTER_GLOBAL_RESOLUTION:$probe")
                } catch (error: Throwable) {
                    lastFailure = error
                }
            }
        }

        if (passed) return
        println("A-002 RESULT $providerId=FAIL reason=${lastFailure.message}")
        throw lastFailure
    }
}
