package streamix.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue
import streamix.core.JvmStreamixHttp
import streamix.core.StreamixBuiltInExtractors
import streamix.core.StreamixExtractor

/**
 * Opt-in live diagnostics for the A-011 provider expansion.
 *
 * These tests are intentionally excluded from regular JVM CI and run only
 * through :liveE2eTest. Each provider is isolated so one site's failure does
 * not mask another site's result.
 */
class A011RuntimeProviderExpansionLiveTest {
    private val probes = listOf("One Piece", "Naruto", "Boruto")
    private val alqanimeProbe = "Kusuriya no Hitorigoto 3rd Season"
    private val providerTimeoutMs = 55_000L

    @Test fun sokujaMustReturnPlayableStreamRef() = runBlocking { assertProvider("sokuja") }
    @Test fun nimegamiMustReturnPlayableStreamRef() = runBlocking { assertProvider("nimegami") }
    @Test fun alqanimeMustReturnPlayableStreamRef() = runBlocking { assertProvider("alqanime") }
    @Test fun animeinMustReturnStreamRef() = runBlocking { assertAnimein() }

    private suspend fun assertAnimein() {
        val http = JvmStreamixHttp()
        StreamixRuntime.installJvm(http)
        val provider = AnimeinApiProvider(http, StreamixRuntime.native().extractors)

        val streams = withTimeout(providerTimeoutMs) {
            provider.streams("One Piece", 1)
        }
        println("A-011 animein streams=${streams.size}")
        println("A-011 RESULT animein=" + if (streams.isNotEmpty()) "PASS" else "FAIL")
        assertTrue(
            streams.isNotEmpty(),
            "A-011 Animein returned no StreamRef for One Piece episode 1"
        )
    }

    private suspend fun assertProvider(providerId: String) {
        val http = JvmStreamixHttp()
        StreamixRuntime.installJvm(http)
        val site = defaultSites(http).firstOrNull { it.id == providerId }
            ?: error("A-011 PROVIDER_SITE_NOT_REGISTERED:$providerId")
        val providerExtractors = RuntimeProviderExtractorRegistry(http)
        val nativeRegistryClass = Class.forName("com.baseprovider.streamix.ProviderExtractorsNative")
        val nativeInstance = nativeRegistryClass.getField("INSTANCE").get(null)
        @Suppress("UNCHECKED_CAST")
        val nativeExtractors = nativeRegistryClass.getMethod("all").invoke(nativeInstance) as List<StreamixExtractor>
        val extractors = StreamixBuiltInExtractors.create(http).also { registry ->
            val existingIds = registry.all().map { it.id.lowercase() }.toSet()
            nativeExtractors
                .filterNot { it.id.lowercase() in existingIds }
                .distinctBy { it.id.lowercase() }
                .forEach(registry::register)
        }

        providerExtractors.get(providerId)
            ?: error("A-011 PROVIDER_EXTRACTOR_NOT_REGISTERED:" + providerId)
        var lastFailure: Throwable = IllegalStateException("NO_PROBE_ATTEMPTED")
        var passed = false

        val providerProbes = if (providerId == "alqanime") listOf(alqanimeProbe) else probes
        withTimeout(providerTimeoutMs) {
            for (title in providerProbes) {
                try {
                    val episodePage = site.findEpisodePage(title, 1)
                        ?: throw IllegalStateException("EPISODE_PAGE_NOT_FOUND:" + title)
                    println("A-011 " + providerId + " PROBE=" + title + " EPISODE_PAGE_FOUND")
                    if (providerId == "alqanime") {
                        val candidates = providerExtractors.get(providerId)?.extract(episodePage).orEmpty()
                        val hosts = candidates.mapNotNull { runCatching { java.net.URI(it.url).host }.getOrNull() }.distinct().take(12)
                        println("A-011 alqanime EXTRACTOR_CANDIDATES=" + candidates.size + " HOSTS=" + hosts.joinToString())
                    }
                    if (providerId == "alqanime") {
                        val cleanUrl = episodePage.replace(Regex("([?&])__shinigami_ep=\\d+"), "").trimEnd('?', '&')
                        val response = runCatching { http.get(cleanUrl, timeoutMs = 8_000L) }.getOrNull()
                        val doc = response?.document
                        val sections = doc?.select("div.sorattl.collapsible").orEmpty()
                        val matching = sections.filter { section ->
                            Regex("(?i)episode\\s*(\\d+)").find(section.selectFirst("h3")?.text().orEmpty())
                                ?.groupValues?.getOrNull(1)?.toIntOrNull() == 1
                        }
                        val sectionSummary = matching.map { section ->
                            val sibling = section.nextElementSibling()
                            "heading=" + section.selectFirst("h3")?.text().orEmpty().take(50) +
                                ",nextClass=" + sibling?.classNames()?.joinToString(".").orEmpty() +
                                ",links=" + (sibling?.select("a[href]")?.size ?: 0)
                        }
                        println("A-011 alqanime PAGE_DIAG status=" + response?.code +
                            " sections=" + sections.size + " matchingEpisode1=" + matching.size +
                            " headings=" + sections.map { it.selectFirst("h3")?.text().orEmpty().take(70) }.take(30).joinToString(" | ") +
                            " summary=" + sectionSummary.joinToString(" | "))
                    }

                    // Reuse the page found above. Do not repeat discovery or run the
                    // provider extractor twice; tokenized provider pages
                    // otherwise waste most of the live timeout.
                    val streams = RuntimeProviderFallback(
                        http = http,
                        extractors = extractors,
                        sites = listOf(object : RuntimeProviderSite {
                            override val id = site.id
                            override val priority = site.priority
                            override suspend fun findEpisodePage(title: String, episodeNumber: Int): String = episodePage
                        }),
                        providerExtractors = providerExtractors
                    ).streams(title, 1)

                    if (streams.isNotEmpty()) {
                        val summary = streams.take(6).map { stream ->
                            val host = runCatching { java.net.URI(stream.url).host }.getOrNull() ?: "unknown-host"
                            stream.type + ":" + (stream.quality?.toString() ?: "unknown") + "@" + host
                        }.distinct()
                        println("A-011 " + providerId + " STREAMS=" + summary.joinToString())
                        println("A-011 RESULT " + providerId + "=PASS")
                        passed = true
                        return@withTimeout
                    }
                    lastFailure = IllegalStateException("NO_STREAMREF_AFTER_GLOBAL_RESOLUTION")
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    lastFailure = error
                    val reason = error.message.orEmpty().substringBefore(':').take(80)
                    println("A-011 " + providerId + " PROBE=" + title + " FAILED=" + error::class.simpleName + ":" + reason)
                }
            }
        }

        if (!passed) {
            val reason = lastFailure.message.orEmpty().substringBefore(':').take(80)
            println("A-011 RESULT " + providerId + "=FAIL reason=" + reason)
            throw lastFailure
        }
    }
}
