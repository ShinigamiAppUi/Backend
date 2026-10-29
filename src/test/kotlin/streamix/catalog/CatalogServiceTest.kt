package streamix.catalog

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import streamix.core.StreamixExtractorRegistry
import streamix.api.StreamRef
import streamix.api.SubtitleRef

class CatalogServiceTest {
    @Test
    fun explicitSourceContractIsPersistedAndLegacyValuesAreMigrated() {
        val root = createTempDirectory("shinigami-catalog-contract")
        val file = root.resolve("episodes.json")
        file.toFile().writeText("""[{"id":"legacy-direct","anilistId":1,"episodeNumber":1,"sourceUrl":"https://example.com/video.m3u8","type":"HLS"},{"id":"legacy-page","anilistId":1,"episodeNumber":2,"sourceUrl":"https://example.com/episode","type":"EMBED"}]""")
        val repository = FileCatalogRepository(file)

        assertEquals(SourceType.DIRECT, repository.getEpisode(1, 1)!!.sources.single().type)
        assertEquals(SourceType.EXTRACTOR, repository.getEpisode(1, 2)!!.sources.single().type)

        repository.upsertSource(EpisodeSource("resolver", 1, 3, "https://gofile.io/d/example", type = SourceType.RESOLVER))
        val persisted = file.toFile().readText()
        assertTrue(persisted.contains("\"type\":\"RESOLVER\""))
    }

    @Test
    fun rejectsIPv6DocumentationAddressAsSourceTarget() {
        val root = createTempDirectory("shinigami-catalog-ipv6-doc")
        val service = CatalogService(
            FileCatalogRepository(root.resolve("episodes.json")),
            StreamixExtractorRegistry()
        )

        assertFailsWith<IllegalArgumentException> {
            service.upsertSource(
                EpisodeSource(
                    id = "ipv6-doc",
                    anilistId = 27,
                    episodeNumber = 1,
                    sourceUrl = "https://[2001:db8::1]/video.m3u8",
                    type = SourceType.DIRECT
                )
            )
        }
    }
    @Test
    fun rejectsLegacyPersistedLocalSourceAtResolutionTime() = runBlocking {
        val root = createTempDirectory("shinigami-catalog-legacy-local")
        val file = root.resolve("episodes.json")
        file.toFile().writeText(
            """[{"id":"legacy-local","anilistId":23,"episodeNumber":1,"sourceUrl":"http://127.0.0.1:8080/internal","type":"DIRECT","enabled":true}]"""
        )
        val service = CatalogService(FileCatalogRepository(file), StreamixExtractorRegistry())

        assertTrue(service.streams(23, 1).isEmpty())
        assertEquals(SourceHealth.FAILED, service.checkSource(23, 1, "legacy-local")!!.health)
    }

    @Test
    fun sourceTypes_are_not_inferred_from_url() = runBlocking {
        val root = createTempDirectory("shinigami-catalog-source-contract")
        val extractor = object : streamix.core.StreamixExtractor {
            override val id = "contract-test"
            override val domains = setOf("example.com")
            override suspend fun extract(request: streamix.core.StreamixExtractorRequest) =
                streamix.core.StreamixExtractorResult(
                    streams = listOf(streamix.core.ProviderStream(
                        providerId = "contract-test",
                        url = "https://media.example.com/extracted.m3u8", type = "m3u8", quality = 720
                    ))
                )
        }
        val service = CatalogService(
            FileCatalogRepository(root.resolve("episodes.json")),
            StreamixExtractorRegistry(listOf(extractor))
        )

        val directSource = service.upsertSource(EpisodeSource("direct-page", 1, 1, "https://example.com/not-an-extension", type = SourceType.DIRECT))
        val resolverSource = service.upsertSource(EpisodeSource("resolver", 1, 1, "https://example.com/not-an-extension", type = SourceType.RESOLVER))
        val extractorSource = service.upsertSource(EpisodeSource("extractor", 1, 1, "https://example.com/not-an-extension", type = SourceType.EXTRACTOR))

        val sources = service.episodes(1).single().sources.associateBy { it.id }
        assertEquals(SourceType.DIRECT, sources.getValue(directSource.id).type)
        assertEquals(SourceType.RESOLVER, sources.getValue(resolverSource.id).type)
        assertEquals(SourceType.EXTRACTOR, sources.getValue(extractorSource.id).type)

        val streams = service.streams(1, 1)
        assertEquals(listOf("catalog:" + directSource.id), streams.map { it.providerId })
    }

