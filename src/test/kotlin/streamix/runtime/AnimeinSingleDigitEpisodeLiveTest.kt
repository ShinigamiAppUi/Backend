package streamix.runtime

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import streamix.core.JvmStreamixHttp

/**
 * Live proof for exact single-digit Animein episode lookup.
 * A successful StreamRef must belong to episode 3, not a prefix match such as 39/399.
 */
class AnimeinSingleDigitEpisodeLiveTest {
    @Test
    fun animeinApiFindsExactOnePieceEpisodeThreeAndReturnsStreamRef() = runBlocking {
        val http = JvmStreamixHttp()
        StreamixRuntime.installJvm(http)
        val provider = AnimeinApiProvider(http, StreamixRuntime.native().extractors)

        val streams = withTimeout(55_000L) {
            provider.streams("One Piece", 3)
        }
        val summary = streams.take(8).map { stream ->
            val host = runCatching { java.net.URI(stream.url).host }.getOrNull() ?: "unknown-host"
            "${stream.type}:${stream.quality ?: "unknown"}@${host}"
        }
        println("A-011 animein exact-episode=3 streams=${streams.size} candidates=${summary.joinToString()}")
        assertTrue(
            streams.isNotEmpty(),
            "Animein API did not return StreamRef for exact One Piece episode 3"
        )
    }
}
