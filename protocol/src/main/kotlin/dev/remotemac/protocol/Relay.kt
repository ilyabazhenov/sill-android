package dev.remotemac.protocol

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLDecoder

/**
 * The relay for a Mac away from home (docs/relay.md): the Mac's helper keeps a connection to it,
 * the phone asks it for the Mac by id and gets a socket that leads to Sill's remote door. Sill's
 * own TLS runs inside, so the relay only ever sees ciphertext.
 */
object Relay {
    private val HELLO = byteArrayOf('R'.code.toByte(), 'M'.code.toByte(), 'R'.code.toByte(), '1'.code.toByte(), 'C'.code.toByte())

    /**
     * A raw socket to the Mac's remote door through the relay, ready for Sill's TLS handshake.
     * [access] is the key from the helper's QR code: without it the helper never opens Sill.
     */
    fun dial(host: String, port: Int, macID: ByteArray, access: ByteArray, timeoutMs: Int = Transport.DIAL_TIMEOUT_MS): Socket {
        require(macID.size == 16 && access.size == 16)
        val s = Socket()
        try {
            Transport.tune(s)
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.getOutputStream().apply { write(HELLO + macID + access); flush() }
            // The relay waits up to 10 s for the Mac to open its side.
            s.soTimeout = timeoutMs + 5_000
            val status = s.getInputStream().read()
            s.soTimeout = 0
            if (status != 0) throw RelayRefused(status)
            return s
        } catch (e: Exception) {
            try { s.close() } catch (_: Exception) {}
            throw e
        }
    }

    /**
     * What a failed TLS handshake through the relay means: the helper ends it itself with an
     * internal_error alert when it can't reach Sill, before Sill ever sees the phone.
     */
    fun explain(e: Throwable): Throwable {
        if (e is PinRefused) return e
        var c: Throwable? = e
        while (c != null) {
            if (c is javax.net.ssl.SSLException && c.message?.contains("internal_error", ignoreCase = true) == true) return RelayRefused(4)
            c = c.cause
        }
        return e
    }
}

class RelayRefused(val status: Int) : IOException(
    when (status) {
        -1 -> "the relay closed the connection"
        1 -> "the Mac isn’t on the relay: is rmhelper running and the Mac awake?"
        2 -> "the Mac has too many sessions through the relay"
        3 -> "the Mac didn’t answer through the relay"
        4 -> "the Mac’s helper couldn’t reach Sill: turn on Remote Access in Sill"
        5 -> "the relay code is out of date: scan the new one from rmhelper link"
        else -> "the relay refused (status $status)"
    },
)

/**
 * `remotemac://relay?r=<host:port>&i=<Mac id, 32 hex>&s=<access key>&k=<Sill key fingerprint>&n=<name>`,
 * from `rmhelper link`.
 */
data class RelayLink(
    val relay: ParsedAddress,
    val macID: String,
    /** base64url of the 16-byte access key. */
    val access: String,
    val fingerprint: ByteArray,
    val name: String,
) {
    val idBytes: ByteArray get() = idBytes(macID)

    companion object {
        fun idBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        fun parse(text: String): RelayLink? {
            val t = text.trim()
            if (!t.lowercase().startsWith("remotemac://relay")) return null
            val q = t.substringAfter('?', "")
            val items = q.split('&').filter { it.isNotEmpty() }.map {
                it.substringBefore('=') to try { URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") } catch (_: Exception) { return null }
            }
            fun single(k: String): String? = items.filter { it.first == k }.singleOrNull()?.second?.takeIf { it.isNotEmpty() }
            val relay = single("r")?.let { ParsedAddress.parse(it) }?.takeIf { it.port != null } ?: return null
            val id = single("i")?.lowercase()?.takeIf { it.length == 32 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } } ?: return null
            val access = single("s")?.takeIf { Base64URL.decode(it)?.size == 16 } ?: return null
            val fp = single("k")?.let { Base64URL.decode(it) }?.takeIf { it.size == 32 } ?: return null
            val name = SafeText.label(single("n") ?: "").ifEmpty { "Mac" }
            return RelayLink(relay, id, access, fp, name)
        }
    }
}