    @Test
    fun extensionlessDirectLinkRemainsGenericVideo() = runBlocking {
        val root = createTempDirectory("shinigami-catalog-direct-type")
        val service = CatalogService(
            FileCatalogRepository(root.resolve("episodes.json")),
            StreamixExtractorRegistry()
        )
        service.upsertSource(
            EpisodeSource(
                "pixeldrain-api",
                24,
                1,
                "https://pixeldrain.com/api/file/mi7ihxqL",
                type = SourceType.DIRECT
            )
        )

        val stream = service.streams(24, 1).single()
        assertEquals("video", stream.type)
    }

    @Test
    fun pixeldrainViewerLink_isDeterministicallyNormalizedToDirectApi() = runBlocking {
        val root = createTempDirectory("shinigami-catalog-pixeldrain")
        val service = CatalogService(
            FileCatalogRepository(root.resolve("episodes.json")),
            StreamixExtractorRegistry()
        )
        service.upsertSource(
            EpisodeSource(
                "pixeldrain-viewer",
                25,
                1,
                "https://pixeldrain.com/u/mi7ihxqL",
                type = SourceType.DIRECT
            )
        )

        val stream = service.streams(25, 1).single()
        assertEquals("https://pixeldrain.com/api/file/mi7ihxqL?download", stream.url)
        assertEquals("video", stream.type)
    }

    @Test
    fun multiSourceEpisodeResolvesDirectStreams() = runBlocking {
        val root = createTempDirectory("shinigami-catalog")
        val repo = FileCatalogRepository(root.resolve("episodes.json"))
        val service = CatalogService(repo, StreamixExtractorRegistry())

        service.upsertSource(EpisodeSource("360", 21, 1, "https://example.com/360.m3u8", type = SourceType.DIRECT, quality = 360, priority = 100, referer = "https://example.com/", headers = mapOf("User-Agent" to "ShinigamiTest")))
        service.upsertSource(EpisodeSource("1080", 21, 1, "https://example.com/1080.m3u8", type = SourceType.DIRECT, quality = 1080, priority = 90))

        val episode = service.episodes(21).single()
        assertEquals(2, episode.sources.size)

        val streams = service.streams(21, 1)
        assertEquals(listOf(1080), streams.map { it.quality })
        assertTrue(streams.single().url.endsWith(".m3u8"))
    }


    @Test
    fun directCandidatesAlwaysPrecedeExtractorCandidates() = runBlocking {
        val root = createTempDirectory("shinigami-catalog-order")
        val direct = "https://example.com/video.m3u8"
        val extractor = object : streamix.core.StreamixExtractor {
            override val id = "extractor-order"
            override val domains = setOf("example.com")
            override suspend fun extract(request: streamix.core.StreamixExtractorRequest) =
                streamix.core.StreamixExtractorResult(
                    streams = listOf(
                        streamix.core.ProviderStream(
                            providerId = "catalog-test",
                            url = "https://media.example.com/from-extractor.m3u8",
                            type = "m3u8",
                            quality = 1080
                        )
                    )
                )
        }
        val service = CatalogService(
            FileCatalogRepository(root.resolve("episodes.json")),
            StreamixExtractorRegistry(listOf(extractor))
        )
        val extractorSource = service.upsertSource(EpisodeSource("extractor", 21, 1, "https://example.com/episode", type = SourceType.EXTRACTOR, priority = 1000))
        val directSource = service.upsertSource(EpisodeSource("direct", 21, 1, direct, priority = 1, type = SourceType.DIRECT))
        val streams = service.streams(21, 1)
        assertEquals("catalog:" + directSource.id, streams.single().providerId)
        assertEquals(direct, streams.single().url)
    }

    @Test
    fun extractorCandidatesFallThroughToNextMatchingExtractor() = runBlocking {
        val root = createTempDirectory("shinigami-catalog-extractor-fallback")
        val first = object : streamix.core.StreamixExtractor {
            override val id = "first"
            override val domains = setOf("example.com")
            override suspend fun extract(request: streamix.core.StreamixExtractorRequest): streamix.core.StreamixExtractorResult {
                throw IllegalStateException("first extractor failed")
            }
        }
        val second = object : streamix.core.StreamixExtractor {
            override val id = "second"
            override val domains = setOf("example.com")
            override suspend fun extract(request: streamix.core.StreamixExtractorRequest) =
                streamix.core.StreamixExtractorResult(
                    streams = listOf(
                        streamix.core.ProviderStream(
                            providerId = "second",
                            url = "https://example.com/recovered.m3u8",
                            type = "m3u8",
                            quality = 720
                        )
                    )
                )
        }
        val service = CatalogService(
            FileCatalogRepository(root.resolve("episodes.json")),
            StreamixExtractorRegistry(listOf(first, second))
        )
        service.upsertSource(EpisodeSource("extractor", 22, 1, "https://example.com/episode", type = SourceType.EXTRACTOR))
        val streams = service.streams(22, 1)
        assertEquals(listOf("https://example.com/recovered.m3u8"), streams.map { it.url })
    }

