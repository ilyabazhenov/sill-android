package dev.remotemac.protocol

import java.io.IOException

/**
 * One pairing exchange (`sill-pair/1`): exactly one kind 19 from the device, one kind 20 back, then
 * the Mac closes. Each side takes the other's fingerprint from the TLS session, never from a
 * message, so a relay in the middle makes the proofs bind the wrong key and fail.
 */
object PairingClient {
    sealed class Outcome {
        /** Paired. Save [macFingerprint] as the Mac's pin. */
        data class Paired(
            val macFingerprint: ByteArray,
            val macID: String,
            val name: String,
            val recognitionKey: ByteArray?,
            val viaCable: Boolean,
        ) : Outcome()

        /** Not paired: one of PairResult's reasons ("code", "closed", "expired", "shown"…). */
        data class Refused(
            val reason: String,
            val triesLeft: Int?,
            val retryAfter: Double?,
            val message: String?,
            /** The key the Mac presented: an ask's "shown" pins the code that follows to it. */
            val macFingerprint: ByteArray? = null,
        ) : Outcome()

        /** The Mac's answer did not prove it knew the secret: not the Mac the code is from. */
        data object BadProof : Outcome()
    }

    sealed class Secret {
        /** The QR code's or link's 16-byte secret. */
        class Link(val secret: ByteArray) : Secret()
        /** The 12 digits typed from the Mac's window. */
        class Code(val code: String) : Secret()
    }

    /** A proof for the Mac's pairing window. [pin]: the link's fingerprint, or null for a typed code. */
    fun pair(
        host: String,
        port: Int,
        identity: Identity,
        secret: Secret,
        pin: ByteArray?,
        deviceName: String,
        model: String?,
    ): Outcome {
        // The Mac wants the request within 10 s of the connection, and the code's key takes
        // 600,000 rounds: derive it first whenever the Mac's key is already known (a pinned dial,
        // as after an ask), so a slow phone never spends that time inside the Mac's window.
        val early = if (secret is Secret.Code && pin != null) PairingProof.codeKey(secret.code, pin) else null
        val t = Transport.tls(host, port, identity, Alpn.PAIRING, pin)
        try {
            val macFp = t.peerFingerprint ?: throw IOException("no key from the Mac")
            val key = when (secret) {
                is Secret.Link -> secret.secret
                is Secret.Code -> early ?: PairingProof.codeKey(secret.code, macFp)
            }
            val proof = PairingProof.deviceProof(key, identity.fingerprint, macFp)
            val method = if (secret is Secret.Link) PairRequest.QR else PairRequest.CODE
            val result = exchange(t, PairRequest(method = method, proof = Base64URL.encode(proof), name = deviceName, model = model))
            if (!result.ok) return Outcome.Refused(result.reason ?: "closed", result.triesLeft, result.retryAfter, result.message)
            val got = result.proof?.let { Base64URL.decode(it) } ?: return Outcome.BadProof
            if (!PairingProof.isValidMacProof(got, key, macFp, identity.fingerprint)) return Outcome.BadProof
            return paired(result, macFp, false)
        } finally {
            t.close()
        }
    }

    /**
     * The home door's "pair me" (kind 19 `ask`): over the USB cable the Mac may pair at once (an
     * ok without a proof, `method` "cable"); otherwise it answers "shown" and puts up its code.
     */
    fun ask(host: String, port: Int, identity: Identity, deviceName: String, model: String?, cable: Boolean): Outcome {
        val t = Transport.tls(host, port, identity, Alpn.PAIRING, null)
        try {
            val macFp = t.peerFingerprint ?: throw IOException("no key from the Mac")
            val result = exchange(
                t,
                PairRequest(method = PairRequest.ASK, proof = "", name = deviceName, model = model, cable = if (cable) true else null),
            )
            if (!result.ok) return Outcome.Refused(result.reason ?: "closed", result.triesLeft, result.retryAfter, result.message, macFp)
            // An ok without a proof is only ever the cable's answer to an ask that claimed it.
            if (!cable || result.method != "cable") return Outcome.BadProof
            return paired(result, macFp, true)
        } finally {
            t.close()
        }
    }

    /** "I no longer need the code your window shows for me." Always answered "closed". */
    fun cancel(host: String, port: Int, identity: Identity, deviceName: String) {
        try {
            val t = Transport.tls(host, port, identity, Alpn.PAIRING, null)
            try { exchange(t, PairRequest(method = PairRequest.CANCEL, proof = "", name = deviceName)) } finally { t.close() }
        } catch (_: Exception) {}
    }

    private fun paired(r: PairResult, macFp: ByteArray, cable: Boolean): Outcome {
        val id = r.macID ?: MacID.make(macFp)
        if (id != MacID.make(macFp)) return Outcome.BadProof
        return Outcome.Paired(
            macFingerprint = macFp,
            macID = id,
            name = SafeText.label(r.name ?: "").ifEmpty { "Mac" },
            recognitionKey = r.recognitionKey?.let { Base64URL.decode(it) },
            viaCable = cable,
        )
    }

    private fun exchange(t: Transport, request: PairRequest): PairResult {
        t.socket.soTimeout = 15_000
        t.send(Message.of(Kind.PAIR_REQUEST, encodePayload(request)))
        val reader = MessageReader(t.input)
        while (true) {
            val m = reader.read() ?: throw IOException("the Mac closed the pairing connection")
            if (m.kind == Kind.PAIR_RESULT) {
                return decodePayload<PairResult>(m.payload) ?: throw IOException("unreadable pairing answer")
            }
        }
    }
}
