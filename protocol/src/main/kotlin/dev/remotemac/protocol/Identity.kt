package dev.remotemac.protocol

import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/** Just enough DER for one certificate and one SPKI (RemoteIdentity.swift's DER). */
internal object DER {
    fun length(n: Int): ByteArray {
        if (n < 0x80) return byteArrayOf(n.toByte())
        val bytes = ArrayList<Byte>()
        var v = n
        while (v > 0) { bytes.add(0, (v and 0xFF).toByte()); v = v shr 8 }
        return byteArrayOf((0x80 or bytes.size).toByte()) + bytes.toByteArray()
    }

    fun tlv(tag: Int, content: ByteArray) = byteArrayOf(tag.toByte()) + length(content.size) + content
    fun sequence(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })
    fun set(vararg parts: ByteArray) = tlv(0x31, parts.fold(ByteArray(0)) { a, b -> a + b })
    fun oid(bytes: ByteArray) = tlv(0x06, bytes)
    fun bitString(bytes: ByteArray) = tlv(0x03, byteArrayOf(0) + bytes)

    fun integer(raw: ByteArray): ByteArray {
        var b = if (raw.isEmpty()) byteArrayOf(0) else raw
        while (b.size > 1 && b[0].toInt() == 0 && (b[1].toInt() and 0x80) == 0) b = b.copyOfRange(1, b.size)
        if ((b[0].toInt() and 0x80) != 0) b = byteArrayOf(0) + b
        return tlv(0x02, b)
    }
}

/**
 * A device identity: one P-256 key and a self-signed certificate made from it. Trust is the key,
 * pinned as the SHA-256 of its SubjectPublicKeyInfo; nothing in the certificate means anything.
 */
class Identity(val privateKey: PrivateKey, val publicKey: ECPublicKey) {
    val point: ByteArray = SPKI.point(publicKey)
    val fingerprint: ByteArray = SPKI.fingerprint(point)
    val certificate: X509Certificate by lazy { makeCertificate() }

    /** The private key as PKCS#8, for a store that keeps it (encrypted on Android). */
    fun exportPrivate(): ByteArray = privateKey.encoded

    private fun makeCertificate(): X509Certificate {
        val ecdsaWithSHA256 = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x02)
        val commonName = byteArrayOf(0x55, 0x04, 0x03)
        val cn = ByteArray(8).also { secureRandom.nextBytes(it) }
        val serial = ByteArray(16).also { secureRandom.nextBytes(it) }
        serial[0] = ((serial[0].toInt() and 0x7F) or 0x01).toByte()
        val hex = cn.joinToString("") { "%02x".format(it) }
        val name = DER.sequence(DER.set(DER.sequence(DER.oid(commonName), DER.tlv(0x0C, hex.toByteArray()))))
        val algorithm = DER.sequence(DER.oid(ecdsaWithSHA256))
        val tbs = DER.sequence(
            DER.tlv(0xA0, DER.integer(byteArrayOf(2))),
            DER.integer(serial),
            algorithm,
            name,
            DER.sequence(DER.tlv(0x17, "260101000000Z".toByteArray()), DER.tlv(0x18, "99991231235959Z".toByteArray())),
            name,
            SPKI.der(point),
        )
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(privateKey)
        s.update(tbs)
        val der = DER.sequence(tbs, algorithm, DER.bitString(s.sign()))
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    companion object {
        fun generate(): Identity {
            val g = KeyPairGenerator.getInstance("EC")
            g.initialize(ECGenParameterSpec("secp256r1"), secureRandom)
            return from(g.generateKeyPair())
        }

        fun from(kp: KeyPair) = Identity(kp.private, kp.public as ECPublicKey)

        /** From a stored PKCS#8 private key and its X.509 SPKI public key. */
        fun restore(pkcs8: ByteArray, spki: ByteArray): Identity {
            val kf = KeyFactory.getInstance("EC")
            val priv = kf.generatePrivate(PKCS8EncodedKeySpec(pkcs8))
            val pub = kf.generatePublic(X509EncodedKeySpec(spki)) as ECPublicKey
            return Identity(priv, pub)
        }

        private var cachedParams: ECParameterSpec? = null

        fun p256Params(): ECParameterSpec {
            cachedParams?.let { return it }
            val g = KeyPairGenerator.getInstance("EC")
            g.initialize(ECGenParameterSpec("secp256r1"))
            val p = (g.generateKeyPair().public as ECPublicKey).params
            cachedParams = p
            return p
        }
    }
}