    @Test
    fun sourceTierBeatsResolutionAcrossTiers() = runBlocking {
        val root = createTempDirectory("shinigami-catalog-quality-first")
        val extractor = object : streamix.core.StreamixExtractor {
            override val id = "quality-first-extractor"
            override val domains = setOf("example.com")
            override suspend fun extract(request: streamix.core.StreamixExtractorRequest) =
                streamix.core.StreamixExtractorResult(
                    streams = listOf(
                        streamix.core.ProviderStream(
                            providerId = "quality-first",
                            url = "https://media.example.com/720p-extracted.m3u8",
                            type = "m3u8",
                            quality = 720
                        )
                    )
                )
        }
        val service = CatalogService(
            FileCatalogRepository(root.resolve("episodes.json")),
            StreamixExtractorRegistry(listOf(extractor))
        )
        service.upsertSource(
            EpisodeSource("720-extractor", 26, 1, "https://example.com/episode", type = SourceType.EXTRACTOR, quality = 720)
        )
        service.upsertSource(
            EpisodeSource("480-direct", 26, 1, "https://example.com/480.m3u8", type = SourceType.DIRECT, quality = 480)
        )

        val streams = service.streams(26, 1)

        assertEquals(listOf(480), streams.map { it.quality })
        assertTrue(streams.single().url.contains("480.m3u8"))
    }

    @Test
    fun directSourcesSupport4kAndPreferHigherKnownQuality() = runBlocking {
        val root = createTempDirectory("shinigami-catalog-4k")
        val service = CatalogService(
            FileCatalogRepository(root.resolve("episodes.json")),
            StreamixExtractorRegistry()
        )
        service.upsertSource(
            EpisodeSource("direct-1080", 260, 1, "https://example.com/1080.m3u8", type = SourceType.DIRECT, quality = 1080)
        )
        service.upsertSource(
            EpisodeSource("direct-4k", 260, 1, "https://example.com/2160.m3u8", type = SourceType.DIRECT, quality = 2160)
        )
        service.upsertSource(
            EpisodeSource("direct-720", 260, 1, "https://example.com/720.m3u8", type = SourceType.DIRECT, quality = 720)
        )

        val streams = service.streams(260, 1)

        assertEquals(listOf(2160), streams.map { it.quality })
        assertTrue(streams.single().url.contains("2160.m3u8"))
    }

    @Test
    fun resolutionLabelsRecognize4kWhenNumericQualityIsMissing() = runBlocking {
        val root = createTempDirectory("shinigami-catalog-4k-label")
        val service = CatalogService(
            FileCatalogRepository(root.resolve("episodes.json")),
            StreamixExtractorRegistry()
        )
        service.upsertSource(
            EpisodeSource("direct-1080-label", 261, 1, "https://example.com/1080.m3u8", type = SourceType.DIRECT, resolution = "1080p")
        )
        service.upsertSource(
            EpisodeSource("direct-4k-label", 261, 1, "https://example.com/4k.m3u8", type = SourceType.DIRECT, resolution = "4K")
        )

        val streams = service.streams(261, 1)

        assertEquals(listOf(2160), streams.map { it.quality })
        assertTrue(streams.single().url.contains("/4k.m3u8"))
    }

    @Test
    fun dimensionResolutionLabelUsesPixelHeight() = runBlocking {
        val root = createTempDirectory("shinigami-catalog-dimension-label")
        val service = CatalogService(
            FileCatalogRepository(root.resolve("episodes.json")),
            StreamixExtractorRegistry()
        )
        service.upsertSource(
            EpisodeSource(
                "direct-dimension",
                262,
                1,
                "https://example.com/dimension.m3u8",
                type = SourceType.DIRECT,
                resolution = "1920x1080"
            )
        )

        val streams = service.streams(262, 1)

        assertEquals(listOf(1080), streams.map { it.quality })
        assertTrue(streams.single().url.contains("dimension.m3u8"))
    }

