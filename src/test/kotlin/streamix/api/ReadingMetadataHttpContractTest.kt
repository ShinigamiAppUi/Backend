package streamix.api

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import streamix.catalog.CatalogService
import streamix.catalog.FileCatalogRepository
import streamix.core.StreamixExtractorRegistry
import streamix.runtime.StreamixService

class ReadingMetadataHttpContractTest {
    @Test
    fun readingMetadataSearchAndDetailUseDedicatedGateway() {
        val temp = Files.createTempDirectory("reading-metadata-http")
        val gateway = FakeReadingMetadataGateway()
        val catalog = CatalogService(
            repository = FileCatalogRepository(temp.resolve("anime-catalog.json")),
            extractors = StreamixExtractorRegistry(emptyList())
        )
        val server = StreamixHttpServer(
            service = StreamixService(catalog = catalog),
            host = "127.0.0.1",
            port = 0,
            readingMetadata = gateway
        )
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

        try {
            server.start()
            val base = "http://127.0.0.1:${server.boundPort()}"

            val search = get(client, "$base/api/v1/reading/metadata/search?q=Fixture&kind=MANHWA&page=3")
            assertEquals(200, search.statusCode(), search.body())
            assertTrue(search.body().contains("Fixture Manhwa"))
            assertTrue(search.body().contains("https://anilist.co/manga/123"))
            assertEquals("Fixture", gateway.lastQuery)
            assertEquals(ReadingMetadataKind.MANHWA, gateway.lastKind)
            assertEquals(3, gateway.lastPage)

            val detail = get(client, "$base/api/v1/reading/metadata/123")
            assertEquals(200, detail.statusCode(), detail.body())
            assertTrue(detail.body().contains("Fixture Manhwa"))

            val invalidKind = get(client, "$base/api/v1/reading/metadata/search?q=Fixture&kind=VIDEO")
            assertEquals(400, invalidKind.statusCode())
            assertFalse(gateway.lastKind == null && gateway.lastQuery == "Fixture" && gateway.lastPage == 1)
        } finally {
            server.close()
            Files.walk(temp).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun upstreamFailureIsMappedToSanitizedBadGateway() {
        val temp = Files.createTempDirectory("reading-metadata-failure")
        val catalog = CatalogService(
            repository = FileCatalogRepository(temp.resolve("anime-catalog.json")),
            extractors = StreamixExtractorRegistry(emptyList())
        )
        val server = StreamixHttpServer(
            service = StreamixService(catalog = catalog),
            host = "127.0.0.1",
            port = 0,
            readingMetadata = object : ReadingMetadataGateway {
                override fun search(query: String, kind: ReadingMetadataKind, page: Int) =
                    throw AniListReadingMetadataException(503)
                override fun detail(anilistId: Long): ReadingMetadata? =
                    throw AniListReadingMetadataException(503)
            }
        )
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        try {
            server.start()
            val response = get(client, "http://127.0.0.1:${server.boundPort()}/api/v1/reading/metadata/search?q=Fixture&kind=MANGA")
            assertEquals(502, response.statusCode())
            assertTrue(response.body().contains("AniList reading metadata request failed"))
            assertFalse(response.body().contains("503"))
        } finally {
            server.close()
            Files.walk(temp).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private fun get(client: HttpClient, url: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder().uri(URI(url)).timeout(Duration.ofSeconds(3)).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        )

    private class FakeReadingMetadataGateway : ReadingMetadataGateway {
        @Volatile var lastQuery: String? = null
        @Volatile var lastKind: ReadingMetadataKind? = null
        @Volatile var lastPage: Int? = null

        override fun search(query: String, kind: ReadingMetadataKind, page: Int): List<ReadingMetadata> {
            lastQuery = query
            lastKind = kind
            lastPage = page
            return listOf(
                ReadingMetadata(
                    id = 123L,
                    kind = kind,
                    title = "Fixture Manhwa",
                    url = "https://anilist.co/manga/123",
                    coverUrl = "https://cdn.example/cover.jpg"
                )
            )
        }

        override fun detail(anilistId: Long): ReadingMetadata? =
            if (anilistId == 123L) ReadingMetadata(
                id = 123L,
                kind = ReadingMetadataKind.MANHWA,
                title = "Fixture Manhwa",
                url = "https://anilist.co/manga/123"
            ) else null
    }
}
