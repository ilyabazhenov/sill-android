package dev.sash.app.stream

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.sash.app.R
import dev.sash.app.data.IdentityStore
import dev.sash.app.data.SavedMac
import dev.sash.app.data.SavedMacs
import dev.sash.app.net.Discovery
import dev.sash.app.net.FoundMac
import dev.sash.protocol.Alpn
import dev.sash.protocol.AppInfo
import dev.sash.protocol.Base64URL
import dev.sash.protocol.Blobs
import dev.sash.protocol.Clipboard
import dev.sash.protocol.CursorShape
import dev.sash.protocol.FetchMenu
import dev.sash.protocol.Goodbye
import dev.sash.protocol.HomeDoor
import dev.sash.protocol.HostSettingsChange
import dev.sash.protocol.HostSettingsState
import dev.sash.protocol.QualityPreset
import dev.sash.protocol.Input
import dev.sash.protocol.MacMenu
import dev.sash.protocol.MacMenuItem
import dev.sash.protocol.MacPointer
import dev.sash.protocol.PairLink
import dev.sash.protocol.PairingClient
import dev.sash.protocol.ParameterSets
import dev.sash.protocol.ParsedAddress
import dev.sash.protocol.PinRefused
import dev.sash.protocol.PressMenuItem
import dev.sash.protocol.Session
import dev.sash.protocol.SessionListener
import dev.sash.protocol.SignedMacInfo
import dev.sash.protocol.SillParity
import dev.sash.protocol.PointerAction
import dev.sash.protocol.StreamSource
import dev.sash.protocol.TrackpadGesture
import dev.sash.protocol.Transport
import dev.sash.protocol.Viewport
import dev.sash.protocol.WindowCommand
import dev.sash.protocol.WindowInfo
import dev.sash.protocol.WindowList
import dev.sash.protocol.verified
import kotlin.math.hypot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/** Where a session goes: a Bonjour row at home, or a saved Mac dialed from afar. */
sealed class Target {
    abstract val name: String

    data class Row(val row: FoundMac, val saved: SavedMac?) : Target() {
        override val name get() = saved?.name ?: row.serviceName
    }

    data class Remote(val mac: SavedMac) : Target() {
        override val name get() = mac.name
    }

    data class Address(val host: String, val port: Int, val plain: Boolean) : Target() {
        override val name get() = host
    }
}

enum class Phase { IDLE, DIALING, CONNECTED, RECONNECTING }

/** Where a tall screen's bar sits: above the picture, between it and the trackpad, or under the trackpad. */
enum class BarPosition { TOP, MIDDLE, BOTTOM }

/** What the connect screen shows while a pairing is under way. */
data class PairingPrompt(
    val macName: String,
    /** The Mac's window shows a code: type it, or scan its QR code. */
    val codeShown: Boolean,
    val busy: Boolean = false,
    val error: String? = null,
    internal val host: String = "",
    internal val port: Int = 0,
    internal val askedKey: ByteArray? = null,
    internal val row: FoundMac? = null,
)

data class MenuState(
    val version: Int? = null,
    val app: String? = null,
    val top: List<MacMenuItem> = emptyList(),
    val note: String? = null,
    val stale: Boolean = false,
    val items: Map<String, List<MacMenuItem>> = emptyMap(),
)

/** The Mac's cursor; [text] when it is the I-beam, the pointer over text. */
data class CursorImage(val image: ImageBitmap, val hotX: Float, val hotY: Float, val width: Float, val height: Float, val text: Boolean = false)

data class LinkStats(val fps: Int = 0, val frameAgeMs: Int = -1, val rttMs: Int = -1)

/**
 * The app's one connection to a Mac and everything the screens show about it. Discovery, pairing,
 * the session, reconnects and the catalog live here, so a rotation or a screen change never drops
 * the stream.
 */
