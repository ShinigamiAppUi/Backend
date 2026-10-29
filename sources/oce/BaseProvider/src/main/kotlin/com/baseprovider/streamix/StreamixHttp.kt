package com.baseprovider.streamix

/**
 * Compatibility aliases during the Streamix dependency migration.
 *
 * The canonical HTTP contract now lives in streamix-core. Keeping these aliases
 * avoids a second HTTP abstraction while existing OCE sources migrate imports.
 */
typealias StreamixHttp = streamix.core.StreamixHttp
typealias StreamixHttpResponse = streamix.core.StreamixHttpResponse
typealias StreamixHttpProbeResponse = streamix.core.StreamixHttpProbeResponse
