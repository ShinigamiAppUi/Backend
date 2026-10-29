package com.baseprovider.streamix.jvm

import com.baseprovider.streamix.StreamixWebRequest
import com.baseprovider.streamix.StreamixWebResolver
import com.baseprovider.streamix.StreamixWebResult

/** JVM fallback: browser-assisted resolution is provided by the Android bridge. */
class JvmStreamixWebResolver : StreamixWebResolver {
    override suspend fun resolve(
        request: StreamixWebRequest,
        onRequest: suspend (StreamixWebRequest) -> Boolean
    ): StreamixWebResult {
        throw UnsupportedOperationException(
            "Browser-assisted resolution is unavailable in the JVM runtime"
        )
    }
}
