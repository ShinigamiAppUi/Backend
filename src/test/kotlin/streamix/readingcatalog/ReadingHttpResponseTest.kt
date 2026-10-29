package streamix.readingcatalog

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

class ReadingHttpResponseTest {
    @Test fun acceptsBodyExactlyAtLimit() {
        val body = "chapter".toByteArray()
        assertContentEquals(body, readBoundedReadingResponse(ByteArrayInputStream(body), body.size))
    }

    @Test fun rejectsBodyOverLimit() {
        val body = "chapter".toByteArray()
        assertNull(readBoundedReadingResponse(ByteArrayInputStream(body), body.size - 1))
    }

    @Test fun rejectsInvalidLimit() {
        assertFailsWith<IllegalArgumentException> {
            readBoundedReadingResponse(ByteArrayInputStream(byteArrayOf(1)), 0)
        }
    }

    @Test fun makesProgressWhenBulkReadReturnsZero() {
        val body = "chapter".toByteArray()
        val stream = object : InputStream() {
            private val delegate = ByteArrayInputStream(body)
            private var zeroOnce = true
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (zeroOnce) { zeroOnce = false; return 0 }
                return delegate.read(buffer, offset, length)
            }
            override fun read(): Int = delegate.read()
        }
        assertContentEquals(body, readBoundedReadingResponse(stream, body.size))
    }
}
