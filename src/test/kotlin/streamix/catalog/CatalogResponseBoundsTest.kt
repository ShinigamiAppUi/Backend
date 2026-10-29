package streamix.catalog

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import java.io.ByteArrayInputStream

class CatalogResponseBoundsTest {
    @Test
    fun acceptsResponseAtConfiguredLimit() {
        val bytes = ByteArray(32) { it.toByte() }
        assertContentEquals(bytes, readBoundedCatalogResponse(ByteArrayInputStream(bytes), bytes.size))
    }

    @Test
    fun rejectsResponseOneByteOverLimit() {
        val bytes = ByteArray(33) { it.toByte() }
        assertNull(readBoundedCatalogResponse(ByteArrayInputStream(bytes), 32))
    }

    @Test
    fun rejectsChunkedStyleResponseWithoutDeclaredLengthWhenOversized() {
        val bytes = ByteArray(MAX_CATALOG_HTTP_RESPONSE_BYTES + 1)
        assertNull(readBoundedCatalogResponse(ByteArrayInputStream(bytes), MAX_CATALOG_HTTP_RESPONSE_BYTES))
    }

    @Test
    fun catalogResponseLimitIsFourMiB() {
        assertEquals(4 * 1024 * 1024, MAX_CATALOG_HTTP_RESPONSE_BYTES)
    }
}
