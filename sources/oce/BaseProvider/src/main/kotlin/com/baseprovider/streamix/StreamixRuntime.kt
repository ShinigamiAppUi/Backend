package com.baseprovider.streamix

/**
 * Compatibility facade during Base OCE migration.
 *
 * Canonical contracts live in streamix-core. Platform implementations remain
 * in the host/runtime layer until the Base OCE module is converted to JVM.
 */
object StreamixRuntime {
    @Volatile private var httpImpl: streamix.core.StreamixHttp? = null
    val http: streamix.core.StreamixHttp
        get() = httpImpl ?: error("StreamixHttp is not installed")
    @Volatile var json: streamix.core.StreamixJson? = null
        private set
    @Volatile var crypto: streamix.core.StreamixCrypto? = null
        private set
    @Volatile var js: streamix.core.StreamixJs? = null
        private set
    @Volatile var dispatchers: streamix.core.StreamixDispatchers? = null
        private set
    @Volatile var webResolver: streamix.core.StreamixWebResolver? = null
        private set
    @Volatile var cloudflareResolver: streamix.core.StreamixCloudflareResolver? = null
        private set

    fun requireCrypto(): streamix.core.StreamixCrypto =
        crypto ?: error("StreamixCrypto is not installed")

    fun requireJson(): streamix.core.StreamixJson =
        json ?: error("StreamixJson is not installed")

    fun install(
        http: streamix.core.StreamixHttp,
        json: streamix.core.StreamixJson,
        crypto: streamix.core.StreamixCrypto,
        js: streamix.core.StreamixJs,
        dispatchers: streamix.core.StreamixDispatchers,
        webResolver: streamix.core.StreamixWebResolver,
        cloudflareResolver: streamix.core.StreamixCloudflareResolver
    ) {
        this.httpImpl = http
        this.json = json
        this.crypto = crypto
        this.js = js
        this.dispatchers = dispatchers
        this.webResolver = webResolver
        this.cloudflareResolver = cloudflareResolver
    }
}


typealias StreamixLegacyAes = streamix.core.StreamixLegacyAes
