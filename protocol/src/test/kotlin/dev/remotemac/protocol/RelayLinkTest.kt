package dev.remotemac.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelayLinkTest {
    private val fp = ByteArray(32) { it.toByte() }
    private val k = Base64URL.encode(fp)
    private val a = Base64URL.encode(ByteArray(16) { 7 })

    @Test fun parsesWhatRmhelperPrints() {
        // url.Values.Encode: sorted keys, ':' as %3A, spaces as '+'.
        val l = RelayLink.parse("remotemac://relay?i=00112233445566778899aabbccddeeff&k=$k&s=$a&n=Tom%E2%80%99s+MacBook&r=relay.example.com%3A7456")!!
        assertEquals("relay.example.com", l.relay.host)
        assertEquals(7456, l.relay.port)
        assertEquals("00112233445566778899aabbccddeeff", l.macID)
        assertArrayEquals(fp, l.fingerprint)
        assertEquals("Tom’s MacBook", l.name)
        assertEquals(a, l.access)
        assertEquals(16, l.idBytes.size)
        assertEquals(0x11.toByte(), l.idBytes[1])
        assertEquals(0xff.toByte(), l.idBytes[15])
    }

    @Test fun ipv6Relay() {
        val l = RelayLink.parse("remotemac://relay?r=%5B2001%3Adb8%3A%3A1%5D%3A7456&i=00112233445566778899aabbccddeeff&k=$k&s=$a")!!
        assertEquals("2001:db8::1", l.relay.host)
        assertEquals(7456, l.relay.port)
        assertEquals("Mac", l.name)
    }

    @Test fun refusesBrokenLinks() {
        assertNull(RelayLink.parse("sill://pair?v=1"))
        assertNull(RelayLink.parse("remotemac://relay?r=relay.example.com:7456&i=00112233445566778899aabbccddeeff&k=$k")) // no access key
        assertNull(RelayLink.parse("remotemac://relay?r=relay.example.com:7456&i=00112233445566778899aabbccddeeff&k=$k&s=AAAA"))
        assertNull(RelayLink.parse("remotemac://relay?r=relay.example.com&i=00112233445566778899aabbccddeeff&k=$k&s=$a")) // no port
        assertNull(RelayLink.parse("remotemac://relay?r=relay.example.com:7456&i=0011&k=$k&s=$a"))
        assertNull(RelayLink.parse("remotemac://relay?r=relay.example.com:7456&i=00112233445566778899aabbccddeeff&k=short&s=$a"))
        assertNull(RelayLink.parse("remotemac://relay?r=a:1&r=b:2&i=00112233445566778899aabbccddeeff&k=$k&s=$a"))
    }
}
