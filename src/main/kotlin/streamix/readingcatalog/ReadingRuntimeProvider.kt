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

/** Enforces the payload contract for the reader selected by the catalog entry kind. */
internal fun ReadingContent.isUsableFor(kind: ReadingKind): Boolean =
    this.kind == kind && when (kind) {
        ReadingKind.NOVEL -> !text.isNullOrBlank() || !contentUrl.isNullOrBlank()
        ReadingKind.MANGA, ReadingKind.MANHWA, ReadingKind.MANHUA ->
            if (pageUrls.isNotEmpty()) pageUrls.all(::isSafeReadingImageUrl)
            else contentUrl?.let(::isSafeReadingHttpUrl) == true
    }

/** Validates an HTTPS provider chapter URL before any network request. */
internal fun isSafeReadingChapterUrl(value: String, allowedHosts: Set<String>, pathIsValid: (String) -> Boolean): Boolean = runCatching {
    URI(value).let { uri ->
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host?.lowercase() in allowedHosts.map(String::lowercase).toSet() &&
            uri.userInfo == null &&
            (uri.port == -1 || uri.port == 443) &&
            !uri.path.isNullOrBlank() &&
            pathIsValid(uri.path)
    }
}.getOrDefault(false)

/** Accepts only HTTPS image URLs without credentials or non-standard ports. */
internal fun isSafeReadingImageUrl(value: String): Boolean = runCatching {
    URI(value).let { uri ->
        uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            (uri.port == -1 || uri.port == 443)
    }
}.getOrDefault(false)
