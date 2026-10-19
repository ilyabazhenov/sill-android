package dev.sash.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * JSON payloads, shaped exactly as Swift's JSONEncoder writes Sill's Codable types: optional fields
 * left out when nil, enums with associated values as `{"case":{"_0":…,"label":…}}`.
 * Golden vectors from the Swift code are in the tests.
 */
val WireJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
}

inline fun <reified T> encodePayload(value: T): ByteArray = WireJson.encodeToString(value).toByteArray(Charsets.UTF_8)
inline fun <reified T> decodePayload(bytes: ByteArray): T? =
    try { WireJson.decodeFromString<T>(String(bytes, Charsets.UTF_8)) } catch (e: Exception) { null }

// ---------------------------------------------------------------------------------------------
// The catalog (Switcher.swift)

sealed class StreamSource {
    data object None : StreamSource()
    data object Desktop : StreamSource()
    data class Window(val id: Long) : StreamSource()

    object Serializer : KSerializer<StreamSource> {
        override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor
        override fun serialize(encoder: Encoder, value: StreamSource) {
            val e = encoder as JsonEncoder
            e.encodeJsonElement(
                when (value) {
                    None -> buildJsonObject { put("none", JsonObject(emptyMap())) }
                    Desktop -> buildJsonObject { put("desktop", JsonObject(emptyMap())) }
                    is Window -> buildJsonObject { put("window", buildJsonObject { put("_0", value.id) }) }
                },
            )
        }

        override fun deserialize(decoder: Decoder): StreamSource {
            val o = (decoder as JsonDecoder).decodeJsonElement().jsonObject
            return when {
                "window" in o -> Window(o["window"]!!.jsonObject["_0"]!!.jsonPrimitive.long)
                "desktop" in o -> Desktop
                else -> None
            }
        }
    }
}

@Serializable
data class WindowInfo(
    val id: Long,
    val title: String = "",
    val appName: String = "",
    val bundleID: String = "",
    val width: Int = 0,
    val height: Int = 0,
)

@Serializable
data class AppInfo(val name: String, val bundleID: String)

@Serializable
data class WindowList(
    val macName: String = "Mac",
    val windows: List<WindowInfo> = emptyList(),
    @Serializable(with = StreamSource.Serializer::class) val active: StreamSource = StreamSource.None,
    val launchID: String? = null,
    val hostVersion: String? = null,
    val protocol: Int? = null,
    val gestures: Int? = null,
    val clipboard: Int? = null,
)

/** Kind 6's payload is the bare StreamSource. */
fun encodeSource(source: StreamSource): ByteArray =
    WireJson.encodeToString(StreamSource.Serializer, source).toByteArray(Charsets.UTF_8)

@Serializable
data class LaunchApp(val bundleID: String)

@Serializable
data class WindowCommand(val id: Long, val action: String) {
    companion object {
        const val CLOSE = "close"
        const val MINIMIZE = "minimize"
        const val FULL_SCREEN = "fullScreen"
    }
}

// ---------------------------------------------------------------------------------------------
// Input (Input.swift). Positions are fractions of the streamed frame.

object PointerAction {
    const val MOVE = "move"
    const val LEFT_DOWN = "leftDown"
    const val LEFT_UP = "leftUp"
    const val RIGHT_DOWN = "rightDown"
    const val RIGHT_UP = "rightUp"
}

object ScrollPhase {
    const val BEGAN = "began"
    const val ENDED = "ended"
    const val MOMENTUM_BEGAN = "momentumBegan"
    const val MOMENTUM_ENDED = "momentumEnded"
}

/** Modifier bits as CGEventFlags / UIKeyModifierFlags carry them. */
object Modifiers {
    const val SHIFT = 1L shl 17
    const val CONTROL = 1L shl 18
    const val OPTION = 1L shl 19
    const val COMMAND = 1L shl 20
}

object Input {
    fun pointer(action: String, x: Double, y: Double): ByteArray = obj(
        buildJsonObject {
            put("pointer", buildJsonObject { put("_0", action); put("x", x); put("y", y) })
        },
    )

    fun scroll(x: Double, y: Double, dx: Double, dy: Double): ByteArray = obj(
        buildJsonObject {
            put("scroll", buildJsonObject { put("x", x); put("y", y); put("dx", dx); put("dy", dy) })
        },
    )

    fun scrollGesture(phase: String, x: Double, y: Double): ByteArray = obj(
        buildJsonObject {
            put("scrollGesture", buildJsonObject { put("_0", phase); put("x", x); put("y", y) })
        },
    )

    /** "\n", "\t" and "\b" arrive at the Mac as Return, Tab and Delete. */
    fun text(text: String): ByteArray = obj(buildJsonObject { put("text", buildJsonObject { put("_0", text) }) })

    fun key(hidUsage: Int, down: Boolean, modifiers: Long): ByteArray = obj(
        buildJsonObject {
            put("key", buildJsonObject { put("hidUsage", hidUsage); put("down", down); put("modifiers", modifiers) })
        },
    )

    private fun obj(o: JsonObject) = o.toString().toByteArray(Charsets.UTF_8)
}

@Serializable
data class Viewport(
    val width: Double,
    val height: Double,
    val scale: Double? = null,
    val localCursor: Boolean? = true,
    val fps: Int? = null,
)

@Serializable
data class ClientStats(
    val fps: Int,
    val frameAgeMs: Int,
    val rttMs: Int,
    val device: String,
    val frameAgeMaxMs: Int? = null,
    val rttMaxMs: Int? = null,
)

@Serializable
data class Hello(
    val appVersion: String? = null,
    val build: String? = null,
    val protocol: Int? = null,
    val device: String? = null,
)

