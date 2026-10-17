package dev.remotemac.app.data

import android.content.Context
import dev.remotemac.protocol.Base64URL
import dev.remotemac.protocol.MacAddress
import dev.remotemac.protocol.MacInfo
import dev.remotemac.protocol.RecognitionTag
import dev.remotemac.protocol.RelayLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A Mac this device paired with: its pin, and how to find it at home and from afar. */
@Serializable
data class SavedMac(
    val macID: String,
    /** base64url of SHA-256(SPKI): the Mac's pin. */
    val fingerprint: String,
    val name: String,
    /** base64url of the 32-byte key that resolves the Mac's Bonjour tag. */
    val recognitionKey: String? = null,
    val remotePort: Int = 7455,
    /** From the Mac's signed kind 18, in its dial order. */
    val addresses: List<MacAddress> = emptyList(),
    /** Addresses the user typed when pairing from afar. */
    val typedAddresses: List<String> = emptyList(),
    val infoIssuedAt: Double = 0.0,
    val bonjourName: String? = null,
    /** host:port that last connected from afar. */
    val lastWorked: String? = null,
    val pairedAt: Long = System.currentTimeMillis(),
    val lastConnectedAt: Long? = null,
    /** The Mac removed this device ("removed" goodbye): it must pair again. */
    val revoked: Boolean = false,
    /** host:port of the relay its helper is on (docs/relay.md), from `rmhelper link`. */
    val relay: String? = null,
    /** The Mac's id on that relay: 32 hex characters. */
    val relayID: String? = null,
    /** base64url of the access key the Mac's helper asks for. */
    val relayAccess: String? = null,
) {
    val pin: ByteArray get() = Base64URL.decode(fingerprint)!!

    fun recognizes(tag: String?): Boolean {
        val key = recognitionKey?.let { Base64URL.decode(it) } ?: return false
        return tag != null && RecognitionTag.matches(tag, key)
    }
}

class SavedMacs(context: Context) {
    private val prefs = context.getSharedPreferences("saved_macs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _all = MutableStateFlow(load())
    val all: StateFlow<List<SavedMac>> = _all

    private fun load(): List<SavedMac> = try {
        json.decodeFromString(ListSerializer(SavedMac.serializer()), prefs.getString("list", "[]")!!)
    } catch (e: Exception) {
        emptyList()
    }

    @Synchronized
    private fun write(list: List<SavedMac>) {
        val capped = list.sortedByDescending { it.lastConnectedAt ?: it.pairedAt }.take(16)
        prefs.edit().putString("list", json.encodeToString(ListSerializer(SavedMac.serializer()), capped)).apply()
        _all.value = capped
    }

    fun byID(macID: String) = _all.value.firstOrNull { it.macID == macID }

    fun byTag(tag: String?) = _all.value.firstOrNull { !it.revoked && it.recognizes(tag) }

    fun byFingerprint(fp: ByteArray) = _all.value.firstOrNull { it.fingerprint == Base64URL.encode(fp) }

    /** A `remotemac://relay` link: the paired Mac with that key now also goes through the relay. */
    fun learnRelay(link: RelayLink): SavedMac? {
        val mac = byFingerprint(link.fingerprint)?.takeIf { !it.revoked } ?: return null
        update(mac.macID) { it.copy(relay = link.relay.text, relayID = link.macID, relayAccess = link.access) }
        return byID(mac.macID)
    }

    /** A new pairing replaces any record of the same Mac. */
    @Synchronized
    fun save(mac: SavedMac) = write(_all.value.filter { it.macID != mac.macID } + mac)

    @Synchronized
    fun update(macID: String, change: (SavedMac) -> SavedMac) {
        val cur = byID(macID) ?: return
        write(_all.value.map { if (it.macID == macID) change(cur) else it })
    }

    @Synchronized
    fun forget(macID: String) = write(_all.value.filter { it.macID != macID })

    /** A kind 18 signed by this Mac's own key: newer addresses; an empty list keeps the old ones. */
    fun learn(macID: String, info: MacInfo) = update(macID) {
        if (info.issuedAt <= it.infoIssuedAt) it
        else it.copy(
            name = info.name,
            remotePort = info.remotePort,
            addresses = if (info.addresses.isEmpty()) it.addresses else info.addresses,
            infoIssuedAt = info.issuedAt,
        )
    }
}
