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
import streamix.auth.AuthRuntime
import streamix.auth.ExternalIdentity
import streamix.catalog.CatalogService
import streamix.catalog.FileCatalogRepository
import streamix.core.StreamixExtractorRegistry
import streamix.readingcatalog.FileReadingCatalogRepository
import streamix.readingcatalog.ReadingCatalogEntry
import streamix.readingcatalog.ReadingCatalogService
import streamix.readingcatalog.ReadingChapterEntry
import streamix.readingcatalog.ReadingChapterSource
import streamix.readingcatalog.ReadingEntryOrigin
import streamix.readingcatalog.ReadingKind
import streamix.readingcatalog.ReadingSourceType
import streamix.runtime.StreamixService

class ReadingCatalogHttpContractTest {
    @Test
    fun memberCatalogRequiresSessionAndBrowseDtosDoNotExposeProviderUrls() {
        val temp = Files.createTempDirectory("reading-catalog-http-contract")
        val auth = AuthRuntime(temp.resolve("auth"))
        val session = auth.auth.signIn(
            ExternalIdentity("test", "reading-catalog-member", displayName = "Reader")
        )
        val registry = StreamixExtractorRegistry(emptyList())
        val animeCatalog = CatalogService(
            repository = FileCatalogRepository(temp.resolve("anime/catalog.json")),
            extractors = registry
        )
        val reading = ReadingCatalogService(
            FileReadingCatalogRepository(temp.resolve("reading/catalog.json"))
        )
        reading.putManual(
            ReadingCatalogEntry(
                id = "ignored-id",
                kind = ReadingKind.MANHWA,
                title = "Tower of Test",
                url = "https://public.example/tower",
                origin = ReadingEntryOrigin.MANUAL,
                metadataId = 4242L,
                catalogKey = "anilist:4242",
                description = "Canonical description",
                author = "Test Author",
                sourceRefs = listOf(
                    streamix.readingcatalog.ReadingSourceRef(
                        "private-source",
                        "https://internal-provider.example/series",
                        "en"
                    )
                ),
                chapters = listOf(
                    ReadingChapterEntry(
                        "chapter-1",
                        "1",
                        sources = listOf(
                            ReadingChapterSource(
                                "private-chapter-source",
                                "private-provider",
                                "https://internal-provider.example/chapter/1",
                                ReadingSourceType.DIRECT,
                                language = "en"
                            )
                        )
                    )
                )
            )
        )
        val server = StreamixHttpServer(
            service = StreamixService(catalog = animeCatalog),
            authRuntime = auth,
            catalog = animeCatalog,
            readingCatalog = reading,
            host = "127.0.0.1",
            port = 0
        )
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

        try {
            server.start()
            val base = "http://127.0.0.1:${server.boundPort()}"
            val unauthenticated = client.send(
                HttpRequest.newBuilder().uri(URI("$base/api/v1/reading/catalog")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(401, unauthenticated.statusCode())

            val catalogResponse = client.send(
                HttpRequest.newBuilder().uri(URI("$base/api/v1/reading/catalog?kind=MANHWA&page=1&perPage=10"))
                    .header("Authorization", "Bearer ${session.token}")
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(200, catalogResponse.statusCode(), catalogResponse.body())
            assertTrue(catalogResponse.body().contains("Tower of Test"))
            assertTrue(catalogResponse.body().contains("Canonical description"))
            assertTrue(catalogResponse.body().contains("\"metadataId\":4242"))
            assertFalse(catalogResponse.body().contains("internal-provider.example"))
            assertFalse(catalogResponse.body().contains("private-provider"))

            val chapters = client.send(
                HttpRequest.newBuilder().uri(URI("$base/api/v1/reading/items/${reading.list().single().id}/chapters?language=en"))
                    .header("Authorization", "Bearer ${session.token}")
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(200, chapters.statusCode(), chapters.body())
            assertTrue(chapters.body().contains("chapter-1"))
            assertFalse(chapters.body().contains("internal-provider.example"))

            val visibleMetadata = client.send(
                HttpRequest.newBuilder().uri(URI("$base/api/v1/reading/catalog/visible-metadata?kind=MANHWA&ids=4242,9999"))
                    .header("Authorization", "Bearer ${session.token}")
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(200, visibleMetadata.statusCode(), visibleMetadata.body())
            assertEquals("[4242]", visibleMetadata.body())

            val byMetadataId = client.send(
                HttpRequest.newBuilder().uri(URI("$base/api/v1/reading/items/by-metadata/4242?kind=MANHWA"))
                    .header("Authorization", "Bearer ${session.token}")
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(200, byMetadataId.statusCode(), byMetadataId.body())
            assertTrue(byMetadataId.body().contains("\"metadataId\":4242"))

            val catalogId = reading.list().single().id
            assertTrue(reading.setEntryEnabled(catalogId, false)?.enabled == false)

            val hiddenBrowse = client.send(
                HttpRequest.newBuilder().uri(URI("$base/api/v1/reading/catalog?kind=MANHWA"))
                    .header("Authorization", "Bearer ${session.token}")
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(200, hiddenBrowse.statusCode(), hiddenBrowse.body())
            assertTrue(hiddenBrowse.body().contains("\"items\":[]"), hiddenBrowse.body())
            assertTrue(hiddenBrowse.body().contains("\"total\":0"), hiddenBrowse.body())

            val hiddenVisibleMetadata = client.send(
                HttpRequest.newBuilder().uri(URI("$base/api/v1/reading/catalog/visible-metadata?kind=MANHWA&ids=4242,9999"))
                    .header("Authorization", "Bearer ${session.token}")
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(200, hiddenVisibleMetadata.statusCode(), hiddenVisibleMetadata.body())
            assertEquals("[]", hiddenVisibleMetadata.body())

            val hiddenByMetadataId = client.send(
                HttpRequest.newBuilder().uri(URI("$base/api/v1/reading/items/by-metadata/4242?kind=MANHWA"))
                    .header("Authorization", "Bearer ${session.token}")
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(404, hiddenByMetadataId.statusCode(), hiddenByMetadataId.body())

            val hiddenDetail = client.send(
                HttpRequest.newBuilder().uri(URI("$base/api/v1/reading/items/$catalogId"))
                    .header("Authorization", "Bearer ${session.token}")
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(404, hiddenDetail.statusCode(), hiddenDetail.body())

            val hiddenChapters = client.send(
                HttpRequest.newBuilder().uri(URI("$base/api/v1/reading/items/$catalogId/chapters"))
                    .header("Authorization", "Bearer ${session.token}")
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(404, hiddenChapters.statusCode(), hiddenChapters.body())

            val hiddenContent = client.send(
                HttpRequest.newBuilder().uri(URI("$base/api/v1/reading/items/$catalogId/chapter-content?chapterId=chapter-1"))
                    .header("Authorization", "Bearer ${session.token}")
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(404, hiddenContent.statusCode(), hiddenContent.body())

            val manhuaKind = client.send(
                HttpRequest.newBuilder().uri(URI("$base/api/v1/reading/catalog?kind=MANHUA"))
                    .header("Authorization", "Bearer ${session.token}")
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(200, manhuaKind.statusCode(), manhuaKind.body())
            assertTrue(manhuaKind.body().contains("\"items\":[]"))

            val invalidKind = client.send(
                HttpRequest.newBuilder().uri(URI("$base/api/v1/reading/catalog?kind=NOT_A_KIND"))
                    .header("Authorization", "Bearer ${session.token}")
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(400, invalidKind.statusCode(), invalidKind.body())

            val unsupportedMethod = client.send(
                HttpRequest.newBuilder().uri(URI("$base/api/v1/reading/catalog"))
                    .header("Authorization", "Bearer ${session.token}")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(405, unsupportedMethod.statusCode())
        } finally {
            server.close()
            Files.walk(temp).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
