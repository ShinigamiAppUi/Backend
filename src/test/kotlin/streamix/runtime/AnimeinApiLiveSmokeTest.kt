package streamix.runtime

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import streamix.core.JvmStreamixHttp

/**
 * Opt-in live contract check for Animein.
 * Acceptance is a non-empty StreamRef list; actual Media3 playback is a later gate.
 */
class AnimeinApiLiveSmokeTest {
    @Test
    fun animeinApiReturnsStreamRefForOnePieceEpisodeOne() = runBlocking {
        val http = JvmStreamixHttp()
        StreamixRuntime.installJvm(http)
        val provider = AnimeinApiProvider(http, StreamixRuntime.native().extractors)

        val streams = withTimeout(55_000L) {
            provider.streams("One Piece", 1)
        }
        val summary = streams.take(8).map { stream ->
            val host = runCatching { java.net.URI(stream.url).host }.getOrNull() ?: "unknown-host"
            "${stream.type}:${stream.quality ?: "unknown"}@${host}"
        }
        println("A-011 Animein episode=1 streams=${streams.size} candidates=${summary.joinToString()}")
        assertTrue(
            streams.isNotEmpty(),
            "Animein API returned no StreamRef for One Piece episode 1"
        )
    }
}
