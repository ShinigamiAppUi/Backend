package streamix.api

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class EpisodeViewCountStoreTest {
    @Test
    fun playbackCountsPersistAndDuplicatePlaybackIdsAreIdempotent() {
        val directory = Files.createTempDirectory("shinigami-view-counts")
        val path = directory.resolve("counts.json")
        try {
            val first = EpisodeViewCountStore(path)
            assertEquals(1L, first.record(21L, 1, "playback_attempt_0001"))
            assertEquals(1L, first.record(21L, 1, "playback_attempt_0001"))
            assertEquals(2L, first.record(21L, 1, "playback_attempt_0002"))
            assertEquals(1L, first.record(21L, 2, "playback_attempt_0001"))
            val reloaded = EpisodeViewCountStore(path)
            assertEquals(mapOf("1" to 2L, "2" to 1L), reloaded.list(21L))
            assertEquals(emptyMap(), reloaded.list(22L))
            assertEquals(2L, reloaded.record(21L, 1, "playback_attempt_0002"))
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }
}
