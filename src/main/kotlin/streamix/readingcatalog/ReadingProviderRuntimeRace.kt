package streamix.readingcatalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import java.util.Locale

/**
 * Mirrors RuntimeProviderFallback's first-usable-result race.
 * Provider adapters are supplied by the runtime bootstrap; no fake/default sites are registered.
 */
class ReadingProviderRuntimeRace(
    providers: List<ReadingRuntimeProvider>
) : ReadingProviderRuntime {
    private fun canonicalProviderId(raw: String): String =
        when (raw.trim().lowercase(Locale.ROOT)) {
            "mangadex-manhwa", "mangadex-manhua" -> "mangadex"
            "weebcentral-manhwa" -> "weebcentral"
            "comick-unoriginal" -> "comick"
            "bbato" -> "bato"
            else -> raw.trim().lowercase(Locale.ROOT)
        }
    private val providers = providers.also { candidates ->
        require(candidates.all { it.id.isNotBlank() }) { "Reading provider IDs must not be blank" }
        val normalizedIds = candidates.map { it.id.trim().lowercase(Locale.ROOT) }
        require(normalizedIds.size == normalizedIds.toSet().size) { "Reading provider IDs must be unique ignoring case" }
    }

    override suspend fun race(
        entry: ReadingCatalogEntry,
        chapter: ReadingChapterEntry,
        failedSourceIds: Set<String>
    ): ReadingContent? = coroutineScope {
        // Failure IDs may be provider IDs, source IDs, URLs, or serialized runtime aliases.
        // Normalize once and remove failed sources before asking adapters to select a chapter source.
        val failedIds = failedSourceIds.mapTo(mutableSetOf()) { it.trim().lowercase(Locale.ROOT) }
        val failedProviderIds = failedIds.mapTo(mutableSetOf()) { canonicalProviderId(it) }
        val eligibleSources = chapter.sources.filter { source ->
            val rawProviderId = source.providerId.trim().lowercase(Locale.ROOT)
            val canonicalProvider = canonicalProviderId(rawProviderId)
            val sourceUrlAllowed = when (entry.kind) {
                ReadingKind.MANGA, ReadingKind.MANHWA, ReadingKind.MANHUA ->
                    isSafeReadingHttpUrl(source.url)
                ReadingKind.NOVEL -> true
            }
            source.enabled &&
                sourceUrlAllowed &&
                source.id.trim().lowercase(Locale.ROOT) !in failedIds &&
                rawProviderId !in failedIds &&
                canonicalProvider !in failedProviderIds &&
                source.url.trim().lowercase(Locale.ROOT) !in failedIds &&
                ("reading-provider:" + rawProviderId) !in failedIds &&
                ("reading-provider:" + canonicalProvider) !in failedIds
        }.map { source ->
            source.copy(providerId = canonicalProviderId(source.providerId))
        }
        // Keep the chapter eligible even when it has no manually cataloged URL.
        // Adapters with explicit discovery-aware supports() implementations may search
        // by the catalog entry/chapter identity; the conservative default still rejects it.
        val eligibleChapter = chapter.copy(sources = eligibleSources)

        val eligible = providers.filter { provider ->
            val providerId = provider.id.trim().lowercase(Locale.ROOT)
            providerId !in failedIds &&
                ("reading-provider:" + providerId) !in failedIds &&
                runCatching { provider.supports(entry, eligibleChapter) }.getOrDefault(false)
        }
        if (eligible.isEmpty()) return@coroutineScope null

        val results = Channel<ReadingContent?>(eligible.size)
        val jobs = eligible.map { provider ->
            async {
                val result = try {
                    provider.chapter(entry, eligibleChapter)?.takeIf { it.isUsableFor(entry.kind) }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    null
                }
                results.send(result)
            }
        }
        try {
            repeat(jobs.size) {
                val result = results.receive()
                if (result != null) {
                    jobs.forEach { it.cancel() }
                    return@coroutineScope result
                }
            }
            null
        } finally {
            jobs.forEach { it.cancel() }
            results.close()
        }
    }
}
