package streamix.catalog

import streamix.api.StreamRef
import streamix.api.SubtitleRef
import streamix.core.StreamixExtractorRegistry
import streamix.core.StreamixExtractorRequest
import streamix.core.StreamixHttp

/**
 * Exposes a native extractor as a catalog RESOLVER without duplicating host logic.
 *
 * The catalog keeps the source contract explicit (RESOLVER), while the actual
 * host/session extraction remains owned by the native extractor registry.
 */
class NativeExtractorCatalogResolver(
    private val extractorRegistry: StreamixExtractorRegistry,
    private val extractorId: String,
    override val id: String,
    private val catalogHttp: StreamixHttp = CatalogSafeStreamixHttp()
) : CatalogResolver {
    private val extractor = requireNotNull(extractorRegistry.get(extractorId)) {
        "native extractor is not registered: $extractorId"
    }

    override val domains: Set<String> = extractor.domains
        .map { it.lowercase().removePrefix("www.") }
        .filter { it.isNotBlank() }
        .toSet()

    private fun mergeHeaders(source: Map<String, String>, supplied: Map<String, String>): Map<String, String> {
        val merged = linkedMapOf<String, String>()
        (source.entries + supplied.entries).forEach { (name, value) ->
            // HTTP header names are case-insensitive. Normalize collisions across
            // both maps, including duplicate variants already present in source.
            merged.keys.filter { it.equals(name, ignoreCase = true) }.forEach(merged::remove)
            merged[name] = value
        }
        return merged
    }

    override suspend fun resolve(source: EpisodeSource): CatalogResolverResult {
        return try {
            val result = extractor.extract(
                StreamixExtractorRequest(
                    url = source.sourceUrl,
                    providerId = "catalog:${source.id}",
                    referer = source.referer,
                    headers = source.headers,
                    quality = source.quality,
                    scopedHttp = catalogHttp
                )
            )
            val subtitles = result.subtitles.map {
                SubtitleRef(
                    url = it.url,
                    language = it.language,
                    headers = it.headers,
                    referer = it.referer
                )
            }
            CatalogResolverResult(
                streams = result.streams.map {
                    StreamRef(
                        providerId = "catalog:${source.id}",
                        url = it.url,
                        quality = it.quality ?: source.quality,
                        type = it.type,
                        language = source.language,
                        subtitleLanguage = source.subtitleLanguage,
                        headers = mergeHeaders(source.headers, it.headers),
                        referer = it.referer ?: source.referer,
                        subtitles = subtitles
                    )
                },
                finalUrl = source.sourceUrl,
                failureReason = if (result.streams.isEmpty()) "native extractor returned no streams" else null
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            CatalogResolverResult(
                finalUrl = source.sourceUrl,
                failureReason = error.message?.take(512) ?: error::class.simpleName
            )
        }
    }
}