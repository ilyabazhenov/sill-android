package dev.remotemac.protocol

import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * The connection to a Mac (RemoteTLS.swift): TLS 1.3 only, both ends present a self-signed P-256
 * certificate, each pins the other's key. `sill/1` is a session, `sill-pair/1` a pairing exchange.
 * A plain door (the CLI without --pairing) is bare TCP.
 */
object Alpn {
    const val SESSION = "sill/1"
    const val PAIRING = "sill-pair/1"
    const val SERVER_NAME = "sill"
}

class PinRefused(val presented: ByteArray?) : CertificateException("the Mac presented another key")

class Transport private constructor(
    val socket: Socket,
    /** The Mac's key as this connection saw it: SHA-256 of its SPKI. Null on a plain door. */
    val peerFingerprint: ByteArray?,
    val alpn: String?,
) {
    val input: DataInputStream = DataInputStream(socket.getInputStream())
    private val output: OutputStream = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
    private val writeLock = Any()

    val isTls get() = socket is SSLSocket

    fun send(m: Message) {
        val bytes = m.serialized()
        synchronized(writeLock) {
            output.write(bytes)
            output.flush()
        }
    }

    fun close() {
        try { socket.close() } catch (_: Exception) {}
    }

    companion object {
        const val DIAL_TIMEOUT_MS = 10_000

        fun plain(host: String, port: Int, timeoutMs: Int = DIAL_TIMEOUT_MS): Transport {
            val s = Socket()
            tune(s)
            s.connect(InetSocketAddress(host, port), timeoutMs)
            return Transport(s, null, null)
        }

        /**
         * A TLS dial. [pin] null accepts any key (an open door, or a pairing whose proof binds the
         * key instead); otherwise anything but that key fails the handshake with [PinRefused].
         * [over] is an already-connected socket to run TLS on (the relay's); null dials [host].
         */
        fun tls(
            host: String,
            port: Int,
            identity: Identity,
            alpn: String,
            pin: ByteArray?,
            timeoutMs: Int = DIAL_TIMEOUT_MS,
            over: Socket? = null,
        ): Transport {
            var seen: ByteArray? = null
            val trust = object : X509ExtendedTrustManager() {
                fun check(chain: Array<out X509Certificate>?) {
                    val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate")
                    val fp = SPKI.fingerprint(leaf.publicKey) ?: throw CertificateException("not a P-256 key")
                    seen = fp
                    if (pin != null && !pin.contentEquals(fp)) throw PinRefused(fp)
                }
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException()
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = throw CertificateException()
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = throw CertificateException()
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val keys = object : X509ExtendedKeyManager() {
                override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf("device")
                override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = "device"
                override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = "device"
                override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = null
                override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = null
                override fun getCertificateChain(alias: String?) = arrayOf(identity.certificate)
                override fun getPrivateKey(alias: String?): PrivateKey = identity.privateKey
            }
            val ctx = SSLContext.getInstance("TLSv1.3")
            ctx.init(arrayOf(keys), arrayOf(trust), secureRandom)
            // No session cache to resume from: a resumed session would skip the certificate.
            ctx.clientSessionContext?.sessionCacheSize = 1
            val raw = over ?: Socket().also {
                tune(it)
                it.connect(InetSocketAddress(host, port), timeoutMs)
            }
            val s = ctx.socketFactory.createSocket(raw, Alpn.SERVER_NAME, port, true) as SSLSocket
            s.enabledProtocols = arrayOf("TLSv1.3")
            val params = s.sslParameters
            params.applicationProtocols = arrayOf(alpn)
            params.serverNames = listOf(SNIHostName(Alpn.SERVER_NAME))
            params.endpointIdentificationAlgorithm = null
            s.sslParameters = params
            s.soTimeout = timeoutMs
            try {
                s.startHandshake()
            } catch (e: Exception) {
                try { s.close() } catch (_: Exception) {}
                // Unwrap our own pin refusal, which the TLS stack wraps.
                var c: Throwable? = e
                while (c != null) { if (c is PinRefused) throw c; c = c.cause }
                throw e
            }
            s.soTimeout = 0
            return Transport(s, seen, s.applicationProtocol)
        }

        internal fun tune(s: Socket) {
            s.tcpNoDelay = true
            s.keepAlive = true
            // IPTOS_LOWDELAY | interactive video, as Network.framework's .interactiveVideo asks.
            try { s.trafficClass = 0x10 or 0xA0 } catch (_: Exception) {}
        }
    }
}

internal fun InputStream.readExactly(n: Int): ByteArray {
    val b = ByteArray(n)
    DataInputStream(this).readFully(b)
    return b
}
