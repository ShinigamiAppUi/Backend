package streamix.catalog

import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CatalogPublicDnsTest {
    @Test
    fun returnsTheExactValidatedAddressCandidates() {
        val publicAddress = InetAddress.getByAddress(byteArrayOf(93, 184.toByte(), 216.toByte(), 34))
        var calls = 0
        val dns = CatalogPublicDns { host ->
            calls++
            assertEquals("media.example", host)
            listOf(publicAddress)
        }

        assertEquals(listOf(publicAddress), dns.lookup("Media.Example."))
        assertEquals(1, calls)
    }

    @Test
    fun rejectsDnsAnswerContainingAnyPrivateAddress() {
        val publicAddress = InetAddress.getByAddress(byteArrayOf(93, 184.toByte(), 216.toByte(), 34))
        val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val dns = CatalogPublicDns { listOf(publicAddress, loopback) }

        assertFailsWith<UnknownHostException> { dns.lookup("media.example") }
    }

    @Test
    fun rejectsLocalNamesBeforeResolution() {
        var calls = 0
        val dns = CatalogPublicDns { calls++; emptyList() }

        assertFailsWith<UnknownHostException> { dns.lookup("printer.local") }
        assertEquals(0, calls)
    }

    @Test
    fun rejectsIpv4MappedIpv6ThatEncodesLoopback() {
        val mappedLoopback = InetAddress.getByAddress(byteArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff.toByte(), 0xff.toByte(), 127, 0, 0, 1
        ))
        val dns = CatalogPublicDns { listOf(mappedLoopback) }

        assertFailsWith<UnknownHostException> { dns.lookup("media.example") }
    }

    @Test
    fun rejectsIpv4CompatibleAndSpecialPurposeIpv6Destinations() {
        val ipv4CompatiblePrivate = InetAddress.getByAddress(byteArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 192.toByte(), 168.toByte(), 1, 1
        ))
        val discardOnly = InetAddress.getByAddress(byteArrayOf(
            0x01, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1
        ))
        val protocolAssignment = InetAddress.getByAddress(byteArrayOf(
            0x20, 0x01, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1
        ))

        assertFailsWith<UnknownHostException> {
            CatalogPublicDns { listOf(ipv4CompatiblePrivate) }.lookup("media.example")
        }
        assertFailsWith<UnknownHostException> {
            CatalogPublicDns { listOf(discardOnly) }.lookup("media.example")
        }
        assertFailsWith<UnknownHostException> {
            CatalogPublicDns { listOf(protocolAssignment) }.lookup("media.example")
        }
    }

    @Test
    fun rejectsNat64AndOtherNonGlobalIpv6Destinations() {
        val nat64Private = InetAddress.getByAddress(byteArrayOf(
            0x00, 0x64, 0xff.toByte(), 0x9b.toByte(), 0, 0, 0, 0,
            0, 0, 0, 0, 10, 0, 0, 1
        ))
        val uniqueLocal = InetAddress.getByAddress(byteArrayOf(
            0xfc.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1
        ))

        assertFailsWith<UnknownHostException> { CatalogPublicDns { listOf(nat64Private) }.lookup("media.example") }
        assertFailsWith<UnknownHostException> { CatalogPublicDns { listOf(uniqueLocal) }.lookup("media.example") }
    }

    @Test
    fun rejectsIpv6TransitionAndTeredoRanges() {
        val sixToFour = InetAddress.getByAddress(byteArrayOf(
            0x20, 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 93, 184.toByte(), 216.toByte(), 34
        ))
        val teredo = InetAddress.getByAddress(byteArrayOf(
            0x20, 0x01, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1
        ))

        assertFailsWith<UnknownHostException> { CatalogPublicDns { listOf(sixToFour) }.lookup("media.example") }
        assertFailsWith<UnknownHostException> { CatalogPublicDns { listOf(teredo) }.lookup("media.example") }
    }

}
