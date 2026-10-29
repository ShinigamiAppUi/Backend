package com.baseprovider.extractor

import com.baseprovider.streamix.StreamixJs
import com.baseprovider.streamix.StreamixLogger
import com.baseprovider.streamix.app
import com.baseprovider.streamix.jvm.JvmStreamixJs
import kotlinx.coroutines.withTimeout

private val lk21Lock = Any()
private var cachedPlayerJsText: String? = null
private var cachedPlayerJsTime: Long = 0L
private const val PLAYER_JS_REFRESH_MS = 30 * 60 * 1000L
private val lk21Js: StreamixJs = JvmStreamixJs()

private suspend fun ensurePlayerJs(): String {
    val now = System.currentTimeMillis()
    synchronized(lk21Lock) {
        cachedPlayerJsText?.takeIf { now - cachedPlayerJsTime < PLAYER_JS_REFRESH_MS }?.let { return it }
    }

    val js = withTimeout(10_000L) {
        app.get(
            "https://assets.lk21.party/js/player.js?v=4",
            timeout = 10_000L
        ).text
    }

    synchronized(lk21Lock) {
        cachedPlayerJsText = js
        cachedPlayerJsTime = now
    }
    return js
}

private fun jsString(value: String): String =
    value.replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\r", "\\r")
        .replace("\n", "\\n")

suspend fun decryptLk21PlayerUrl(encrypted: String): String? {
    if (encrypted.isBlank() || encrypted.startsWith("http")) return null

    return runCatching {
        val playerJs = ensurePlayerJs()
        val script = """
            var window = this;
            var globalThis = this;
            var navigator = {};
            var location = {};
            var document = {};
            var setTimeout = function(){};
            var clearTimeout = function(){};
            var console = {log:function(){},warn:function(){},error:function(){}};
            var atob = function(s) { return s; };
            $playerJs
            typeof _L === "function" ? _L('${jsString(encrypted)}') : "";
        """.trimIndent()

        lk21Js.evaluate(script).takeIf { it.isNotBlank() }
    }.getOrElse { e ->
        StreamixLogger.d("Lk21Crypto", "Decryption failed: ${e.message}")
        null
    }
}