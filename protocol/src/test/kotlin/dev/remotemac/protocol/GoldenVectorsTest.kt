package dev.remotemac.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Vectors printed by Sill's own Swift code (StreamProtocol compiled with a small main.swift, see
 * tools/vectors/main.swift). JSON is compared as parsed trees: key order does not matter on the wire.
 */
class GoldenVectorsTest {
    private fun same(expected: String, actual: ByteArray) {
        val e = Json.parseToJsonElement(expected)
        val a = Json.parseToJsonElement(String(actual))
        assertEquals(norm(e), norm(a))
    }

    // Swift writes 800 for a Double that is whole; Kotlin writes 800.0. Both decode alike.
    private fun norm(e: JsonElement): String = sorted(e).toString().replace(Regex("(\\d)\\.0([,}\\]])"), "$1$2")

    private fun sorted(e: JsonElement): JsonElement = when (e) {
        is kotlinx.serialization.json.JsonObject -> kotlinx.serialization.json.JsonObject(e.toSortedMap().mapValues { sorted(it.value) })
        is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(e.map { sorted(it) })
        else -> e
    }

    @Test fun input() {
        same("""{"pointer":{"_0":"leftDown","x":0.25,"y":0.5}}""", Input.pointer(PointerAction.LEFT_DOWN, 0.25, 0.5))
        same("""{"scroll":{"dx":0.01,"dy":-0.02,"x":0.1,"y":0.2}}""", Input.scroll(0.1, 0.2, 0.01, -0.02))
        same("""{"text":{"_0":"hé\n"}}""", Input.text("hé\n"))
        same("""{"scrollGesture":{"_0":"momentumBegan","x":0.3,"y":0.4}}""", Input.scrollGesture(ScrollPhase.MOMENTUM_BEGAN, 0.3, 0.4))
        same("""{"key":{"down":true,"hidUsage":44,"modifiers":1048576}}""", Input.key(44, true, Modifiers.COMMAND))
    }

    @Test fun sources() {
        same("""{"none":{}}""", encodeSource(StreamSource.None))
        same("""{"window":{"_0":1234}}""", encodeSource(StreamSource.Window(1234)))
        same("""{"desktop":{}}""", encodeSource(StreamSource.Desktop))
        val wl = decodePayload<WindowList>(
            """{"active":{"window":{"_0":1}},"launchID":"x","macName":"Mac","windows":[{"appName":"A","bundleID":"b","height":20,"id":1,"title":"T","width":10}]}""".toByteArray(),
        )!!
        assertEquals(StreamSource.Window(1), wl.active)
        assertEquals("T", wl.windows[0].title)
    }

    @Test fun payloads() {
        same("""{"fps":60,"height":500,"localCursor":true,"width":800}""", encodePayload(Viewport(800.0, 500.0, null, true, 60)))
        same("""{"action":"fullScreen","id":7}""", encodePayload(WindowCommand(7, WindowCommand.FULL_SCREEN)))
        same("""{"appVersion":"0.1","build":"1","device":"Pixel","protocol":1}""", encodePayload(Hello("0.1", "1", 1, "Pixel")))
        same(
            """{"device":"Pixel","fps":60,"frameAgeMaxMs":20,"frameAgeMs":9,"rttMaxMs":10,"rttMs":7}""",
            encodePayload(ClientStats(60, 9, 7, "Pixel", 20, 10)),
        )
        same("""{"bitrate":25000000,"token":3}""", encodePayload(HostSettingsChange(token = 3, bitrate = 25_000_000)))
        same(
            """{"method":"code","model":"Pixel 8","name":"Pixel","proof":"abc","v":1}""",
            encodePayload(PairRequest(method = "code", proof = "abc", name = "Pixel", model = "Pixel 8")),
        )
        same("""{}""", encodePayload(Clipboard()))
        same("""{"token":1}""", encodePayload(FetchMenu(token = 1)))
    }

    @Test fun pairingCrypto() {
        val fp = ByteArray(32) { it.toByte() }
        val fpd = ByteArray(32) { (255 - it).toByte() }
        val key = PairingProof.codeKey("482913557208", fp)
        assertEquals("kNcy6gXmGlFu2f2i7+30yecZ8PnUMeYjhA1qhyxcxRQ=", Base64.getEncoder().encodeToString(key))
        assertEquals("RyK_Ze1DtT-2EayBUQFWLHCZzU77gt423nUPvtiJq-c", Base64URL.encode(PairingProof.deviceProof(key, fpd, fp)))
        assertEquals("LFOnfYjg00fbKzP0XW2xBKB93jphw-zqHE786Ri8obE", Base64URL.encode(PairingProof.macProof(key, fp, fpd)))
        assertEquals("000G40R40M30E209", MacID.make(fp))
        assertEquals(8, PairingCode.checkDigit(listOf(4, 8, 2, 9, 1, 3, 5, 5, 7, 2, 0)))
        assertEquals(PairingCode.Check.Ok("482913557208"), PairingCode.check("4829 1355 7208"))
        assertEquals(PairingCode.Check.Typo, PairingCode.check("4829 1355 7209"))
        assertEquals("AQIDBAUGZbeEBvBH", RecognitionTag.make(fp, byteArrayOf(1, 2, 3, 4, 5, 6)))
        assertTrue(RecognitionTag.matches("AQIDBAUGZbeEBvBH", fp))
    }

    @Test fun spki() {
        val pt = byteArrayOf(4) + ByteArray(64) { (it + 1).toByte() }
        assertEquals(
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEAQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyAhIiMkJSYnKCkqKywtLi8wMTIzNDU2Nzg5Ojs8PT4/QA==",
            Base64.getEncoder().encodeToString(SPKI.der(pt)),
        )
        assertEquals("FViyFte_0z13dcRV1gOA06LPYfH9mA6aNIOFWQYr1d4", Base64URL.encode(SPKI.fingerprint(pt)))
    }

    @Test fun identityCertificateRoundTrips() {
        val id = Identity.generate()
        val cert = id.certificate
        // The certificate's own key must be the identity's pin, as the Mac computes it.
        assertEquals(Base64URL.encode(id.fingerprint), Base64URL.encode(SPKI.fingerprint(cert.publicKey)!!))
        cert.verify(id.publicKey)
        // Java's own SPKI encoding of the key is the same DER Sill builds by hand.
        assertEquals(Base64URL.encode(SPKI.der(id.point)), Base64URL.encode(id.publicKey.encoded))
    }

    @Test fun pairLink() {
        val fp = ByteArray(32) { it.toByte() }
        val url = "sill://pair?v=1&m=${MacID.make(fp)}&k=${Base64URL.encode(fp)}&s=${Base64URL.encode(ByteArray(16))}&n=Tom%20%26%20Jerry&p=7455&a=mac.tail.ts.net&a=%5Bfd7a%3A%3A1%5D%3A7456"
        val link = PairLink.parse(url)!!
        assertEquals("Tom & Jerry", link.name)
        assertEquals(7455, link.port)
        assertEquals(ParsedAddress("mac.tail.ts.net", null), link.addresses[0])
        assertEquals(ParsedAddress("fd7a::1", 7456), link.addresses[1])
    }
}
