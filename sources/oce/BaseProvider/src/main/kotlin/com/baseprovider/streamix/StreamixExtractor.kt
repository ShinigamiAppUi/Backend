package com.baseprovider.streamix

/**
 * Compatibility aliases during the Streamix dependency migration.
 *
 * Canonical extractor contracts live in streamix-core. Existing OCE sources
 * can keep their package imports until their call sites are migrated.
 */
typealias StreamixExtractor = streamix.core.StreamixExtractor
typealias StreamixExtractorRequest = streamix.core.StreamixExtractorRequest
typealias StreamixExtractorResult = streamix.core.StreamixExtractorResult
typealias StreamixSubtitle = streamix.core.ProviderSubtitle
