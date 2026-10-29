package com.baseprovider.extractor

/** Compatibility facade; canonical implementation lives in streamix-core. */
object CompiledRegexPatterns {
    val M3U8_STREAM_INFO get() = streamix.core.StreamixExtractorPatterns.M3U8_STREAM_INFO
    val RUMBLE_URL_PATTERN get() = streamix.core.StreamixExtractorPatterns.RUMBLE_URL_PATTERN
    val DAILYMOTION_VIDEO_URL get() = streamix.core.StreamixExtractorPatterns.DAILYMOTION_VIDEO_URL
    val DAILYMOTION_SUBTITLE get() = streamix.core.StreamixExtractorPatterns.DAILYMOTION_SUBTITLE
    val ARCHIVE_ORG_URL get() = streamix.core.StreamixExtractorPatterns.ARCHIVE_ORG_URL
    val UNIVERSAL_VIDEO_URL get() = streamix.core.StreamixExtractorPatterns.UNIVERSAL_VIDEO_URL
    val MLG_QUALITY_1080 get() = streamix.core.StreamixExtractorPatterns.MLG_QUALITY_1080
    val MLG_QUALITY_720 get() = streamix.core.StreamixExtractorPatterns.MLG_QUALITY_720
    val MLG_QUALITY_480 get() = streamix.core.StreamixExtractorPatterns.MLG_QUALITY_480
    val MLG_QUALITY_360 get() = streamix.core.StreamixExtractorPatterns.MLG_QUALITY_360

    fun extractAllVideoUrls(text: String) = streamix.core.StreamixExtractorPatterns.extractAllVideoUrls(text)
    fun filterMasterM3u8(urls: Collection<String>) = streamix.core.StreamixExtractorPatterns.filterMasterM3u8(urls)
    fun prioritizeAdaptiveUrls(urls: Collection<String>) = streamix.core.StreamixExtractorPatterns.prioritizeAdaptiveUrls(urls)
}
