package streamix.readingcatalog

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals

class NovelResponseLimitTest {
    @Test
    fun boundedReaderAcceptsBodyAtLimit() {
        val body = "chapter".toByteArray()
        assertContentEquals(body, readBoundedNovelResponse(ByteArrayInputStream(body), body.size))
    }

    @Test
    fun boundedReaderRejectsBodyOverLimit() {
        val body = "chapter".toByteArray()
        assertNull(readBoundedNovelResponse(ByteArrayInputStream(body), body.size - 1))
    }

    @Test
    fun boundedReaderRequiresPositiveLimit() {
        assertFailsWith<IllegalArgumentException> {
            readBoundedNovelResponse(ByteArrayInputStream(byteArrayOf(1)), 0)
        }
    }
    @Test
    fun boundedReaderMakesProgressWhenBulkReadReturnsZero() {
        val body = "chapter".toByteArray()
        val stream = object : InputStream() {
            private val delegate = ByteArrayInputStream(body)
            private var returnZero = true

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (returnZero) {
                    returnZero = false
                    return 0
                }
                return delegate.read(buffer, offset, length)
            }

            override fun read(): Int = delegate.read()
        }

        assertContentEquals(body, readBoundedNovelResponse(stream, body.size))
    }

}
