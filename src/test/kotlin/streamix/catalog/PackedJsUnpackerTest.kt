package streamix.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PackedJsUnpackerTest {
    @Test
    fun decodesPackedSourceAndUrl() {
        val packed = "eval(function(p,a,c,k,e,r){return p}('0:\"1\";HEIGHT=1080',2,2,'src|https://cdn.example/video.mp4'.split('|')))"
        assertEquals(
            "src:\"https://cdn.example/video.mp4\";HEIGHT=1080",
            PackedJsUnpacker.unpackAndCombine(packed)
        )
    }

    @Test
    fun decodesBase62DictionaryIndex() {
        val packed = "eval(function(p,a,c,k,e,r){return p}('z',62,36,'x0|x1|x2|x3|x4|x5|x6|x7|x8|x9|x10|x11|x12|x13|x14|x15|x16|x17|x18|x19|x20|x21|x22|x23|x24|x25|x26|x27|x28|x29|x30|x31|x32|x33|x34|x35'.split('|')))"
        assertEquals("x35", PackedJsUnpacker.unpackAndCombine(packed))
    }

    @Test
    fun returnsNullForUnpackedScript() {
        assertNull(PackedJsUnpacker.unpackAndCombine("var player = true;"))
    }

    @Test
    fun rejectsDictionaryCountMismatch() {
        val packed = "eval(function(p,a,c,k,e,r){return p}('0',2,2,'only'.split('|')))"
        assertNull(PackedJsUnpacker.unpackAndCombine(packed))
    }
}
