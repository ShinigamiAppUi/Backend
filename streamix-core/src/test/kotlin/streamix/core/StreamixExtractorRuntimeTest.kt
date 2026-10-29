package streamix.core

import kotlin.test.Test
import kotlin.test.assertEquals

class StreamixExtractorRuntimeTest {
    @Test
    fun keepsRegistryAndRoutesRepeatedRequests() {
        val extractor = FakeExtractor("filedon", setOf("filedon.co"))
        val runtime = StreamixExtractorRuntime(
            StreamixExtractorRegistry(listOf(extractor))
        )

        assertEquals(extractor, runtime.resolve("https://filedon.co/embed/a"))
        assertEquals(extractor, runtime.resolve("https://filedon.co/embed/b"))
        assertEquals(extractor, runtime.get("FILEDON"))
    }

    private class FakeExtractor(
        override val id: String,
        override val domains: Set<String>
    ) : StreamixExtractor {
        override suspend fun extract(request: StreamixExtractorRequest) =
            StreamixExtractorResult()
    }
}