// ---------------------------------------------------------------------------------------------
// Host settings (HostSettings.swift)

@Serializable
data class StreamSettings(
    val maxFPS: Int = 60,
    val bitrate: Int = 15_000_000,
    val captureScale: Double = 2.0,
    val prioritizeSpeed: Boolean = false,
    val virtualDisplay: Boolean = false,
    val directWireless: Boolean? = null,
)

@Serializable
data class RunningStream(
    val width: Int,
    val height: Int,
    val fps: Int,
    val mbps: Int,
    val onVirtualDisplay: Boolean = false,
)

@Serializable
data class AwayQuality(
    val homeBitrate: Int,
    val homeCaptureScale: Double,
    val awayBitrate: Int,
    val awayCaptureScale: Double,
    val thisConnectionAway: Boolean,
    val awayRunning: Boolean,
)

@Serializable
data class LinkReport(
    val state: String,
    val withheldPerSecond: Int = 0,
    val bitrate: Int = 0,
    val carriedKbps: Int? = null,
    val suggestedBitrate: Int? = null,
    val suggestedCaptureScale: Double? = null,
) {
    val isBehind get() = state == "behind"
}

@Serializable
data class HostSettingsState(
    val settings: StreamSettings,
    val persistent: Boolean = false,
    val virtualDisplayAvailable: Boolean = false,
    val virtualDisplayNote: String? = null,
    val softwareEncoder: Boolean = false,
    val stream: RunningStream? = null,
    val answering: Int? = null,
    val away: AwayQuality? = null,
    val link: LinkReport? = null,
)

@Serializable
data class HostSettingsChange(
    val token: Int? = null,
    val maxFPS: Int? = null,
    val bitrate: Int? = null,
    val captureScale: Double? = null,
    val prioritizeSpeed: Boolean? = null,
    val virtualDisplay: Boolean? = null,
    val directWireless: Boolean? = null,
)

enum class QualityPreset(val bitrate: Int, val title: String) {
    LOW(4_000_000, "Low"),
    EFFICIENT(8_000_000, "Efficient"),
    BALANCED(15_000_000, "Balanced"),
    HIGH(25_000_000, "High"),
    PRO(40_000_000, "Pro"),
    ULTRA(80_000_000, "Ultra"),
    EXTREME(150_000_000, "Extreme");

    companion object {
        fun forBitrate(b: Int) = entries.firstOrNull { it.bitrate == b }
        fun name(b: Int) = forBitrate(b)?.title ?: "${b / 1_000_000} Mbps"
    }
}

// ---------------------------------------------------------------------------------------------
// Remote access and pairing (Remote.swift)

@Serializable
data class MacAddress(val host: String, val port: Int? = null, val kind: String = "", val via: String = "")

@Serializable
data class MacInfo(
    val v: Int = 1,
    val macID: String,
    val name: String,
    val issuedAt: Double,
    val remoteAccess: Boolean,
    val remotePort: Int,
    val internet: Boolean = false,
    val addresses: List<MacAddress> = emptyList(),
)

@Serializable
data class SignedMacInfo(val info: String, val key: String, val sig: String)

@Serializable
data class PairRequest(
    val v: Int = 1,
    val method: String,
    val proof: String,
    val name: String,
    val model: String? = null,
    val cable: Boolean? = null,
) {
    companion object {
        const val QR = "qr"
        const val CODE = "code"
        const val ASK = "ask"
        const val CANCEL = "cancel"
    }
}

@Serializable
data class PairResult(
    val ok: Boolean,
    val proof: String? = null,
    val macID: String? = null,
    val name: String? = null,
    val recognitionKey: String? = null,
    val reason: String? = null,
    val triesLeft: Int? = null,
    val retryAfter: Double? = null,
    val method: String? = null,
    val message: String? = null,
)

@Serializable
data class Goodbye(
    val reason: String,
    val message: String? = null,
    val minimumVersion: String? = null,
    val reconnect: Boolean? = null,
)

// ---------------------------------------------------------------------------------------------
// The Mac's menus, pointer, gestures and clipboard

@Serializable
data class MacMenuItem(
    val id: String? = null,
    val title: String? = null,
    val separator: Boolean? = null,
    val enabled: Boolean? = null,
    val mark: String? = null,
    val key: String? = null,
    val submenu: Boolean? = null,
)

@Serializable
data class MacMenu(
    val version: Int? = null,
    val app: String? = null,
    val bundleID: String? = null,
    val menus: List<MacMenuItem>? = null,
    val answering: Int? = null,
    val menu: String? = null,
    val items: List<MacMenuItem>? = null,
    val more: Int? = null,
    val pressed: Boolean? = null,
    val stale: Boolean? = null,
    val note: String? = null,
)

@Serializable
data class FetchMenu(val version: Int? = null, val id: String? = null, val title: String? = null, val token: Int? = null)

@Serializable
data class PressMenuItem(val version: Int? = null, val id: String? = null, val title: String? = null, val token: Int? = null)

@Serializable
data class MacPointer(val x: Double? = null, val y: Double? = null, val inside: Boolean? = null, val seen: Int? = null)

@Serializable
data class TrackpadGesture(val gesture: String, val fingers: Int? = null) {
    companion object {
        const val SWIPE_UP = "swipeUp"
        const val SWIPE_DOWN = "swipeDown"
        const val SWIPE_LEFT = "swipeLeft"
        const val SWIPE_RIGHT = "swipeRight"
        const val PINCH = "pinch"
        const val SPREAD = "spread"
    }
}

@Serializable
data class Clipboard(val text: String? = null, val stop: Boolean? = null) {
    companion object {
        const val MAX_TEXT_BYTES = 256 * 1024
    }
}