    @Test
    fun unicodeMultiplicationResolutionLabelUsesPixelHeight() = runBlocking {
        val root = createTempDirectory("shinigami-catalog-unicode-dimension-label")
        val service = CatalogService(
            FileCatalogRepository(root.resolve("episodes.json")),
            StreamixExtractorRegistry()
        )
        service.upsertSource(
            EpisodeSource(
                "direct-unicode-dimension",
                263,
                1,
                "https://example.com/unicode-dimension.m3u8",
                type = SourceType.DIRECT,
                resolution = "1920×1080"
            )
        )

        val streams = service.streams(263, 1)

        assertEquals(listOf(1080), streams.map { it.quality })
        assertTrue(streams.single().url.contains("unicode-dimension.m3u8"))
    }

    @Test
    fun sameResolutionExhaustsDirectMirrorsBeforeResolver() = runBlocking {
        val root = createTempDirectory("shinigami-catalog-tier-fallback")
        val resolver = object : CatalogResolver {
            override val id = "tier-resolver"
            override val domains = setOf("example.com")
            override suspend fun resolve(source: EpisodeSource) =
                CatalogResolverResult(
                    streams = listOf(
                        StreamRef(
                            "resolver",
                            "https://media.example.com/resolved.m3u8",
                            720,
                            "m3u8"
                        )
                    )
                )
        }
        val service = CatalogService(
            FileCatalogRepository(root.resolve("episodes.json")),
            StreamixExtractorRegistry(),
            CatalogResolverRegistry(listOf(resolver))
        )
        val first = service.upsertSource(
            EpisodeSource("direct-a", 27, 1, "https://example.com/a.m3u8", type = SourceType.DIRECT, quality = 720, priority = 100)
        )
        val second = service.upsertSource(
            EpisodeSource("direct-b", 27, 1, "https://example.com/b.m3u8", type = SourceType.DIRECT, quality = 720, priority = 90)
        )
        service.upsertSource(
            EpisodeSource("resolver", 27, 1, "https://example.com/container", type = SourceType.RESOLVER, quality = 720)
        )

        val streams = service.streams(27, 1, failedSourceIds = setOf(first.id))

        assertEquals(listOf("catalog:" + second.id), streams.map { it.providerId })
    }

    @Test
    fun directFailureThenResolverFailureFallsThroughToExtractor() = runBlocking {
        val root = createTempDirectory("shinigami-catalog-full-fallback")
        val extractor = object : streamix.core.StreamixExtractor {
            override val id = "full-fallback-extractor"
            override val domains = setOf("example.com")
            override suspend fun extract(request: streamix.core.StreamixExtractorRequest) =
                streamix.core.StreamixExtractorResult(
                    streams = listOf(
                        streamix.core.ProviderStream(
                            providerId = "extractor",
                            url = "https://example.com/720p-final.m3u8",
                            type = "m3u8",
                            quality = 720
                        )
                    )
                )
        }
        val failingResolver = object : CatalogResolver {
            override val id = "failing-resolver"
            override val domains = setOf("example.com")
            override suspend fun resolve(source: EpisodeSource) =
                CatalogResolverResult(failureReason = "simulated resolver failure")
        }
        val service = CatalogService(
            FileCatalogRepository(root.resolve("episodes.json")),
            StreamixExtractorRegistry(listOf(extractor)),
            CatalogResolverRegistry(listOf(failingResolver))
        )
        val direct = service.upsertSource(
            EpisodeSource("720-direct", 28, 1, "https://example.com/720.m3u8", type = SourceType.DIRECT, quality = 720)
        )
        service.upsertSource(
            EpisodeSource("720-resolver", 28, 1, "https://example.com/container", type = SourceType.RESOLVER, quality = 720)
        )
        service.upsertSource(
            EpisodeSource("720-extractor", 28, 1, "https://example.com/episode", type = SourceType.EXTRACTOR, quality = 720)
        )
        val streams = service.streams(28, 1, failedSourceIds = setOf(direct.id))

        assertEquals(listOf(720), streams.map { it.quality })
        assertTrue(streams.single().url.contains("720p-final.m3u8"))
    }

