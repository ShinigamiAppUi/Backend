package streamix.runtime

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import streamix.core.JvmStreamixHttp

/** Live proof that Animein can locate an older episode and return StreamRefs. */
class AnimeinOlderEpisodeLiveTest {
    @Test
    fun animeinApiFindsOlderOnePieceEpisode299AndReturnsStreamRef() = runBlocking {
        val http = JvmStreamixHttp()
        StreamixRuntime.installJvm(http)
        val provider = AnimeinApiProvider(http, StreamixRuntime.native().extractors)

        val streams = withTimeout(55_000L) {
            provider.streams("One Piece", 299)
        }
        val summary = streams.take(8).map { stream ->
            val host = runCatching { java.net.URI(stream.url).host }.getOrNull() ?: "unknown-host"
            "${stream.type}:${stream.quality ?: "unknown"}@${host}"
        }
        println("A-011 animein older-episode=299 streams=${streams.size} candidates=${summary.joinToString()}")
        assertTrue(
            streams.isNotEmpty(),
            "Animein API did not return StreamRef for One Piece episode 299"
        )
    }
}
