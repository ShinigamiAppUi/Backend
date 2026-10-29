package streamix.readingcatalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadingHttpTransportContractTest {
    @Test fun sharedHtmlTransportHasAStableDefaultResponseLimit() {
        assertEquals(2 * 1024 * 1024, MAX_READING_HTTP_RESPONSE_BYTES)
    }

    @Test fun sharedTransportRequiresHttpsWithoutCredentialsOrNonstandardPorts() {
        assertTrue(isSafeReadingHttpUrl("https://reader.example/chapter/1"))
        assertTrue(isSafeReadingHttpUrl("https://reader.example:443/chapter/1?token=x"))
        assertFalse(isSafeReadingHttpUrl("http://reader.example/chapter/1"))
        assertFalse(isSafeReadingHttpUrl("https://user:pass@reader.example/chapter/1"))
        assertFalse(isSafeReadingHttpUrl("https://reader.example:8443/chapter/1"))
        assertFalse(isSafeReadingHttpUrl("https://localhost/chapter/1"))
        assertFalse(isSafeReadingHttpUrl("https://private.local/chapter/1"))
        assertFalse(isSafeReadingHttpUrl("https://127.0.0.1/chapter/1"))
        assertFalse(isSafeReadingHttpUrl("https://192.168.1.10/chapter/1"))
        assertFalse(isSafeReadingHttpUrl("https://[::1]/chapter/1"))
        assertFalse(isSafeReadingHttpUrl("not a url"))
    }

    @Test fun cookieBootstrapTransportRejectsUnsafeOrCrossOriginBootstrapTargets() {
        assertNull(ReadingHttpTransport.getTextWithCookieBootstrap("http://reader.example/api", "https://reader.example/"))
        assertNull(ReadingHttpTransport.getTextWithCookieBootstrap("https://reader.example/api", "https://other.example/"))
        assertNull(ReadingHttpTransport.getTextWithCookieBootstrap("https://user@reader.example/api", "https://reader.example/"))
    }

    @Test fun boundedReaderStillRejectsOversizedBodiesUsedBySharedTransport() {
        val bytes = ByteArray(MAX_READING_HTTP_RESPONSE_BYTES + 1) { 1 }
        assertNull(readBoundedReadingResponse(bytes.inputStream(), MAX_READING_HTTP_RESPONSE_BYTES))
    }

    @Test fun boundedReaderAcceptsBodiesAtTheSharedLimit() {
        val bytes = ByteArray(MAX_READING_HTTP_RESPONSE_BYTES) { 1 }
        assertNotNull(readBoundedReadingResponse(bytes.inputStream(), MAX_READING_HTTP_RESPONSE_BYTES))
    }
}
