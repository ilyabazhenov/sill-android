package dev.sash.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.DataInputStream
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class SessionTest {
    /** A plain session against a local socket that reads everything the session writes. */
    private fun session(onEnded: CountDownLatch = CountDownLatch(1), received: MutableList<Message>? = null): Pair<Session, ServerSocket> {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            val peer = server.accept()
            val reader = MessageReader(DataInputStream(peer.getInputStream()))
            try {
                while (true) {
                    val m = reader.read() ?: break
                    received?.let { synchronized(it) { it += m } }
                }
            } catch (_: Exception) {}
        }
        val t = Transport.plain("127.0.0.1", server.localPort)
        val s = Session(t, "test", "1.0", object : SessionListener {
            override fun onEnded(error: Throwable?) = onEnded.countDown()
        })
        s.start()
        return s to server
    }

    @Test fun sendingToAClosedSessionNeverThrows() {
        val (s, server) = session()
        s.close()
        assertFalse(s.isOpen)
        // What a touch does in the instant before the app hears the session ended.
        repeat(100) {
            s.pointerMove(0.5, 0.5)
            s.input(Input.pointer(PointerAction.LEFT_DOWN, 0.5, 0.5))
            s.send(Kind.PING, ByteArray(8))
            s.receiptPing(ByteArray(16))
        }
        server.close()
    }

    @Test fun inputsCountedAcrossThreads() {
        val received = ArrayList<Message>()
        val (s, server) = session(received = received)
        val threads = (0 until 8).map {
            thread {
                repeat(500) { i ->
                    if (i % 2 == 0) s.input(Input.key(4, true, 0)) else s.pointerMove(i / 1000.0, 0.5)
                }
            }
        }
        threads.forEach { it.join() }
        Thread.sleep(100) // the last coalesced move goes out 8 ms after it was made
        val inputs = { synchronized(received) { received.count { it.kind == Kind.INPUT } } }
        val deadline = System.currentTimeMillis() + 3000
        while (inputs() < s.inputsSent && System.currentTimeMillis() < deadline) Thread.sleep(20)
        // Every input the session counted reached the wire, and no increment was lost: the
        // 2,000 key events are all there, plus however many coalesced moves went out.
        assertEquals(s.inputsSent, inputs())
        assertEquals(2000, synchronized(received) { received.count { it.kind == Kind.INPUT && String(it.payload).contains("\"key\"") } })
        s.close()
        server.close()
    }

    @Test fun addressesRoundTripWithIPv6() {
        for ((host, port) in listOf("fd7a:115c:a1e0::1" to 7455, "10.0.2.2" to 54266, "mac.tail1234.ts.net" to 7455)) {
            val text = ParsedAddress(host, port).text
            assertEquals(ParsedAddress(host, port), ParsedAddress.parse(text))
        }
        assertEquals("[fd7a:115c:a1e0::1]:7455", ParsedAddress("fd7a:115c:a1e0::1", 7455).text)
    }

    @Test fun endedOnceWhenThePeerGoes() {
        val ended = CountDownLatch(1)
        val (s, server) = session(onEnded = ended)
        server.close()
        s.transport.socket.shutdownInput()
        assert(ended.await(3, TimeUnit.SECONDS))
        s.close() // a second end is a no-op
    }
}
