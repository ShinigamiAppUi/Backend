package com.baseprovider.streamix.jvm

import com.baseprovider.streamix.StreamixCloudflareResolver
import com.baseprovider.streamix.StreamixCloudflareResult

/**
 * JVM backend has no browser challenge solver.
 *
 * A provider may continue with normal HTTP candidates; Android can install
 * the WebView-backed implementation when browser assistance is available.
 */
class JvmStreamixCloudflareResolver : StreamixCloudflareResolver {
    override suspend fun solve(url: String, referer: String?): StreamixCloudflareResult =
        StreamixCloudflareResult(solved = false)
}
