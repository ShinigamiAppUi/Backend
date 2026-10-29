package streamix.api

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import streamix.catalog.CatalogService
import streamix.catalog.FileCatalogRepository
import streamix.core.StreamixExtractorRegistry
import streamix.runtime.StreamixService

class StreamixExternalSessionRouteTest {
    @Test
    fun externalSessionRouteUsesInjectedVerifierAndRejectsInvalidCredentials() {
        val temp = Files.createTempDirectory("shinigami-auth-session-verifier")
        val auth = streamix.auth.AuthRuntime(temp.resolve("auth"))
        val registry = StreamixExtractorRegistry(emptyList())
        val catalog = CatalogService(
            repository = FileCatalogRepository(temp.resolve("catalog/episodes.json")),
            extractors = registry
        )
        val verifier = streamix.auth.ExternalIdentityVerifier { provider, credential ->
            when {
                provider != "test-provider" -> null
                credential == "verifier-error" -> throw IllegalArgumentException("upstream verifier configuration error")
                credential == "valid-credential" -> streamix.auth.ExternalIdentity(
                    provider = provider,
                    subject = "verified-subject",
                    displayName = "Verified User"
                )
                credential == "invalid-identity" -> streamix.auth.ExternalIdentity(
                    provider = provider,
                    subject = " ",
                    displayName = "Broken Verified Identity"
                )
                credential == "mismatched-provider" -> streamix.auth.ExternalIdentity(
                    provider = "other-provider",
                    subject = "verified-subject",
                    displayName = "Mismatched Provider"
                )
                else -> null
            }
        }
        val server = StreamixHttpServer(
            service = StreamixService(catalog = catalog),
            authRuntime = auth,
            catalog = catalog,
            host = "127.0.0.1",
            port = 0,
            externalIdentityVerifier = verifier
        )
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

        try {
            server.start()
            val endpoint = "http://127.0.0.1:${server.boundPort()}/api/v1/auth/session"
            val invalid = client.send(
                HttpRequest.newBuilder().uri(URI(endpoint))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                        """{"provider":"test-provider","credential":"invalid"}"""
                    )).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(401, invalid.statusCode())
            assertTrue(invalid.body().contains("invalid authentication credential"))

            val verifierFailure = client.send(
                HttpRequest.newBuilder().uri(URI(endpoint))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                        """{"provider":"test-provider","credential":"verifier-error"}"""
                    )).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(500, verifierFailure.statusCode(), verifierFailure.body())

