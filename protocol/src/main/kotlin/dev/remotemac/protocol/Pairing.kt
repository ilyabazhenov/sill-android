package dev.remotemac.protocol

import java.net.URLDecoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.math.BigInteger

/** Pairing.swift, RemoteIdentity.swift and Remote.swift's crypto, byte for byte. */

object Base64URL {
    fun encode(d: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(d)

    /** Null unless canonical unpadded base64url. */
    fun decode(text: String): ByteArray? {
        if (text.any { !(it.isLetterOrDigit() && it.code < 128) && it != '-' && it != '_' }) return null
        if (text.length % 4 == 1) return null
        val d = try { Base64.getUrlDecoder().decode(text) } catch (e: IllegalArgumentException) { return null }
        return if (encode(d) == text) d else null
    }
}

fun sha256(d: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(d)

fun hmacSha256(key: ByteArray, msg: ByteArray): ByteArray {
    val m = Mac.getInstance("HmacSHA256")
    m.init(SecretKeySpec(key, "HmacSHA256"))
    return m.doFinal(msg)
}

/** The 12-digit typed code: 11 random digits and a Damm check digit. */
object PairingCode {
    private val table = arrayOf(
        intArrayOf(0, 3, 1, 7, 5, 9, 8, 6, 4, 2), intArrayOf(7, 0, 9, 2, 1, 5, 4, 8, 6, 3),
        intArrayOf(4, 2, 0, 6, 8, 7, 1, 3, 5, 9), intArrayOf(1, 7, 5, 0, 9, 8, 3, 4, 2, 6),
        intArrayOf(6, 1, 2, 3, 0, 4, 5, 9, 7, 8), intArrayOf(3, 6, 7, 4, 2, 0, 9, 5, 8, 1),
        intArrayOf(5, 8, 6, 9, 7, 2, 0, 1, 3, 4), intArrayOf(8, 9, 4, 5, 3, 6, 2, 0, 1, 7),
        intArrayOf(9, 4, 3, 8, 6, 1, 7, 2, 0, 5), intArrayOf(2, 5, 8, 1, 4, 3, 6, 7, 9, 0),
    )
    const val LENGTH = 12

    fun checkDigit(digits: List<Int>): Int = digits.fold(0) { acc, d -> table[acc][d] }

    sealed class Check {
        data class Ok(val code: String) : Check()
        data object Length : Check()
        data object Typo : Check()
    }

    fun check(input: String): Check {
        val kept = input.filter { it != ' ' && it != '-' && it != ' ' }
        if (kept.length != LENGTH || kept.any { it !in '0'..'9' }) return Check.Length
        if (checkDigit(kept.map { it - '0' }) != 0) return Check.Typo
        return Check.Ok(kept)
    }

    fun grouped(code: String) = code.chunked(4).joinToString(" ")
}

object PairingProof {
    const val ROUNDS = 600_000
    private const val LABEL = "sill-pair-v1"

    /** PBKDF2-HMAC-SHA256(code, "sill-pair-v1" ‖ fp_mac, 600,000, 32 bytes). */
    fun codeKey(code: String, macFingerprint: ByteArray): ByteArray {
        require(code.length == PairingCode.LENGTH && code.all { it in '0'..'9' })
        val salt = LABEL.toByteArray() + macFingerprint
        val spec = PBEKeySpec(code.toCharArray(), salt, ROUNDS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    fun deviceProof(key: ByteArray, deviceFingerprint: ByteArray, macFingerprint: ByteArray): ByteArray =
        hmacSha256(key, "$LABEL device".toByteArray() + byteArrayOf(0) + deviceFingerprint + macFingerprint)

    fun macProof(key: ByteArray, macFingerprint: ByteArray, deviceFingerprint: ByteArray): ByteArray =
        hmacSha256(key, "$LABEL mac".toByteArray() + byteArrayOf(0) + macFingerprint + deviceFingerprint)

    fun isValidMacProof(proof: ByteArray, key: ByteArray, macFingerprint: ByteArray, deviceFingerprint: ByteArray) =
        MessageDigest.isEqual(proof, macProof(key, macFingerprint, deviceFingerprint))
}

/** The SubjectPublicKeyInfo of a P-256 point and its SHA-256: every pin. */
object SPKI {
    private val ecPublicKey = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x02, 0x01)
    private val prime256v1 = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x03, 0x01, 0x07)

    fun der(point: ByteArray): ByteArray =
        DER.sequence(DER.sequence(DER.oid(ecPublicKey), DER.oid(prime256v1)), DER.bitString(point))

    fun fingerprint(point: ByteArray): ByteArray = sha256(der(point))

    /** The 65-byte uncompressed point of a P-256 public key. */
    fun point(key: java.security.interfaces.ECPublicKey): ByteArray {
        fun fixed(i: BigInteger): ByteArray {
            val b = i.toByteArray()
            return when {
                b.size == 32 -> b
                b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
                else -> ByteArray(32 - b.size) + b
            }
        }
        return byteArrayOf(4) + fixed(key.w.affineX) + fixed(key.w.affineY)
    }

    /** Null for anything but a P-256 key. */
    fun fingerprint(key: java.security.PublicKey): ByteArray? {
        val ec = key as? java.security.interfaces.ECPublicKey ?: return null
        if (ec.params.curve.field.fieldSize != 256) return null
        return fingerprint(point(ec))
    }

    fun publicKey(point: ByteArray): java.security.interfaces.ECPublicKey? {
        if (point.size != 65 || point[0].toInt() != 4) return null
        return try {
            val kf = KeyFactory.getInstance("EC")
            val params = Identity.p256Params()
            val x = BigInteger(1, point.copyOfRange(1, 33))
            val y = BigInteger(1, point.copyOfRange(33, 65))
            kf.generatePublic(ECPublicKeySpec(ECPoint(x, y), params)) as java.security.interfaces.ECPublicKey
        } catch (e: Exception) { null }
    }
}

/** Crockford base32 of the fingerprint's first 10 bytes: 16 characters. */
object MacID {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    fun make(fingerprint: ByteArray): String {
        if (fingerprint.size < 10) return ""
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (i in 0 until 10) {
            buffer = (buffer shl 8) or (fingerprint[i].toInt() and 0xFF); bits += 8
            while (bits >= 5) {
                bits -= 5
                out.append(ALPHABET[(buffer shr bits) and 31])
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        return out.toString()
    }

    fun isWellFormed(t: String) = t.length == 16 && t.all { it in ALPHABET }
}

/** The Bonjour TXT `r`: a saved Mac's recognition hint. */
object RecognitionTag {
    const val TXT_KEY = "r"

    fun matches(tag: String, recognitionKey: ByteArray): Boolean {
        val bytes = Base64URL.decode(tag) ?: return false
        if (bytes.size != 12) return false
        val expected = hmacSha256(recognitionKey, "sill-tag-v1".toByteArray() + bytes.copyOfRange(0, 6)).copyOfRange(0, 6)
        return MessageDigest.isEqual(expected, bytes.copyOfRange(6, 12))
    }

    fun make(recognitionKey: ByteArray, nonce: ByteArray): String {
        val mac = hmacSha256(recognitionKey, "sill-tag-v1".toByteArray() + nonce).copyOfRange(0, 6)
        return Base64URL.encode(nonce + mac)
    }
}

/** The Bonjour TXT `p`: how the home door speaks. */
enum class HomeDoor {
    /** No `p`: plain TCP, no pairing (the CLI without --pairing, development builds). */
    PLAIN,
    /** `p` = anything but "0": TLS, paired devices only. */
    PAIRING_REQUIRED,
    /** `p` = "0": TLS, any device. */
    OPEN;

    companion object {
        fun fromTxt(txt: Map<String, ByteArray?>): HomeDoor {
            val key = txt.keys.firstOrNull { it.equals("p", ignoreCase = true) } ?: return PLAIN
            val v = txt[key]?.toString(Charsets.UTF_8)
            return if (v == "0") OPEN else PAIRING_REQUIRED
        }
    }
}

/** A host:port as the Mac's pairing link and pane give it. */
data class ParsedAddress(val host: String, val port: Int?) {
    val text: String
        get() = when {
            port == null -> host
            host.contains(':') -> "[$host]:$port"
            else -> "$host:$port"
        }

    companion object {
        /** A lenient subset of AddressParser.swift: names, IPv4, IPv6 (bracketed with a port). */
        fun parse(raw: String): ParsedAddress? {
            val t = raw.trim()
            if (t.isEmpty() || t.length > 253 + 6) return null
            if (t.startsWith("[")) {
                val close = t.indexOf(']')
                if (close < 0) return null
                val host = t.substring(1, close)
                val rest = t.substring(close + 1)
                if (rest.isEmpty()) return ParsedAddress(host, null)
                if (!rest.startsWith(":")) return null
                val p = rest.substring(1).toIntOrNull() ?: return null
                return if (p in 1..65535) ParsedAddress(host, p) else null
            }
            val colons = t.count { it == ':' }
            if (colons > 1) return ParsedAddress(t, null) // bare IPv6
            if (colons == 1) {
                val host = t.substringBefore(':')
                val p = t.substringAfter(':').toIntOrNull() ?: return null
                if (host.isEmpty() || p !in 1..65535) return null
                return ParsedAddress(host, p)
            }
            if (t.any { it.isWhitespace() || it == '/' }) return null
            return ParsedAddress(t, null)
        }
    }
}

/** `sill://pair?v=1&m=<macID>&k=<fp>&s=<secret>&n=<name>&p=<port>&a=<address>…` */
data class PairLink(
    val macID: String,
    val fingerprint: ByteArray,
    val secret: ByteArray,
    val name: String,
    val port: Int,
    val addresses: List<ParsedAddress>,
) {
    companion object {
        fun parse(text: String): PairLink? {
            val t = text.trim()
            val lower = t.lowercase()
            if (!lower.startsWith("sill://pair")) return null
            val q = t.substringAfter('?', "")
            if (q.isEmpty()) return null
            val items = q.split('&').filter { it.isNotEmpty() }.map {
                val k = it.substringBefore('=')
                val v = URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
                k to v
            }
            fun single(k: String): String? = items.filter { it.first == k }.singleOrNull()?.second?.takeIf { it.isNotEmpty() }
            if (single("v") != "1") return null
            val m = single("m")?.takeIf { MacID.isWellFormed(it) } ?: return null
            val fp = single("k")?.let { Base64URL.decode(it) }?.takeIf { it.size == 32 } ?: return null
            val s = single("s")?.let { Base64URL.decode(it) }?.takeIf { it.size == 16 } ?: return null
            val p = single("p")?.takeIf { it.length <= 5 && it.all { c -> c in '0'..'9' } }?.toIntOrNull()
                ?.takeIf { it in 1..65535 } ?: return null
            if (m != MacID.make(fp)) return null
            val addrs = items.filter { it.first == "a" }.map { ParsedAddress.parse(it.second) ?: return null }.take(5)
            val name = SafeText.label(items.firstOrNull { it.first == "n" }?.second ?: "").ifEmpty { "Mac" }
            return PairLink(m, fp, s, name, p, addrs)
        }
    }
}

/** Kind 18: who the Mac is, signed with its identity key. */
fun SignedMacInfo.verified(): Pair<MacInfo, ByteArray>? {
    return try {
        val bytes = Base64.getDecoder().decode(info)
        val point = Base64.getDecoder().decode(key)
        val sigBytes = Base64.getDecoder().decode(sig)
        val pub = SPKI.publicKey(point) ?: return null
        val v = Signature.getInstance("SHA256withECDSA")
        v.initVerify(pub)
        v.update(bytes)
        if (!v.verify(sigBytes)) return null
        val decoded = decodePayload<MacInfo>(bytes) ?: return null
        val fp = SPKI.fingerprint(point)
        if (decoded.macID != MacID.make(fp)) return null
        decoded to fp
    } catch (e: Exception) { null }
}

fun SignedMacInfo.unverifiedInfo(): MacInfo? =
    try { decodePayload<MacInfo>(Base64.getDecoder().decode(info)) } catch (e: Exception) { null }

/** SafeText.swift: one clean line. */
object SafeText {
    fun label(text: String, limit: Int = 64): String {
        val out = StringBuilder()
        var pendingSpace = false
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                pendingSpace = out.isNotEmpty(); continue
            }
            when (Character.getType(cp).toByte()) {
                Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED -> continue
            }
            if (pendingSpace) { out.append(' '); pendingSpace = false }
            out.appendCodePoint(cp)
        }
        var s = out.toString()
        if (s.codePointCount(0, s.length) > limit) {
            s = s.substring(0, s.offsetByCodePoints(0, limit)).trimEnd()
        }
        return s
    }
}

internal val secureRandom = SecureRandom()