    @Test
    fun sourceHealthPersistsAndCanBeDisabled() = runBlocking {
        val root = createTempDirectory("shinigami-catalog-health")
        val service = CatalogService(
            FileCatalogRepository(root.resolve("episodes.json")),
            StreamixExtractorRegistry()
        )
        val extractor = object : streamix.core.StreamixExtractor {
            override val id = "health-test"
            override val domains = setOf("example.com")
            override suspend fun extract(request: streamix.core.StreamixExtractorRequest) =
                streamix.core.StreamixExtractorResult(
                    streams = listOf(
                        streamix.core.ProviderStream(
                            providerId = "health-test",
                            url = "https://media.example.com/health.m3u8",
                            type = "m3u8",
                            quality = 720
                        )
                    )
                )
        }
        val serviceWithExtractor = CatalogService(
            FileCatalogRepository(root.resolve("episodes.json")),
            StreamixExtractorRegistry(listOf(extractor))
        )
        val healthSource = serviceWithExtractor.upsertSource(
            EpisodeSource("health", 23, 1, "https://example.com/page", type = SourceType.EXTRACTOR)
        )
        val checked = serviceWithExtractor.checkSource(23, 1, healthSource.id)
        assertTrue(checked != null)
        assertTrue(checked!!.lastCheckedAt != null)
        assertTrue(checked.health != SourceHealth.UNKNOWN)
        val disabled = service.setSourceEnabled(23, 1, healthSource.id, false)
        assertEquals(false, disabled!!.enabled)
        assertEquals(false, serviceWithExtractor.episodes(23).single().sources.single().enabled)
    }

    @Test
    fun resolverHealthPersistsHttpEvidenceAndClassifiesExpired() = runBlocking {
        val root = createTempDirectory("shinigami-catalog-resolver-health")
        val resolver = object : CatalogResolver {
            override val id = "health-resolver"
            override val domains = setOf("example.com")
            override suspend fun resolve(source: EpisodeSource) =
                CatalogResolverResult(
                    httpStatus = 403,
                    finalUrl = source.sourceUrl,
                    failureReason = "HTTP 403"
                )
        }
        val service = CatalogService(
            FileCatalogRepository(root.resolve("episodes.json")),
            StreamixExtractorRegistry(),
            CatalogResolverRegistry(listOf(resolver))
        )
        val source = service.upsertSource(
            EpisodeSource(
                "resolver-health",
                23,
                2,
                "https://example.com/container",
                type = SourceType.RESOLVER
            )
        )

        val checked = service.checkSource(23, 2, source.id)!!

        assertEquals(SourceHealth.EXPIRED, checked.health)
        assertEquals(403, checked.lastHttpStatus)
        assertEquals("HTTP 403", checked.lastFailureReason)
        assertTrue(checked.lastCheckedAt != null)
    }