            // A verifier that returns a malformed trusted identity is a server-side
            // integration failure, not evidence that the user's credential is invalid.
            val invalidVerifiedIdentity = client.send(
                HttpRequest.newBuilder().uri(URI(endpoint))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                        """{"provider":"test-provider","credential":"invalid-identity"}"""
                    )).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(500, invalidVerifiedIdentity.statusCode(), invalidVerifiedIdentity.body())

            val mismatchedProvider = client.send(
                HttpRequest.newBuilder().uri(URI(endpoint))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                        """{"provider":"test-provider","credential":"mismatched-provider"}"""
                    )).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(500, mismatchedProvider.statusCode(), mismatchedProvider.body())
            assertTrue(mismatchedProvider.body().contains("identity verifier returned an invalid identity"))

            val malformedRequest = client.send(
                HttpRequest.newBuilder().uri(URI(endpoint))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{not-json")).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(400, malformedRequest.statusCode(), malformedRequest.body())

            val blankProvider = client.send(
                HttpRequest.newBuilder().uri(URI(endpoint))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                        """{"provider":"   ","credential":"valid-credential"}"""
                    )).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(400, blankProvider.statusCode(), blankProvider.body())
            assertTrue(blankProvider.body().contains("provider is required"), blankProvider.body())

            val blankCredential = client.send(
                HttpRequest.newBuilder().uri(URI(endpoint))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                        """{"provider":"test-provider","credential":"   "}"""
                    )).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(400, blankCredential.statusCode(), blankCredential.body())
            assertTrue(blankCredential.body().contains("credential is required"), blankCredential.body())

            val oversizedRequest = client.send(
                HttpRequest.newBuilder().uri(URI(endpoint))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(" ".repeat(64 * 1024 + 1))).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(413, oversizedRequest.statusCode(), oversizedRequest.body())

            val missingProvider = client.send(
                HttpRequest.newBuilder().uri(URI(endpoint))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"credential":"valid-credential"}""")).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(400, missingProvider.statusCode())

            val nestedSessionRoute = client.send(
                HttpRequest.newBuilder().uri(URI("${endpoint}/unexpected"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                        """{"provider":"test-provider","credential":"valid-credential"}"""
                    )).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(404, nestedSessionRoute.statusCode(), nestedSessionRoute.body())

            val sessionWithoutToken = client.send(
                HttpRequest.newBuilder().uri(URI(endpoint)).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(401, sessionWithoutToken.statusCode(), sessionWithoutToken.body())
            assertTrue(sessionWithoutToken.body().contains("authorization token is required"), sessionWithoutToken.body())

            val sessionWithUnknownToken = client.send(
                HttpRequest.newBuilder().uri(URI(endpoint))
                    .header("Authorization", "Bearer unknown-session-token")
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(401, sessionWithUnknownToken.statusCode(), sessionWithUnknownToken.body())

            val logoutWithoutToken = client.send(
                HttpRequest.newBuilder().uri(URI(endpoint.replace("/api/v1/auth/session", "/api/v1/auth/logout")))
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(401, logoutWithoutToken.statusCode(), logoutWithoutToken.body())

            val unsupportedSessionMethod = client.send(
                HttpRequest.newBuilder().uri(URI(endpoint))
                    .method("DELETE", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(405, unsupportedSessionMethod.statusCode(), unsupportedSessionMethod.body())
            assertEquals("GET, POST", unsupportedSessionMethod.headers().firstValue("Allow").orElse(""))

            val valid = client.send(
                HttpRequest.newBuilder().uri(URI(endpoint))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                        """{"provider":"test-provider","credential":"valid-credential"}"""
                    )).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(200, valid.statusCode(), valid.body())
            assertTrue(valid.body().contains("Verified User"), valid.body())
            val token = com.google.gson.JsonParser.parseString(valid.body())
                .asJsonObject.get("token")?.asString
                ?: error("successful session response must include a token")

            val current = client.send(
                HttpRequest.newBuilder().uri(URI(endpoint))
                    .header("Authorization", "Bearer $token")
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(200, current.statusCode(), current.body())
            assertTrue(current.body().contains("Verified User"), current.body())

            val nestedLogoutRoute = client.send(
                HttpRequest.newBuilder().uri(URI("http://127.0.0.1:${server.boundPort()}/api/v1/auth/logout/unexpected"))
                    .header("Authorization", "Bearer ${token}")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(404, nestedLogoutRoute.statusCode(), nestedLogoutRoute.body())

            val logout = client.send(
                HttpRequest.newBuilder().uri(URI("http://127.0.0.1:${server.boundPort()}/api/v1/auth/logout"))
                    .header("Authorization", "Bearer $token")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(200, logout.statusCode(), logout.body())

            val afterLogout = client.send(
                HttpRequest.newBuilder().uri(URI(endpoint))
                    .header("Authorization", "Bearer $token")
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(401, afterLogout.statusCode(), afterLogout.body())
        } finally {
            server.close()
        }
    }

    @Test
    fun externalSessionRouteFailsClosedWhenVerifierIsNotConfigured() {
        val temp = Files.createTempDirectory("shinigami-auth-session-no-verifier")
        val auth = streamix.auth.AuthRuntime(temp.resolve("auth"))
        val registry = StreamixExtractorRegistry(emptyList())
        val catalog = CatalogService(
            repository = FileCatalogRepository(temp.resolve("catalog/episodes.json")),
            extractors = registry
        )
        val server = StreamixHttpServer(
            service = StreamixService(catalog = catalog),
            authRuntime = auth,
            catalog = catalog,
            host = "127.0.0.1",
            port = 0
        )
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

        try {
            server.start()
            val endpoint = "http://127.0.0.1:${server.boundPort()}/api/v1/auth/session"
            val response = client.send(
                HttpRequest.newBuilder().uri(URI(endpoint))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                        """{"provider":"test-provider","credential":"untrusted-credential"}"""
                    )).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(503, response.statusCode(), response.body())
            assertTrue(response.body().contains("external identity verifier is not configured"), response.body())
        } finally {
            server.close()
        }
    }

}
