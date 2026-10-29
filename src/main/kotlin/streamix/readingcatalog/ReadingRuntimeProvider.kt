package streamix.readingcatalog

import java.net.URI

/**
 * Backend-native adapter contract for reading providers.
 *
 * Providers declare the catalog entry/chapter combinations they can handle,
 * then return image pages for comics or text content for novels. Implementations
 * must return null when the source is unsupported or cannot be resolved.
 */
interface ReadingRuntimeProvider {
    val id: String

    /**
     * Default routing is deliberately conservative: only chapters that explicitly
     * reference this provider are eligible. Site adapters may narrow this further
     * by validating their own canonical chapter URLs.
     */
    fun supports(entry: ReadingCatalogEntry, chapter: ReadingChapterEntry): Boolean =
        chapter.sources.any { it.enabled && it.providerId.equals(id, ignoreCase = true) }

    suspend fun chapter(
        entry: ReadingCatalogEntry,
        chapter: ReadingChapterEntry
    ): ReadingContent?
}

/** Maximum page references accepted from a single provider response. Keeps malformed sources from producing unbounded reader payloads. */
internal const val MAX_READING_PAGE_COUNT: Int = 1000

/** Preserves source order while dropping unsafe/duplicate image URLs and bounding the response. */
internal fun Iterable<String>.normalizedReadingPageUrls(): List<String> =
    asSequence()
        .filter(::isSafeReadingImageUrl)
        .distinct()
        .take(MAX_READING_PAGE_COUNT)
        .toList()

/** Enforces the payload contract for the reader selected by the catalog entry kind. */
internal fun ReadingContent.isUsableFor(kind: ReadingKind): Boolean =
    this.kind == kind && when (kind) {
        ReadingKind.NOVEL -> !text.isNullOrBlank() || contentUrl?.let(::isSafeReadingHttpUrl) == true
        ReadingKind.MANGA, ReadingKind.MANHWA, ReadingKind.MANHUA ->
            if (pageUrls.isNotEmpty()) pageUrls.all(::isSafeReadingImageUrl)
            else contentUrl?.let(::isSafeReadingHttpUrl) == true
    }

/** Validates an HTTPS provider chapter URL before any network request. */
internal fun isSafeReadingChapterUrl(value: String, allowedHosts: Set<String>, pathIsValid: (String) -> Boolean): Boolean = try {
    URI(value).let { uri ->
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host?.lowercase() in allowedHosts.map(String::lowercase).toSet() &&
            uri.userInfo == null &&
            (uri.port == -1 || uri.port == 443) &&
            !uri.path.isNullOrBlank() &&
            pathIsValid(uri.path)
    }
} catch (error: kotlinx.coroutines.CancellationException) {
    throw error
} catch (_: Exception) {
    false
}

/** Image URLs use the same public HTTPS destination policy as other reading HTTP targets. */
internal fun isSafeReadingImageUrl(value: String): Boolean = isSafeReadingHttpUrl(value)