    @Test
    fun rejectsLocalSourceUrl() {
        val service = CatalogService(
            FileCatalogRepository(createTempDirectory("shinigami-catalog-ssrf").resolve("episodes.json")),
            StreamixExtractorRegistry()
        )
        val error = runCatching {
            service.upsertSource(EpisodeSource("local", 1, 1, "http://localhost:8080/internal", type = SourceType.EXTRACTOR))
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun extractorCancellationIsNotSwallowed() = runBlocking {
        val extractor = object : streamix.core.StreamixExtractor {
            override val id = "cancel-test"
            override val domains = setOf("example.com")
            override suspend fun extract(request: streamix.core.StreamixExtractorRequest): streamix.core.StreamixExtractorResult {
                throw kotlinx.coroutines.CancellationException("cancelled")
            }
        }
        val registry = StreamixExtractorRegistry(listOf(extractor))
        val service = CatalogService(
            FileCatalogRepository(createTempDirectory("shinigami-catalog-cancel").resolve("episodes.json")),
            registry
        )
        service.upsertSource(EpisodeSource("cancel", 88, 1, "https://example.com/embed", type = SourceType.EXTRACTOR))
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            service.streams(88, 1)
        }
    }

    @Test
    fun concurrentUpsertsRemainConsistent() {
        val root = createTempDirectory("shinigami-catalog-concurrency")
        val service = CatalogService(
            FileCatalogRepository(root.resolve("episodes.json")),
            StreamixExtractorRegistry()
        )
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val tasks = (1..32).map { episode ->
                pool.submit {
                    service.upsertSource(
                        EpisodeSource(
                            id = "",
                            anilistId = 77,
                            episodeNumber = episode,
                            sourceUrl = "https://example.com/$episode.m3u8",
                            type = SourceType.DIRECT,
                            quality = 720
                        )
                    )
                }
            }
            tasks.forEach { it.get(5, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(32, service.episodes(77).size)
            assertTrue(service.episodes(77).all { it.sources.size == 1 })
        } finally {
            pool.shutdownNow()
            pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    @Test
    fun rejectsPrivateAddressRanges() {
        val service = CatalogService(
            FileCatalogRepository(createTempDirectory("shinigami-catalog-private").resolve("episodes.json")),
            StreamixExtractorRegistry()
        )
        val urls = listOf(
            "http://127.0.0.1:8080/internal",
            "http://10.0.0.1/internal",
            "http://192.168.1.1/internal",
            "http://[::1]/internal",
            "http://[fc00::1]/internal",
            "http://0.1.2.3/internal",
            "http://192.0.2.1/internal",
            "http://198.18.0.1/internal",
            "http://224.0.0.1/internal",
            "http://printer.local/internal",
            "http://[ff02::1]/internal"
        )
        urls.forEachIndexed { index, url ->
            val error = runCatching {
                service.upsertSource(EpisodeSource("private-$index", 1, 1, url, type = SourceType.EXTRACTOR))
            }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException, url)
        }
    }

    @Test
    fun rejectsUnresolvableSourceUrl() {
        val service = CatalogService(
            FileCatalogRepository(createTempDirectory("shinigami-catalog-dns").resolve("episodes.json")),
            StreamixExtractorRegistry()
        )
        val error = runCatching {
            service.upsertSource(EpisodeSource("dns", 1, 1, "https://does-not-exist.invalid/source", type = SourceType.EXTRACTOR))
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun sourceIdCannotMoveAcrossEpisodes() = runBlocking {
        val root = createTempDirectory("shinigami-catalog-id")
        val service = CatalogService(FileCatalogRepository(root.resolve("episodes.json")), StreamixExtractorRegistry())
        service.upsertSource(EpisodeSource("shared-id", 7, 1, "https://example.com/1.m3u8", type = SourceType.DIRECT))
        val saved = service.upsertSource(EpisodeSource("shared-id", 7, 2, "https://example.com/2.m3u8", type = SourceType.DIRECT))
        assertTrue(saved.id != "shared-id")
        assertEquals("https://example.com/1.m3u8", service.episodes(7).single { it.number == 1 }.sources.single().sourceUrl)
        assertEquals("https://example.com/2.m3u8", service.episodes(7).single { it.number == 2 }.sources.single().sourceUrl)
    }

    @Test
    fun rejectsNonHttpSchemesAndInvalidPorts() {
        val service = CatalogService(
            FileCatalogRepository(createTempDirectory("shinigami-catalog-invalid-url").resolve("episodes.json")),
            StreamixExtractorRegistry()
        )
        val urls = listOf(
            "ftp://example.com/video",
            "https://example.com:70000/video",
            "http://example.com:0/video"
        )
        urls.forEachIndexed { index, url ->
            val error = runCatching {
                service.upsertSource(EpisodeSource("invalid-url-$index", 1, 1, url, type = SourceType.DIRECT))
            }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException, url)
        }
    }

    @Test
    fun rejectsSourceCredentials() {
        val service = CatalogService(
            FileCatalogRepository(createTempDirectory("shinigami-catalog-userinfo").resolve("episodes.json")),
            StreamixExtractorRegistry()
        )
        val error = runCatching {
            service.upsertSource(EpisodeSource("userinfo", 1, 1, "https://user:pass@example.com/video", type = SourceType.DIRECT))
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun unsupportedAutoSourceDoesNotGuessProvider() = runBlocking {
        val root = createTempDirectory("shinigami-catalog-unsupported")
        val service = CatalogService(
            FileCatalogRepository(root.resolve("episodes.json")),
            StreamixExtractorRegistry()
        )
        service.upsertSource(
            EpisodeSource("unsupported", 99, 3, "https://example.com/embed/3", type = SourceType.RESOLVER)
        )

        assertTrue(service.streams(99, 3).isEmpty())
    }

    @Test
    fun disabledSourceIsNotResolved() = runBlocking {
        val root = createTempDirectory("shinigami-catalog-disabled")
        val service = CatalogService(
            FileCatalogRepository(root.resolve("episodes.json")),
            StreamixExtractorRegistry()
        )
        service.upsertSource(
            EpisodeSource(
                "disabled", 21, 2, "https://example.com/720.m3u8",
                type = SourceType.DIRECT,
                quality = 720, enabled = false
            )
        )
        assertTrue(service.episodes(21).single().sources.single().enabled.not())
        assertFalse(service.streams(21, 2).isNotEmpty())
    }
    @Test
    fun bulkImportPreservesExistingSourceHealth() {
        val root = createTempDirectory("shinigami-catalog-bulk-health")
        val repository = FileCatalogRepository(root.resolve("episodes.json"))
        val service = CatalogService(repository, StreamixExtractorRegistry())

        val existing = service.upsertSource(
            EpisodeSource(
                "existing",
                21,
                1,
                "https://example.com/existing.m3u8",
                type = SourceType.DIRECT,
                health = SourceHealth.STREAMING,
                lastCheckedAt = 1234L,
                lastHttpStatus = 200
            )
        )

        service.bulkUpsertSources(
            listOf(
                EpisodeSource(
                    existing.id,
                    21,
                    1,
                    "https://example.com/existing-updated.m3u8",
                    type = SourceType.DIRECT,
                    health = SourceHealth.UNKNOWN
                )
            )
        )

        val persisted = service.episodes(21).single().sources.single()
        assertEquals("https://example.com/existing-updated.m3u8", persisted.sourceUrl)
        assertEquals(SourceHealth.STREAMING, persisted.health)
        assertEquals(1234L, persisted.lastCheckedAt)
        assertEquals(200, persisted.lastHttpStatus)
    }


    @Test
    fun bulkUpsertCannotReplaceSourceIdOwnedByAnotherAnime() {
        val root = createTempDirectory("shinigami-catalog-cross-anime-id")
        val repository = FileCatalogRepository(root.resolve("episodes.json"))
        repository.upsertSource(
            EpisodeSource("globally-owned-id", 100, 1, "https://example.com/original.m3u8", type = SourceType.DIRECT)
        )

        assertFailsWith<IllegalArgumentException> {
            repository.upsertSources(
                listOf(EpisodeSource("globally-owned-id", 200, 1, "https://example.com/replacement.m3u8", type = SourceType.DIRECT))
            )
        }

        assertEquals("https://example.com/original.m3u8", repository.getEpisode(100, 1)!!.sources.single().sourceUrl)
        assertTrue(repository.getEpisode(200, 1) == null)
    }

    @Test
    fun nativeExtractorOutputDoesNotForwardSensitiveHeadersAcrossOrigins() = runBlocking {
        val root = createTempDirectory("shinigami-catalog-extractor-cross-origin-headers")
        val extractor = object : streamix.core.StreamixExtractor {
            override val id = "cross-origin-extractor"
            override val domains = setOf("example.com")
            override suspend fun extract(request: streamix.core.StreamixExtractorRequest) =
                streamix.core.StreamixExtractorResult(
                    streams = listOf(
                        streamix.core.ProviderStream(
                            providerId = id,
                            url = "https://example.net/video.m3u8",
                            quality = 720,
                            type = "m3u8",
                            headers = mapOf(
                                "Authorization" to "Bearer extractor-secret",
                                "Cookie" to "extractor-session=secret",
                                "X-Playback" to "extractor-ok"
                            ),
                            referer = "https://example.com/private/extractor-token"
                        )
                    ),
                    subtitles = listOf(
                        streamix.core.ProviderSubtitle(
                            url = "https://example.net/captions.vtt",
                            language = "en",
                            headers = mapOf(
                                "Authorization" to "Bearer subtitle-secret",
                                "Cookie" to "subtitle-session=secret",
                                "X-Subtitle" to "subtitle-ok"
                            ),
                            referer = "https://example.com/private/subtitle-token"
                        )
                    )
                )
        }
        val service = CatalogService(
            FileCatalogRepository(root.resolve("episodes.json")),
            StreamixExtractorRegistry(listOf(extractor))
        )
        service.upsertSource(
            EpisodeSource(
                id = "extractor-cross-origin",
                anilistId = 98766,
                episodeNumber = 1,
                sourceUrl = "https://example.com/embed",
                type = SourceType.EXTRACTOR,
                headers = mapOf(
                    "Authorization" to "Bearer source-secret",
                    "Cookie" to "source-session=secret",
                    "User-Agent" to "ShinigamiTest"
                ),
                referer = "https://example.com/private/source-token"
            )
        )

        val stream = service.streams(98766, 1).single()

        assertEquals("ShinigamiTest", stream.headers["User-Agent"])
        assertEquals("extractor-ok", stream.headers["X-Playback"])
        assertFalse(stream.headers.keys.any { it.equals("Authorization", true) })
        assertFalse(stream.headers.keys.any { it.equals("Cookie", true) })
        assertEquals(null, stream.referer)
        val subtitle = stream.subtitles.single()
        assertEquals("subtitle-ok", subtitle.headers["X-Subtitle"])
        assertFalse(subtitle.headers.keys.any { it.equals("Authorization", true) })
        assertFalse(subtitle.headers.keys.any { it.equals("Cookie", true) })
        assertEquals(null, subtitle.referer)
    }

    @Test
    fun playbackHeadersCollapseCaseInsensitiveDuplicatesWithResolverOverride() = runBlocking {
        val root = createTempDirectory("shinigami-catalog-header-case")
        val resolver = object : CatalogResolver {
            override val id = "header-case-test"
            override val domains = setOf("example.com")
            override suspend fun resolve(source: EpisodeSource) = CatalogResolverResult(
                streams = listOf(
                    StreamRef(
                        providerId = id,
                        url = "https://example.com/video.m3u8",
                        quality = 720,
                        type = "m3u8",
                        headers = linkedMapOf(
                            "USER-AGENT" to "resolver-first",
                            "user-agent" to "resolver-last"
                        )
                    )
                )
            )
        }
        val service = CatalogService(
            FileCatalogRepository(root.resolve("episodes.json")),
            StreamixExtractorRegistry(),
            CatalogResolverRegistry(listOf(resolver))
        )
        service.upsertSource(
            EpisodeSource(
                id = "header-case",
                anilistId = 98766,
                episodeNumber = 1,
                sourceUrl = "https://example.com/embed",
                type = SourceType.RESOLVER,
                headers = linkedMapOf(
                    "User-Agent" to "source-first",
                    "user-agent" to "source-last"
                )
            )
        )

        val stream = service.streams(98766, 1).single()
        assertEquals(1, stream.headers.keys.count { it.equals("user-agent", true) })
        assertEquals("resolver-last", stream.headers.entries.single { it.key.equals("user-agent", true) }.value)
    }

    @Test
    fun resolverDoesNotForwardSensitiveHeadersAcrossOrigins() = runBlocking {
        val root = createTempDirectory("shinigami-catalog-cross-origin-headers")
        val resolver = object : CatalogResolver {
            override val id = "cross-origin-header-test"
            override val domains = setOf("example.com")
            override suspend fun resolve(source: EpisodeSource) = CatalogResolverResult(
                streams = listOf(
                    StreamRef(
                        providerId = id,
                        url = "https://example.com:8443/video.m3u8",
                        quality = 720,
                        type = "m3u8",
                        headers = mapOf(
                            "Authorization" to "Bearer resolver-secret",
                            "Cookie" to "resolver-session=secret",
                            "X-Playback" to "resolver-ok"
                        ),
                        referer = "https://example.com/private/resolver-token",
                        subtitles = listOf(
                            SubtitleRef(
                                url = "https://example.net/captions.vtt",
                                language = "en",
                                headers = mapOf(
                                    "Authorization" to "Bearer subtitle-secret",
                                    "Cookie" to "subtitle-session=secret",
                                    "X-Subtitle" to "subtitle-ok"
                                ),
                                referer = "https://example.net/private/subtitle-token"
                            )
                        )
                    )
                )
            )
        }
        val service = CatalogService(
            FileCatalogRepository(root.resolve("episodes.json")),
            StreamixExtractorRegistry(),
            CatalogResolverRegistry(listOf(resolver))
        )
        service.upsertSource(
            EpisodeSource(
                id = "cross-origin",
                anilistId = 98765,
                episodeNumber = 1,
                sourceUrl = "https://example.com/embed",
                type = SourceType.RESOLVER,
                headers = mapOf(
                    "Authorization" to "Bearer secret",
                    "Cookie" to "session=secret",
                    "User-Agent" to "ShinigamiTest",
                    "X-Playback" to "ok"
                ),
                referer = "https://example.com/private/path?token=secret"
            )
        )

        val stream = service.streams(98765, 1).single()

        assertEquals("ShinigamiTest", stream.headers["User-Agent"])
        assertEquals("resolver-ok", stream.headers["X-Playback"])
        assertFalse(stream.headers.keys.any { it.equals("Authorization", true) })
        assertFalse(stream.headers.keys.any { it.equals("Cookie", true) })
        assertFalse(stream.headers.keys.any { it.equals("Referer", true) })
        assertEquals(null, stream.referer)
        val subtitle = stream.subtitles.single()
        assertEquals("subtitle-ok", subtitle.headers["X-Subtitle"])
        assertFalse(subtitle.headers.keys.any { it.equals("Authorization", true) })
        assertFalse(subtitle.headers.keys.any { it.equals("Cookie", true) })
        assertEquals(null, subtitle.referer)
    }

}