class Controller(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())

    val discovery = Discovery(context)
    val saved = SavedMacs(context)
    private val identities = IdentityStore(context)
    val decoder = VideoDecoder()

    val deviceName: String = run {
        val n = try { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) } catch (_: Exception) { null }
        val name = n?.takeIf { it.isNotBlank() } ?: Build.MODEL
        "$name (${Build.MANUFACTURER} ${Build.MODEL})"
    }
    private val shortName: String = Build.MODEL

    // --- connection state
    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = _phase
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status
    private val _pairing = MutableStateFlow<PairingPrompt?>(null)
    val pairing: StateFlow<PairingPrompt?> = _pairing
    private val _target = MutableStateFlow<Target?>(null)
    val target: StateFlow<Target?> = _target
    private val _route = MutableStateFlow<String?>(null)
    /** "Wi‑Fi" at home, "through Tailscale"/"from afar" for a remote session. */
    val route: StateFlow<String?> = _route
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notices: SharedFlow<String> = _notices

    // --- the catalog and the stream
    private val _windows = MutableStateFlow<WindowList?>(null)
    val windows: StateFlow<WindowList?> = _windows
    private val _thumbnails = MutableStateFlow<Map<Long, ImageBitmap>>(emptyMap())
    val thumbnails: StateFlow<Map<Long, ImageBitmap>> = _thumbnails
    private val _icons = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val icons: StateFlow<Map<String, ImageBitmap>> = _icons
    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps
    private val _settings = MutableStateFlow<HostSettingsState?>(null)
    val settings: StateFlow<HostSettingsState?> = _settings
    private val _settingsPending = MutableStateFlow<HostSettingsChange?>(null)
    val settingsPending: StateFlow<HostSettingsChange?> = _settingsPending
    private val _cursor = MutableStateFlow<CursorImage?>(null)
    val cursor: StateFlow<CursorImage?> = _cursor
    private val _macPointer = MutableStateFlow<Pair<Double, Double>?>(null)
    /** Where the Mac's own pointer is (frame fractions) while the Mac or another device moves it. */
    val macPointer: StateFlow<Pair<Double, Double>?> = _macPointer
    private val _menus = MutableStateFlow(MenuState())
    val menus: StateFlow<MenuState> = _menus
    private val _stats = MutableStateFlow(LinkStats())
    val stats: StateFlow<LinkStats> = _stats
    private val _videoSize = MutableStateFlow<Pair<Int, Int>?>(null)
    val videoSize: StateFlow<Pair<Int, Int>?> = _videoSize
    private val _clipboardNote = MutableStateFlow<String?>(null)
    val clipboardNote: StateFlow<String?> = _clipboardNote

    val shareClipboard = MutableStateFlow(prefs().getBoolean("shareClipboard", true))
    val gesturesOn = MutableStateFlow(prefs().getBoolean("gestures", true))
    /** Landscape: the picture is a trackpad (relative pointer) rather than direct touch. */
    val trackpadMode = MutableStateFlow(prefs().getBoolean("trackpadMode", false))
    /** A click that lands in text offers the software keyboard (a button by it for a few seconds). */
    val autoKeyboard = MutableStateFlow(prefs().getBoolean("autoKeyboard", true))
    val barPosition = MutableStateFlow(
        prefs().getString("barPosition", null)?.let { n -> BarPosition.entries.firstOrNull { it.name == n } } ?: BarPosition.TOP,
    )

    private val _lastClick = MutableStateFlow<Pair<Double, Double>?>(null)
    /** Where the last plain click landed (frame fractions): the spot the keyboard keeps in sight. */
    val lastClick: StateFlow<Pair<Double, Double>?> = _lastClick
    private val _textHint = MutableStateFlow(false)
    /**
     * A plain click left the Mac's pointer an I-beam in the window it was made in: likely a click
     * into a text field, so the keyboard is offered. Cleared after [TEXT_HINT_MS] and by the next click.
     */
    val textHint: StateFlow<Boolean> = _textHint
    private var textWatch: Job? = null

    @Volatile private var session: Session? = null
    private var sessionPin: ByteArray? = null
    private var sessionSaved: SavedMac? = null
    private var firstList = true
    private var lastAutoDesktop = 0L
    private var lastUserPick = 0L
    private var lastViewport: Viewport? = null
    private var reconnectJob: Job? = null
    private var dialJob: Job? = null
    private var userEnded = false
    private var lastGoodbye: Goodbye? = null
    private val tokens = AtomicInteger(0)
    private var settingsTimeout: Runnable? = null
    private val receipts = HashMap<String, Runnable>()

    init {
        decoder.onVideoSize = { w, h -> _videoSize.value = w to h }
        decoder.onStall = { main.post { resync() } }
        decoder.onNewSurface = { main.post { resync(force = true) } }
        decoder.onFrameShown = { lastFrameAt = System.currentTimeMillis() }
        scope.launch {
            while (true) {
                delay(1_000)
                watchPicture()
            }
        }
        // Each saved Mac Bonjour lists: its home door kept, to dial when Bonjour doesn't hear it.
        scope.launch {
            discovery.macs.collect { rows ->
                for (row in rows) {
                    if (row.door == HomeDoor.PLAIN) continue
                    val ip = row.addresses.firstOrNull { it is java.net.Inet4Address }?.hostAddress ?: continue
                    val mac = saved.byTag(row.tag) ?: continue
                    val at = ParsedAddress(ip, row.port).text
                    if (mac.homeAddress != at) saved.update(mac.macID) { it.copy(homeAddress = at) }
                }
            }
        }
    }

    private var lastResync = 0L

    /**
     * The decoder lost a frame and waits for a keyframe, which the Mac sends on its own only every
     * few seconds while the picture moves. Picking the source that already streams restarts the
     * Mac's pipeline, which starts with a keyframe. At most once every 5 s, unless [force] (a new
     * surface: the app back in front).
     */
    private fun resync(force: Boolean = false) {
        val s = session ?: return
        val active = _windows.value?.active ?: return
        if (active == StreamSource.None) return
        val now = System.currentTimeMillis()
        if (!force && now - lastResync < 5_000) return
        lastResync = now
        Log.i(TAG, if (force) "new surface; restarting the stream for a keyframe" else "decoder lost a frame; restarting the stream for a keyframe")
        askStream(s, active)
    }

    // --- no picture: the Mac's display is off or locked

    @Volatile private var lastFrameAt = 0L
    /** When this device last asked the Mac to start a stream, which begins with a keyframe at once. */
    private var streamAskedAt = 0L
    private val _noPicture = MutableStateFlow(false)
    /**
     * A stream was asked for and no frame came within [NO_PICTURE_AFTER]: macOS captures nothing
     * while the Mac's display is off, and Sill's clicks don't turn it on.
     */
    val noPicture: StateFlow<Boolean> = _noPicture

    private fun askStream(s: Session, source: StreamSource) {
        streamAskedAt = System.currentTimeMillis()
        _noPicture.value = false
        s.select(source)
    }

    /** Once a second: says so when no picture came, and asks again every 10 s for when the display wakes. */
    private fun watchPicture() {
        val now = System.currentTimeMillis()
        val waiting = session != null && decoder.hasSurface && _phase.value == Phase.CONNECTED && _windows.value?.active.let { it != null && it != StreamSource.None } &&
            streamAskedAt > 0 && lastFrameAt < streamAskedAt && now - streamAskedAt > NO_PICTURE_AFTER
        _noPicture.value = waiting
        if (waiting && now - lastResync >= 10_000) resync(force = true)
    }

    /** The user's "try again" under the no-picture note. */
    fun retryPicture() = resync(force = true)

    private fun prefs() = context.getSharedPreferences("app", Context.MODE_PRIVATE)
    private fun str(id: Int, vararg args: Any): String = context.getString(id, *args)

    fun setShareClipboard(on: Boolean) {
        shareClipboard.value = on
        prefs().edit().putBoolean("shareClipboard", on).apply()
        if ((_windows.value?.clipboard ?: 0) >= 1) session?.clipboard(if (on) Clipboard() else Clipboard(stop = true))
    }

    fun setGestures(on: Boolean) {
        gesturesOn.value = on
        prefs().edit().putBoolean("gestures", on).apply()
    }

    fun setTrackpadMode(on: Boolean) {
        trackpadMode.value = on
        prefs().edit().putBoolean("trackpadMode", on).apply()
    }

    /**
     * The order apps keep in the window strip, by bundle ID: the Mac lists windows most recent
     * first, which reshuffles them at every switch. Apps join at the end when first seen.
     */
    private val _appOrder = MutableStateFlow(prefs().getString("appOrder", "")!!.split('\n').filter { it.isNotEmpty() })
    val appOrder: StateFlow<List<String>> = _appOrder

    private fun setAppOrder(order: List<String>) {
        _appOrder.value = order
        prefs().edit().putString("appOrder", order.joinToString("\n")).apply()
    }

    /** Moves [w]'s app one place left (-1) or right (+1) among the apps open now. */
    fun moveApp(w: WindowInfo, by: Int) {
        val open = orderedWindows(_windows.value?.windows ?: return, _appOrder.value).map { appKey(it) }.distinct()
        val i = open.indexOf(appKey(w))
        val j = i + by
        if (i < 0 || j !in open.indices) return
        val other = open[j]
        val order = _appOrder.value.toMutableList()
        val a = order.indexOf(appKey(w))
        val b = order.indexOf(other)
        if (a < 0 || b < 0) return
        order[a] = other; order[b] = appKey(w)
        setAppOrder(order)
    }

    fun setAutoKeyboard(on: Boolean) {
        autoKeyboard.value = on
        prefs().edit().putBoolean("autoKeyboard", on).apply()
    }

    fun setBarPosition(at: BarPosition) {
        barPosition.value = at
        prefs().edit().putString("barPosition", at.name).apply()
    }

    // =============================================================================================
    // Dialing

    /** A tap on a row of the network's Macs. */
    fun tap(row: FoundMac) {
        val savedMac = saved.byTag(row.tag) ?: saved.all.value.firstOrNull { row.tag == null && it.bonjourName == row.serviceName && !it.revoked }
        val t = Target.Row(row, savedMac)
        when {
            row.door == HomeDoor.PLAIN -> dial(t)
            savedMac != null -> dial(t)
            row.door == HomeDoor.OPEN -> dial(t)
            else -> ask(row)
        }
    }

    fun connectRemote(mac: SavedMac) = dial(Target.Remote(mac))

    fun connectAddress(host: String, port: Int, plain: Boolean) = dial(Target.Address(host, port, plain))

    private fun dial(t: Target, reconnecting: Boolean = false) {
        dialJob?.cancel()
        _target.value = t
        _pairing.value = null
        userEnded = false
        _phase.value = if (reconnecting) Phase.RECONNECTING else Phase.DIALING
        _status.value = str(if (reconnecting) R.string.reconnecting_to else R.string.connecting_to, t.name)
        dialJob = scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { open(t) } }
            result.onSuccess { (transport, pin, mac) -> startSession(t, transport, pin, mac) }
            result.onFailure { e -> dialFailed(t, e, reconnecting) }
        }
    }

    /** Opens the right kind of connection for [t]: (transport, the Mac's pin, the saved Mac). */
    private suspend fun open(t: Target): Triple<Transport, ByteArray?, SavedMac?> {
        val id = identities.identity()
        return when (t) {
            is Target.Row -> {
                val host = t.row.bestAddress?.hostAddress ?: error("no address")
                when {
                    t.row.door == HomeDoor.PLAIN -> Triple(Transport.plain(host, t.row.port), null, t.saved)
                    t.saved != null -> Triple(Transport.tls(host, t.row.port, id, Alpn.SESSION, t.saved.pin), t.saved.pin, t.saved)
                    else -> {
                        val tr = Transport.tls(host, t.row.port, id, Alpn.SESSION, null)
                        Triple(tr, tr.peerFingerprint, null)
                    }
                }
            }
            is Target.Address -> {
                if (t.plain) return Triple(Transport.plain(t.host, t.port), null, null)
                val s = saved.all.value.firstOrNull { !it.revoked && it.lastWorked == ParsedAddress(t.host, t.port).text }
                val tr = Transport.tls(t.host, t.port, id, Alpn.SESSION, s?.pin)
                Triple(tr, tr.peerFingerprint, s ?: tr.peerFingerprint?.let { saved.byFingerprint(it) })
            }
            is Target.Remote -> {
                val tr = raceRemote(t.mac, id)
                Triple(tr, t.mac.pin, t.mac)
            }
        }
    }

    /** One way to the Mac from afar: its remote door at [host]:[port]. */
    private data class Way(val host: String, val port: Int) {
        /** What [SavedMac.lastWorked] keeps. */
        val text: String get() = ParsedAddress(host, port).text
    }

    /**
     * Every way to the Mac from afar, a second apart; the first handshake wins. Once the race is
     * decided (a winner, every attempt failed, the 20 s limit, or the dial cancelled), the
     * deferred is closed, so a handshake that finishes later finds it taken and closes its own
     * connection instead of leaving it open on the Mac.
     */
    private suspend fun raceRemote(mac: SavedMac, id: dev.sash.protocol.Identity): Transport {
        val candidates = remoteCandidates(mac)
        if (candidates.isEmpty()) error(str(R.string.no_address_for, mac.name))
        val winner = CompletableDeferred<Pair<Transport, String>>()
        val failures = AtomicInteger(0)
        val jobs = candidates.mapIndexed { i, way ->
            scope.launch(Dispatchers.IO) {
                delay(i * 1000L)
                if (winner.isCompleted) return@launch
                try {
                    val tr = Transport.tls(way.host, way.port, id, Alpn.SESSION, mac.pin)
                    if (!winner.complete(tr to way.text)) tr.close()
                } catch (e: Exception) {
                    if (failures.incrementAndGet() == candidates.size) winner.completeExceptionally(e)
                }
            }
        }
        var got: Pair<Transport, String>? = null
        try {
            got = withTimeoutOrNull(20_000) { winner.await() }
        } finally {
            jobs.forEach { it.cancel() }
            // Not taken by us (timeout, failure, cancellation): later winners must close theirs,
            // and one that won in the instant before this must be closed here.
            if (got == null && !winner.completeExceptionally(java.util.concurrent.CancellationException("race decided"))) {
                @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
                runCatching { winner.getCompleted().first.close() }
            }
        }
        val won = got ?: throw java.io.IOException(str(R.string.didnt_answer, mac.name))
        // The home door is kept on its own (homeAddress): as "worked last" it would be tried first from afar too.
        if (won.second != mac.homeAddress) saved.update(mac.macID) { it.copy(lastWorked = won.second) }
        return won.first
    }

    /**
     * The address that worked last time first, then the home door Bonjour last listed (Bonjour may
     * just not hear the Mac now), then Sill's own (VPN, LAN, internet), then typed ones.
     */
    private fun remoteCandidates(mac: SavedMac): List<Way> {
        val out = LinkedHashMap<String, Way>()
        fun add(w: Way) { out.putIfAbsent(w.text, w) }
        fun direct(a: ParsedAddress) = add(Way(a.host, a.port ?: mac.remotePort))
        mac.lastWorked?.let { ParsedAddress.parse(it) }?.let(::direct)
        mac.homeAddress?.let { ParsedAddress.parse(it) }?.takeIf { it.port != null }?.let(::direct)
        val rank = mapOf("vpn" to 0, "lan" to 1, "internet" to 2)
        mac.addresses.sortedBy { rank[it.kind] ?: 3 }.forEach { add(Way(it.host, it.port ?: mac.remotePort)) }
        mac.typedAddresses.mapNotNull { ParsedAddress.parse(it) }.forEach(::direct)
        return out.values.toList()
    }

    private fun dialFailed(t: Target, e: Throwable, reconnecting: Boolean) {
        Log.w(TAG, "dial ${t.name} failed", e)
        if (e is PinRefused && t is Target.Row && t.saved != null) {
            // The Mac was set up again (a new key): pair once more.
            _status.value = str(R.string.new_key, t.name)
            saved.update(t.saved.macID) { it.copy(revoked = true) }
            _phase.value = Phase.IDLE
            return
        }
        if (reconnecting) { scheduleReconnect(); return }
        _phase.value = Phase.IDLE
        _status.value = when {
            e is java.net.SocketTimeoutException || e is java.net.ConnectException -> str(R.string.didnt_answer, t.name)
            e is javax.net.ssl.SSLException && t is Target.Row && t.saved == null -> str(R.string.asks_to_pair, t.name)
            e is javax.net.ssl.SSLException -> str(R.string.refused_device, t.name)
            else -> e.message ?: str(R.string.couldnt_connect, t.name)
        }
    }

    // =============================================================================================
    // Pairing

    /** An unpaired device's tap at a door that asks devices to pair: "pair me". */
    private fun ask(row: FoundMac) {
        val host = row.bestAddress?.hostAddress ?: return
        _target.value = Target.Row(row, null)
        _pairing.value = PairingPrompt(row.serviceName, codeShown = false, busy = true, host = host, port = row.port, row = row)
        scope.launch {
            val out = withContext(Dispatchers.IO) {
                runCatching { PairingClient.ask(host, row.port, identities.identity(), shortName, Build.MODEL, cable = false) }
            }
            val prompt = _pairing.value ?: return@launch
            out.onFailure { _pairing.value = prompt.copy(busy = false, error = str(R.string.didnt_answer, row.serviceName)) }
            out.onSuccess { o ->
                when (o) {
                    is PairingClient.Outcome.Refused -> _pairing.value = when (o.reason) {
                        "shown" -> prompt.copy(codeShown = true, busy = false, askedKey = o.macFingerprint)
                        "openOnMac" -> prompt.copy(busy = false, error = str(R.string.open_pair_on_mac), codeShown = true)
                        "locked" -> prompt.copy(busy = false, error = str(R.string.unlock_mac_first, row.serviceName))
                        "busy" -> prompt.copy(busy = false, error = str(R.string.mac_busy_retry, row.serviceName, (o.retryAfter ?: 5.0).toInt()))
                        else -> prompt.copy(busy = false, error = o.message ?: str(R.string.no_code_shown, row.serviceName, o.reason))
                    }
                    is PairingClient.Outcome.Paired -> paired(o, row, null)
                    PairingClient.Outcome.BadProof -> _pairing.value = prompt.copy(busy = false, error = str(R.string.bad_proof))
                }
            }
        }
    }

    /** The 12 digits from the Mac's window, after an ask or for a typed address from afar. */
    fun submitCode(code: String) {
        val p = _pairing.value ?: return
        val check = dev.sash.protocol.PairingCode.check(code)
        if (check !is dev.sash.protocol.PairingCode.Check.Ok) {
            _pairing.value = p.copy(error = if (check is dev.sash.protocol.PairingCode.Check.Typo) str(R.string.code_typo) else str(R.string.code_length))
            return
        }
        _pairing.value = p.copy(busy = true, error = null)
        scope.launch {
            val out = withContext(Dispatchers.IO) {
                runCatching {
                    PairingClient.pair(p.host, p.port, identities.identity(), PairingClient.Secret.Code(check.code), p.askedKey, shortName, Build.MODEL)
                }
            }
            handlePairOutcome(out, p, typedAddress = if (p.row == null) ParsedAddress(p.host, p.port).text else null)
        }
    }

    /** Pairing from afar: the Mac's address and the code from its window. */
    fun startRemotePairing(address: String) {
        val a = ParsedAddress.parse(address) ?: run { _status.value = str(R.string.not_an_address); return }
        val port = a.port ?: 7455
        _pairing.value = PairingPrompt(a.host, codeShown = true, host = a.host, port = port)
        // At a home door an ask puts the Mac's code up by itself; a remote door answers "closed"
        // without counting a try, and the person opens the window on the Mac as before.
        scope.launch {
            val out = withContext(Dispatchers.IO) {
                runCatching { PairingClient.ask(a.host, port, identities.identity(), shortName, Build.MODEL, cable = false) }
            }.getOrNull()
            val p = _pairing.value ?: return@launch
            if (out is PairingClient.Outcome.Refused && out.reason == "shown" && p.host == a.host) {
                _pairing.value = p.copy(askedKey = out.macFingerprint)
            }
        }
    }

    /** A `sill://pair` link (the QR code): home rows first, pinned to its key, then its addresses. */
    fun pairLink(link: PairLink) {
        _pairing.value = PairingPrompt(link.name, codeShown = true, busy = true)
        scope.launch {
            val id = identities.identity()
            val tries = discovery.macs.value.filter { it.door != HomeDoor.PLAIN }
                .mapNotNull { r -> r.bestAddress?.hostAddress?.let { Triple(it, r.port, r) } } +
                link.addresses.map { Triple(it.host, it.port ?: link.port, null) }
            var last: Result<PairingClient.Outcome>? = null
            for ((host, port, row) in tries) {
                val out = withContext(Dispatchers.IO) {
                    runCatching { PairingClient.pair(host, port, id, PairingClient.Secret.Link(link.secret), link.fingerprint, shortName, Build.MODEL) }
                }
                last = out
                if (out.isSuccess) {
                    handlePairOutcome(out, PairingPrompt(link.name, true, host = host, port = port, row = row), typedAddress = null)
                    return@launch
                }
            }
            _pairing.value = PairingPrompt(link.name, codeShown = true, error = str(R.string.couldnt_reach, link.name))
            last?.exceptionOrNull()?.let { Log.w(TAG, "pair link", it) }
        }
    }

    private fun handlePairOutcome(out: Result<PairingClient.Outcome>, p: PairingPrompt, typedAddress: String?) {
        out.onFailure { e ->
            Log.w(TAG, "pairing", e)
            _pairing.value = p.copy(busy = false, error = str(R.string.didnt_answer, p.macName))
        }
        out.onSuccess { o ->
            when (o) {
                is PairingClient.Outcome.Paired -> paired(o, p.row, typedAddress, p)
                is PairingClient.Outcome.Refused -> _pairing.value = p.copy(
                    busy = false,
                    error = when (o.reason) {
                        "code" -> listOfNotNull(str(R.string.code_mismatch), o.triesLeft?.let { context.resources.getQuantityString(R.plurals.tries_left, it, it) }).joinToString(" ")
                        "expired" -> str(R.string.code_expired)
                        "closed", "stopped" -> str(R.string.code_not_shown)
                        "busy" -> str(R.string.wait_retry, (o.retryAfter ?: 2.0).toInt())
                        else -> o.message ?: str(R.string.mac_said_no, o.reason)
                    },
                )
                PairingClient.Outcome.BadProof -> _pairing.value = p.copy(busy = false, error = str(R.string.bad_proof_code))
            }
        }
    }

    private fun paired(o: PairingClient.Outcome.Paired, row: FoundMac?, typedAddress: String?, prompt: PairingPrompt? = null) {
        val mac = SavedMac(
            macID = o.macID,
            fingerprint = Base64URL.encode(o.macFingerprint),
            name = o.name,
            recognitionKey = o.recognitionKey?.let { Base64URL.encode(it) },
            bonjourName = row?.serviceName,
            typedAddresses = listOfNotNull(typedAddress),
            lastWorked = typedAddress,
            defaultsSent = false,
        )
        saved.save(mac)
        _pairing.value = null
        _notices.tryEmit(str(R.string.paired_with, o.name))
        when {
            row != null -> dial(Target.Row(row, mac))
            prompt != null && prompt.host.isNotEmpty() -> dial(Target.Remote(mac))
        }
    }

    fun cancelPairing() {
        val p = _pairing.value
        _pairing.value = null
        if (p?.row != null && p.codeShown) {
            scope.launch(Dispatchers.IO) { PairingClient.cancel(p.host, p.port, identities.identity(), shortName) }
        }
    }

    // =============================================================================================
    // The session

    private fun startSession(t: Target, transport: Transport, pin: ByteArray?, mac: SavedMac?) {
        session?.let { old -> session = null; old.close() }
        sessionPin = pin
        sessionSaved = mac
        firstList = true
        lastGoodbye = null
        _route.value = when {
            t is Target.Remote -> remoteRoute(transport)
            else -> if (transport.isTls) null else str(R.string.route_unencrypted)
        }
        val events = SessionEvents()
        val s = Session(transport, deviceName, SillParity.VERSION, events)
        events.me = s
        session = s
        s.start()
        // A session counts as connected only at its first window list (within 10 s).
        main.postDelayed({
            if (session === s && firstList && s.isOpen) {
                s.close()
                _status.value = str(R.string.didnt_send_windows, t.name)
            }
        }, 10_000)
    }

    private fun remoteRoute(t: Transport): String {
        val a = t.socket.inetAddress?.hostAddress ?: return str(R.string.route_afar)
        return str(if (a.startsWith("100.") || a.startsWith("fd7a:115c:a1e0")) R.string.route_tailscale else R.string.route_afar)
    }

    /**
     * One session's events. Each session gets its own, and every callback first checks that its
     * session is still the current one, so a session that ended (or was replaced) cannot touch
     * the next one's state: its late end, window list or frames are dropped.
     */
    private inner class SessionEvents : SessionListener {
        @Volatile var me: Session? = null
        private val live get() = me != null && session === me

        /** On the main thread, and only while this session is still the current one. */
        private fun onMain(block: () -> Unit) { main.post { if (live) block() } }

        override fun onWindowList(list: WindowList) = onMain { windowList(list) }

        override fun onHostSettings(state: HostSettingsState) = onMain {
            _settings.value = state
            sendDefaultsOnce(state)
            val pending = _settingsPending.value
            if (pending != null && state.answering != null && state.answering!! >= (pending.token ?: 0)) {
                val refused = (pending.bitrate != null && pending.bitrate != state.settings.bitrate) ||
                    (pending.maxFPS != null && pending.maxFPS != state.settings.maxFPS) ||
                    (pending.captureScale != null && pending.captureScale != state.settings.captureScale)
                if (refused) _notices.tryEmit(str(R.string.mac_kept_setting))
                _settingsPending.value = null
            }
        }

        override fun onMacInfo(info: SignedMacInfo) {
            if (!live) return
            val (mi, fp) = info.verified() ?: return
            // Only the key this very connection proved names this Mac.
            if (sessionPin == null || !fp.contentEquals(sessionPin)) return
            saved.byFingerprint(fp)?.let { saved.learn(it.macID, mi) }
        }

        override fun onThumbnail(t: Blobs.Thumbnail) {
            if (!live) return
            val bmp = BitmapFactory.decodeByteArray(t.jpeg, 0, t.jpeg.size) ?: return
            _thumbnails.value = _thumbnails.value + (t.windowID to bmp.asImageBitmap())
        }

        override fun onAppIcon(icon: Blobs.Icon) {
            if (!live) return
            val bmp = BitmapFactory.decodeByteArray(icon.png, 0, icon.png.size) ?: return
            _icons.value = _icons.value + (icon.bundleID to bmp.asImageBitmap())
        }

        override fun onAppList(apps: List<AppInfo>) { if (live) _apps.value = apps.sortedBy { it.name.lowercase() } }

        override fun onCursorShape(c: Blobs.Cursor) {
            if (!live) return
            val bmp = BitmapFactory.decodeByteArray(c.png, 0, c.png.size) ?: return
            _cursor.value = CursorImage(bmp.asImageBitmap(), c.hotX, c.hotY, c.width, c.height, isIBeam(bmp, c))
        }

        override fun onParameterSets(ps: ParameterSets) { if (live) decoder.parameterSets(ps) }

        override fun onFrame(accessUnit: ByteArray, keyframe: Boolean, timestamp: Double) { if (live) decoder.frame(accessUnit, keyframe) }

        override fun onMacPointer(p: MacPointer) {
            val s = me?.takeIf { live } ?: return
            if ((p.seen ?: 0) < s.inputsSent) return // built before the Mac read our latest input
            val x = p.x; val y = p.y
            _macPointer.value = if (p.inside == true && x != null && y != null) x to y else null
        }

        override fun onMacMenu(m: MacMenu) = onMain { macMenu(m) }

        override fun onClipboard(c: Clipboard) {
            val text = c.text ?: return
            if (!shareClipboard.value) return
            onMain {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                ignoreNextLocalClip = text
                cm.setPrimaryClip(ClipData.newPlainText("Mac", text))
                _notices.tryEmit(str(R.string.copied_on_mac))
            }
        }

        override fun onClipboardReceipt(token: ByteArray) = onMain {
            receipts.remove(Base64URL.encode(token))?.let { main.removeCallbacks(it); _clipboardNote.value = str(R.string.sent_to_clipboard) }
        }

        override fun onGoodbye(g: Goodbye) { if (live) lastGoodbye = g }

        override fun onStats(fps: Int, frameAgeMs: Int, rttMs: Int) { if (live) _stats.value = LinkStats(fps, frameAgeMs, rttMs) }

        // Ended: only the current session's end decides anything (a replaced or disconnected
        // session has already been let go of).
        override fun onEnded(error: Throwable?) = onMain { sessionEnded(error) }
    }

    private fun windowList(list: WindowList) {
        val s = session ?: return
        _windows.value = list
        val newApps = list.windows.map { appKey(it) }.distinct().filter { it !in _appOrder.value }
        if (newApps.isNotEmpty()) setAppOrder(_appOrder.value + newApps)
        // Thumbnails of windows that closed go with them.
        val open = list.windows.mapTo(HashSet()) { it.id }
        if (_thumbnails.value.keys.any { it !in open }) _thumbnails.value = _thumbnails.value.filterKeys { it in open }
        if (firstList) {
            firstList = false
            _phase.value = Phase.CONNECTED
            _status.value = null
            reconnectJob?.cancel()
            reconnectAttempts = 0
            sessionSaved?.let { m ->
                val row = (_target.value as? Target.Row)?.row
                saved.update(m.macID) { it.copy(lastConnectedAt = System.currentTimeMillis(), bonjourName = row?.serviceName ?: it.bonjourName, revoked = false) }
            }
            lastViewport?.let { s.viewport(it) }
            s.fetchMenu(FetchMenu(token = tokens.incrementAndGet()))
            if ((list.clipboard ?: 0) >= 1 && shareClipboard.value) s.clipboard(Clipboard())
        }
        // Nothing streams: ask for the Desktop, at most once every 10 s, never right over a pick.
        if (list.active == StreamSource.None) {
            val now = System.currentTimeMillis()
            if (now - lastAutoDesktop > 10_000 && now - lastUserPick > 2_000) {
                lastAutoDesktop = now
                main.postDelayed({
                    if (session === s && _windows.value?.active == StreamSource.None && System.currentTimeMillis() - lastUserPick > 2_000) {
                        askStream(s, StreamSource.Desktop)
                    }
                }, if (firstListDone) 2_000 else 0)
            }
        }
        firstListDone = true
    }

    private var firstListDone = false
    private var reconnectAttempts = 0

    private fun sessionEnded(error: Throwable?) {
        val t = _target.value
        session = null
        firstListDone = false
        _macPointer.value = null
        if (userEnded || t == null) {
            resetCatalog()
            _phase.value = Phase.IDLE
            return
        }
        val g = lastGoodbye
        val words = g?.let { goodbyeWords(it, t.name) }
        val reconnect = when (g?.reason) {
            null -> true
            "quit", "busy" -> true
            "remoteOff", "internetOff" -> t !is Target.Remote
            "removed" -> { sessionSaved?.let { m -> saved.update(m.macID) { it.copy(revoked = true) } }; false }
            "pairingRequired" -> false
            else -> g.reconnect == true
        }
        if (!reconnect) {
            resetCatalog()
            _phase.value = Phase.IDLE
            _status.value = words
            return
        }
        Log.i(TAG, "session ended: $error; reconnecting")
        _status.value = words ?: str(R.string.reconnecting_to, t.name)
        _phase.value = Phase.RECONNECTING
        scheduleReconnect(if (g?.reason == "quit") 3_000 else 0)
    }

    // An update notice is the one goodbye in this app's own words: the Mac's speak of Sill and the
    // App Store, and its version is Sill's (SillParity), not this app's.
    private fun goodbyeWords(g: Goodbye, name: String): String = if (g.reason == "update") {
        val min = g.minimumVersion?.let { dev.sash.protocol.SafeText.label(it, 32) }?.takeIf { it.isNotEmpty() }
        if (min != null) str(R.string.needs_update_min, name, min) else str(R.string.needs_update, name)
    } else g.message?.let { dev.sash.protocol.SafeText.label(it, 300) }?.takeIf { it.isNotEmpty() }
        ?: when (g.reason) {
            "quit" -> str(R.string.bye_quit, name)
            "removed" -> str(R.string.bye_removed, name)
            "pairingRequired" -> str(R.string.bye_pairing_required, name)
            "remoteOff" -> str(R.string.bye_remote_off, name)
            "internetOff" -> str(R.string.bye_internet_off, name)
            "busy" -> str(R.string.bye_busy, name)
            else -> str(R.string.bye_ended, name)
        }

    private fun scheduleReconnect(firstDelay: Long = 0) {
        reconnectJob?.cancel()
        val t = _target.value ?: return
        reconnectJob = scope.launch {
            val delays = listOf(0L, 2_000, 4_000, 8_000, 10_000, 10_000, 20_000, 30_000, 30_000, 30_000, 30_000, 30_000)
            if (reconnectAttempts >= delays.size) {
                _phase.value = Phase.IDLE
                _status.value = str(R.string.stopped_trying, t.name)
                resetCatalog()
                return@launch
            }
            delay(firstDelay + delays[reconnectAttempts])
            reconnectAttempts++
            if (!isActive || userEnded) return@launch
            // Home first: the row as listed now (by tag for a saved Mac), else from afar.
            val mac = sessionSaved
            val row = discovery.macs.value.firstOrNull { r ->
                (mac != null && mac.recognizes(r.tag)) || (t is Target.Row && r.serviceName == t.row.serviceName)
            }
            val next = when {
                row != null -> Target.Row(row, mac)
                mac != null && (mac.addresses.isNotEmpty() || mac.typedAddresses.isNotEmpty() || mac.lastWorked != null || mac.homeAddress != null) -> Target.Remote(mac)
                t is Target.Address -> t
                else -> null
            }
            if (next == null) { scheduleReconnect(); return@launch }
            dial(next, reconnecting = true)
        }
    }

    private fun resetCatalog() {
        _windows.value = null
        _thumbnails.value = emptyMap()
        _settings.value = null
        _settingsPending.value = null
        _menus.value = MenuState()
        _macPointer.value = null
        _lastClick.value = null
        _videoSize.value = null
        _route.value = null
        _stats.value = LinkStats()
    }

    fun disconnect() {
        userEnded = true
        reconnectJob?.cancel()
        dialJob?.cancel()
        val s = session
        session = null
        s?.close()
        resetCatalog()
        _phase.value = Phase.IDLE
        _status.value = null
    }

    /** Back in front after the background: a dead link is found again (the browse began afresh in onStart). */
    fun cameForward() {
        if (_phase.value == Phase.RECONNECTING) { reconnectAttempts = 0; scheduleReconnect() }
    }

    // =============================================================================================
    // What the stream screen sends

    fun select(source: StreamSource) {
        lastUserPick = System.currentTimeMillis()
        session?.let { askStream(it, source) }
    }

    fun launchApp(bundleID: String) {
        lastUserPick = System.currentTimeMillis()
        session?.launchApp(bundleID)
    }

    fun windowCommand(id: Long, action: String) = session?.windowCommand(id, action)

    fun viewport(v: Viewport) {
        if (v == lastViewport) return
        lastViewport = v
        session?.viewport(v)
    }

    /** Where this device last put the Mac's pointer (frame fractions). */
    private var lastPut: Pair<Double, Double>? = null
    /** Taps' clicks, one after another: a waiting click is not overtaken by the next tap's. */
    private val clicks = Mutex()

    fun pointer(action: String, x: Double, y: Double) {
        _macPointer.value = null
        lastPut = x to y
        session?.input(Input.pointer(action, x, y))
    }

    fun move(x: Double, y: Double) {
        _macPointer.value = null
        lastPut = x to y
        session?.pointerMove(x, y)
    }

    /**
     * A tap's click at [x], [y] ([secondary]: the right button) with [mods] held. A pointer that
     * jumps into a magnifying Dock makes its icons grow and slide under it, and a click sent with
     * the jump lands while they move. So a jump to near the desktop's bottom or side edges (where
     * the Dock lives) moves first and clicks once the Dock has settled.
     */
    fun tapClick(x: Double, y: Double, secondary: Boolean, mods: Long) {
        val from = lastPut
        move(x, y)
        val jump = from == null || hypot(from.first - x, from.second - y) > 0.01
        val nearEdge = y > 1 - DOCK_BAND || x < DOCK_BAND || x > 1 - DOCK_BAND
        val wait = jump && nearEdge && _windows.value?.active == StreamSource.Desktop
        scope.launch {
            clicks.withLock {
                if (wait) delay(DOCK_SETTLE_MS)
                withModifiers(mods) {
                    if (secondary) { pointer(PointerAction.RIGHT_DOWN, x, y); pointer(PointerAction.RIGHT_UP, x, y) }
                    else { pointer(PointerAction.LEFT_DOWN, x, y); pointer(PointerAction.LEFT_UP, x, y) }
                }
                if (!secondary) clicked(x, y)
            }
        }
    }

    /**
     * A plain left click at [x], [y], already sent: remembered, and watched for landing in text.
     * The cursor's shape follows the pointer through the host's 20 Hz poll and the link, so it gets
     * a round trip to settle at the new spot (an I-beam left over from the last field must not
     * count), then a little longer to turn into one.
     */
    fun clicked(x: Double, y: Double) {
        _lastClick.value = x to y
        textWatch?.cancel()
        _textHint.value = false
        if (!autoKeyboard.value) return
        val before = windowState()
        textWatch = scope.launch {
            val rtt = _stats.value.rttMs.coerceIn(0, 500)
            delay(250L + rtt)
            withTimeoutOrNull(600) { _cursor.first { it?.text == true } } ?: return@launch
            // A click that opened or switched a window (a chat, a link, an app) leaves whatever the
            // new one autofocuses under the pointer: not a click into text. The list may trail the
            // cursor's shape, so it gets a moment more.
            delay(150L + rtt / 2)
            if (windowState() != before) return@launch
            _textHint.value = true
            delay(TEXT_HINT_MS)
            _textHint.value = false
        }
    }

    fun dismissTextHint() {
        textWatch?.cancel()
        _textHint.value = false
    }

    /** What a click that opens or switches a window changes: the stream, the windows, the front one and its title. */
    private fun windowState(): Any? = _windows.value?.let { l -> Triple(l.active, l.windows.mapTo(HashSet()) { it.id }, l.windows.firstOrNull()?.let { it.id to it.title }) }

    fun scroll(x: Double, y: Double, dx: Double, dy: Double) = session?.input(Input.scroll(x, y, dx, dy))
    fun scrollPhase(phase: String, x: Double, y: Double) = session?.input(Input.scrollGesture(phase, x, y))
    fun text(t: String) = session?.input(Input.text(t))
    fun key(usage: Int, down: Boolean, mods: Long) = session?.input(Input.key(usage, down, mods))

    /**
     * [action] with [mods] held as a keyboard holds them: each modifier's own key goes down
     * (carrying the flags held so far), then [action], then the keys come up in reverse, each up
     * carrying the flags still held. Shortcuts and latched clicks both go through here.
     */
    fun withModifiers(mods: Long, action: () -> Unit) {
        val release = holdModifiers(mods)
        action()
        release()
    }

    /** The modifier keys of [mods] down now; the returned function lets them up again. */
    fun holdModifiers(mods: Long): () -> Unit {
        val keys = HidKeys.modifierKeys(mods)
        keys.forEach { (k, held) -> key(k, true, held) }
        return { for (i in keys.indices.reversed()) key(keys[i].first, false, if (i > 0) keys[i - 1].second else 0L) }
    }

    /** A shortcut as a keyboard sends it: its modifiers' own keys down, the key, then them up. */
    fun chord(usage: Int, mods: Long) = withModifiers(mods) {
        key(usage, true, mods)
        key(usage, false, mods)
    }

    fun gesture(name: String, fingers: Int) {
        if (!gesturesOn.value || (_windows.value?.gestures ?: 0) < 1) return
        // A gesture acts on the Mac's own screen: the Desktop first while a window streams.
        if (_windows.value?.active is StreamSource.Window) select(StreamSource.Desktop)
        session?.gesture(TrackpadGesture(name, fingers))
    }

    /**
     * The first session at home with a Mac just paired sets Sash's defaults on it: Balanced,
     * Standard, 60 fps and Prioritize Encoding Speed (the Mac's own are Retina, 120 fps and off).
     * Away from home the quality and resolution are the away pair, so it waits for a home session.
     */
    private val defaultsTried = mutableSetOf<String>()

    private fun sendDefaultsOnce(state: HostSettingsState) {
        val pin = sessionPin ?: return
        val mac = saved.byFingerprint(pin)?.takeIf { !it.defaultsSent } ?: return
        if (state.away?.thisConnectionAway == true || _settingsPending.value != null) return
        // Once per launch at most, so a Mac that refuses a value is not asked again and again.
        if (!defaultsTried.add(mac.macID)) return
        val s = state.settings
        val change = HostSettingsChange(
            bitrate = QualityPreset.BALANCED.bitrate.takeIf { it != s.bitrate },
            captureScale = 1.0.takeIf { it != s.captureScale },
            maxFPS = 60.takeIf { it != s.maxFPS },
            prioritizeSpeed = true.takeIf { it != s.prioritizeSpeed },
        )
        if (change != HostSettingsChange()) changeSettings(change)
        // A Mac that keeps no settings (the CLI) hears them again at its next session.
        if (state.persistent) saved.update(mac.macID) { it.copy(defaultsSent = true) }
    }

    fun changeSettings(change: HostSettingsChange) {
        val s = session ?: return
        val token = tokens.incrementAndGet()
        val c = change.copy(token = token)
        _settingsPending.value = c
        s.changeSettings(c)
        settingsTimeout?.let { main.removeCallbacks(it) }
        val r = Runnable {
            if (_settingsPending.value?.token == token) {
                _settingsPending.value = null
                _notices.tryEmit(str(R.string.mac_didnt_answer))
            }
        }
        settingsTimeout = r
        main.postDelayed(r, maxOf(4_000L, 4L * _stats.value.rttMs.coerceAtLeast(0)))
    }

    // --- menus

    private fun macMenu(m: MacMenu) {
        val cur = _menus.value
        when {
            m.menus != null -> _menus.value = MenuState(m.version, m.app, m.menus!!, m.note, m.stale == true)
            m.menu != null && m.items != null -> _menus.value = cur.copy(items = cur.items + (m.menu!! to m.items!!), stale = m.stale == true)
            m.pressed != null -> if (m.pressed == false) _notices.tryEmit(m.note ?: str(R.string.menu_not_chosen))
            m.note != null -> _notices.tryEmit(m.note!!)
        }
    }

    fun fetchMenu(item: MacMenuItem) {
        val v = _menus.value.version
        session?.fetchMenu(FetchMenu(version = v, id = item.id, title = item.title, token = tokens.incrementAndGet()))
    }

    fun pressMenu(item: MacMenuItem) {
        val v = _menus.value.version
        session?.pressMenuItem(PressMenuItem(version = v, id = item.id, title = item.title, token = tokens.incrementAndGet()))
    }

    // --- clipboard

    @Volatile var ignoreNextLocalClip: String? = null

    /** This device's clipboard text, if there is any new since the Mac's own. */
    fun localClipboardText(): String? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString() ?: return null
        if (t.isEmpty() || t == ignoreNextLocalClip) return null
        return t
    }

    val macTakesClipboard get() = (_windows.value?.clipboard ?: 0) >= 1 && shareClipboard.value

    fun pasteOnMac(text: String) {
        val s = session ?: return
        val bytes = text.toByteArray()
        if (bytes.size > Clipboard.MAX_TEXT_BYTES) { _clipboardNote.value = str(R.string.too_long); return }
        s.clipboard(Clipboard(text = text))
        val token = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val key = Base64URL.encode(token)
        val miss = Runnable { receipts.remove(key); _clipboardNote.value = str(R.string.didnt_reach_mac) }
        receipts[key] = miss
        main.postDelayed(miss, maxOf(4_000L, 4L * _stats.value.rttMs.coerceAtLeast(0)))
        s.receiptPing(token)
        ignoreNextLocalClip = text
    }

    fun clearClipboardNote() { _clipboardNote.value = null }

    /** Pair This Device… from a session at an open door: the Mac shows its code. */
    fun pairingWanted() = session?.pairingWanted()

    val isConnected get() = _phase.value == Phase.CONNECTED

    companion object {
        private const val NO_PICTURE_AFTER = 4_000L
        /** How near an edge (a fraction of the frame) a tap may be in the Dock. */
        private const val DOCK_BAND = 0.15
        /** How long the Dock takes to grow under a pointer that jumped into it. */
        private const val DOCK_SETTLE_MS = 200L
        private const val TEXT_HINT_MS = 3_000L
        private const val TAG = "Controller"
    }
}

private fun isIBeam(bmp: android.graphics.Bitmap, c: Blobs.Cursor): Boolean {
    val w = bmp.width
    val h = bmp.height
    if (w * h > 512 * 512 || c.width <= 0f || c.height <= 0f) return false
    val px = IntArray(w * h)
    bmp.getPixels(px, 0, w, 0, 0, w, h)
    val opaque = BooleanArray(px.size) { (px[it] ushr 24) > 128 }
    return CursorShape.isIBeam(opaque, w, h, c.hotX * w / c.width, c.hotY * h / c.height)
}

/** What groups a window with its app's others: the bundle ID, else the app's name. */
fun appKey(w: WindowInfo): String = w.bundleID.ifEmpty { w.appName }

/** [windows] in a steady order: by app as [appOrder] has them, each app's windows oldest first. */
fun orderedWindows(windows: List<WindowInfo>, appOrder: List<String>): List<WindowInfo> {
    val rank = appOrder.withIndex().associate { (i, k) -> k to i }
    return windows.sortedWith(compareBy({ rank[appKey(it)] ?: Int.MAX_VALUE }, { appKey(it) }, { it.id }))
}
