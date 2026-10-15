package dev.remotemac.app.stream

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
import dev.remotemac.app.data.IdentityStore
import dev.remotemac.app.data.SavedMac
import dev.remotemac.app.data.SavedMacs
import dev.remotemac.app.net.Discovery
import dev.remotemac.app.net.FoundMac
import dev.remotemac.protocol.Alpn
import dev.remotemac.protocol.AppInfo
import dev.remotemac.protocol.Base64URL
import dev.remotemac.protocol.Blobs
import dev.remotemac.protocol.Clipboard
import dev.remotemac.protocol.FetchMenu
import dev.remotemac.protocol.Goodbye
import dev.remotemac.protocol.HomeDoor
import dev.remotemac.protocol.HostSettingsChange
import dev.remotemac.protocol.HostSettingsState
import dev.remotemac.protocol.Input
import dev.remotemac.protocol.MacMenu
import dev.remotemac.protocol.MacMenuItem
import dev.remotemac.protocol.MacPointer
import dev.remotemac.protocol.PairLink
import dev.remotemac.protocol.PairingClient
import dev.remotemac.protocol.ParameterSets
import dev.remotemac.protocol.ParsedAddress
import dev.remotemac.protocol.PinRefused
import dev.remotemac.protocol.PressMenuItem
import dev.remotemac.protocol.Session
import dev.remotemac.protocol.SessionListener
import dev.remotemac.protocol.SignedMacInfo
import dev.remotemac.protocol.StreamSource
import dev.remotemac.protocol.TrackpadGesture
import dev.remotemac.protocol.Transport
import dev.remotemac.protocol.Viewport
import dev.remotemac.protocol.WindowCommand
import dev.remotemac.protocol.WindowList
import dev.remotemac.protocol.verified
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

data class CursorImage(val image: ImageBitmap, val hotX: Float, val hotY: Float, val width: Float, val height: Float)

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

    private var session: Session? = null
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
    }

    private fun prefs() = context.getSharedPreferences("app", Context.MODE_PRIVATE)

    fun setShareClipboard(on: Boolean) {
        shareClipboard.value = on
        prefs().edit().putBoolean("shareClipboard", on).apply()
        if ((_windows.value?.clipboard ?: 0) >= 1) session?.clipboard(if (on) Clipboard() else Clipboard(stop = true))
    }

    fun setGestures(on: Boolean) {
        gesturesOn.value = on
        prefs().edit().putBoolean("gestures", on).apply()
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
        _status.value = if (reconnecting) "Reconnecting to ${t.name}…" else "Connecting to ${t.name}…"
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
                val s = saved.all.value.firstOrNull { !it.revoked && it.lastWorked == "${t.host}:${t.port}" }
                val tr = Transport.tls(t.host, t.port, id, Alpn.SESSION, s?.pin)
                Triple(tr, tr.peerFingerprint, s ?: tr.peerFingerprint?.let { saved.byFingerprint(it) })
            }
            is Target.Remote -> {
                val tr = raceRemote(t.mac, id)
                Triple(tr, t.mac.pin, t.mac)
            }
        }
    }

    /** Every way to the Mac from afar, a second apart; the first handshake wins. */
    private suspend fun raceRemote(mac: SavedMac, id: dev.remotemac.protocol.Identity): Transport {
        val candidates = remoteCandidates(mac)
        if (candidates.isEmpty()) error("no address for ${mac.name}: pair it at home with Remote Access on, or add its address")
        val winner = CompletableDeferred<Pair<Transport, String>>()
        val errors = ArrayList<Throwable>()
        val jobs = candidates.mapIndexed { i, (host, port) ->
            scope.launch(Dispatchers.IO) {
                delay(i * 1000L)
                if (winner.isCompleted) return@launch
                try {
                    val tr = Transport.tls(host, port, id, Alpn.SESSION, mac.pin)
                    if (!winner.complete(tr to "$host:$port")) tr.close()
                } catch (e: Exception) {
                    synchronized(errors) { errors += e }
                    if (errors.size == candidates.size) winner.completeExceptionally(e)
                }
            }
        }
        val got = withTimeoutOrNull(20_000) { winner.await() }
        jobs.forEach { it.cancel() }
        got ?: throw java.io.IOException("${mac.name} didn’t answer")
        saved.update(mac.macID) { it.copy(lastWorked = got.second) }
        return got.first
    }

    private fun remoteCandidates(mac: SavedMac): List<Pair<String, Int>> {
        val out = LinkedHashSet<Pair<String, Int>>()
        mac.lastWorked?.let { ParsedAddress.parse(it) }?.let { out += it.host to (it.port ?: mac.remotePort) }
        val rank = mapOf("vpn" to 0, "lan" to 1, "internet" to 2)
        mac.addresses.sortedBy { rank[it.kind] ?: 3 }.forEach { out += it.host to (it.port ?: mac.remotePort) }
        mac.typedAddresses.mapNotNull { ParsedAddress.parse(it) }.forEach { out += it.host to (it.port ?: mac.remotePort) }
        return out.toList()
    }

    private fun dialFailed(t: Target, e: Throwable, reconnecting: Boolean) {
        Log.w(TAG, "dial ${t.name} failed", e)
        if (e is PinRefused && t is Target.Row && t.saved != null) {
            // The Mac was set up again (a new key): pair once more.
            _status.value = "${t.name} has a new key since this device paired. Tap it to pair again."
            saved.update(t.saved.macID) { it.copy(revoked = true) }
            _phase.value = Phase.IDLE
            return
        }
        if (reconnecting) { scheduleReconnect(); return }
        _phase.value = Phase.IDLE
        _status.value = when {
            e is java.net.SocketTimeoutException || e is java.net.ConnectException -> "${t.name} didn’t answer."
            e is javax.net.ssl.SSLException && t is Target.Row && t.saved == null -> "${t.name} asks devices to pair. Tap it to pair."
            e is javax.net.ssl.SSLException -> "${t.name} refused this device. It may have been removed on the Mac: pair it again."
            else -> e.message ?: "Couldn’t connect to ${t.name}."
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
            out.onFailure { _pairing.value = prompt.copy(busy = false, error = "${row.serviceName} didn’t answer.") }
            out.onSuccess { o ->
                when (o) {
                    is PairingClient.Outcome.Refused -> _pairing.value = when (o.reason) {
                        "shown" -> prompt.copy(codeShown = true, busy = false, askedKey = o.macFingerprint)
                        "openOnMac" -> prompt.copy(busy = false, error = "On the Mac, choose Pair iPhone or iPad… in Sill’s menu, then enter its code here.", codeShown = true)
                        "locked" -> prompt.copy(busy = false, error = "Unlock ${row.serviceName}, then try again.")
                        "busy" -> prompt.copy(busy = false, error = "${row.serviceName} is busy. Try again in ${(o.retryAfter ?: 5.0).toInt()} s.")
                        else -> prompt.copy(busy = false, error = o.message ?: "${row.serviceName} didn’t show a code (${o.reason}).")
                    }
                    is PairingClient.Outcome.Paired -> paired(o, row, null)
                    PairingClient.Outcome.BadProof -> _pairing.value = prompt.copy(busy = false, error = "That isn’t the Mac it says it is.")
                }
            }
        }
    }

    /** The 12 digits from the Mac's window, after an ask or for a typed address from afar. */
    fun submitCode(code: String) {
        val p = _pairing.value ?: return
        val check = dev.remotemac.protocol.PairingCode.check(code)
        if (check !is dev.remotemac.protocol.PairingCode.Check.Ok) {
            _pairing.value = p.copy(error = if (check is dev.remotemac.protocol.PairingCode.Check.Typo) "That code has a typo. Check it against your Mac." else "A code has 12 digits.")
            return
        }
        _pairing.value = p.copy(busy = true, error = null)
        scope.launch {
            val out = withContext(Dispatchers.IO) {
                runCatching {
                    PairingClient.pair(p.host, p.port, identities.identity(), PairingClient.Secret.Code(check.code), p.askedKey, shortName, Build.MODEL)
                }
            }
            handlePairOutcome(out, p, typedAddress = if (p.row == null) "${p.host}:${p.port}" else null)
        }
    }

    /** Pairing from afar: the Mac's address and the code from its window. */
    fun startRemotePairing(address: String) {
        val a = ParsedAddress.parse(address) ?: run { _status.value = "That isn’t an address."; return }
        _pairing.value = PairingPrompt(a.host, codeShown = true, host = a.host, port = a.port ?: 7455)
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
            _pairing.value = PairingPrompt(link.name, codeShown = true, error = "Couldn’t reach ${link.name}. Be on the same network, or turn on Remote Access on the Mac.")
            last?.exceptionOrNull()?.let { Log.w(TAG, "pair link", it) }
        }
    }

    private fun handlePairOutcome(out: Result<PairingClient.Outcome>, p: PairingPrompt, typedAddress: String?) {
        out.onFailure { e ->
            Log.w(TAG, "pairing", e)
            _pairing.value = p.copy(busy = false, error = "${p.macName} didn’t answer.")
        }
        out.onSuccess { o ->
            when (o) {
                is PairingClient.Outcome.Paired -> paired(o, p.row, typedAddress, p)
                is PairingClient.Outcome.Refused -> _pairing.value = p.copy(
                    busy = false,
                    error = when (o.reason) {
                        "code" -> "That code didn’t match. ${o.triesLeft?.let { "$it tries left." } ?: ""}".trim()
                        "expired" -> "The code expired. Show a new one on the Mac."
                        "closed", "stopped" -> "The Mac isn’t showing a code now."
                        "busy" -> "Wait ${(o.retryAfter ?: 2.0).toInt()} s and try again."
                        else -> o.message ?: "The Mac said no (${o.reason})."
                    },
                )
                PairingClient.Outcome.BadProof -> _pairing.value = p.copy(busy = false, error = "That isn’t the Mac the code is from.")
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
        )
        saved.save(mac)
        _pairing.value = null
        _notices.tryEmit("Paired with ${o.name}.")
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
        session?.close()
        sessionPin = pin
        sessionSaved = mac
        firstList = true
        lastGoodbye = null
        _route.value = when (t) {
            is Target.Remote -> remoteRoute(transport)
            else -> if (transport.isTls) null else "unencrypted"
        }
        val s = Session(transport, deviceName, APP_VERSION, listener)
        session = s
        s.start()
        // A session counts as connected only at its first window list (within 10 s).
        main.postDelayed({
            if (session === s && firstList && s.isOpen) {
                s.close()
                _status.value = "${t.name} didn’t send its windows."
            }
        }, 10_000)
    }

    private fun remoteRoute(t: Transport): String {
        val a = t.socket.inetAddress?.hostAddress ?: return "from afar"
        return if (a.startsWith("100.") || a.startsWith("fd7a:115c:a1e0")) "through Tailscale" else "from afar"
    }

    private val listener = object : SessionListener {
        override fun onWindowList(list: WindowList) = main.post { windowList(list) }.let {}

        override fun onHostSettings(state: HostSettingsState) = main.post {
            _settings.value = state
            val pending = _settingsPending.value
            if (pending != null && state.answering != null && state.answering!! >= (pending.token ?: 0)) {
                val refused = (pending.bitrate != null && pending.bitrate != state.settings.bitrate) ||
                    (pending.maxFPS != null && pending.maxFPS != state.settings.maxFPS) ||
                    (pending.captureScale != null && pending.captureScale != state.settings.captureScale)
                if (refused) _notices.tryEmit("The Mac kept its setting.")
                _settingsPending.value = null
            }
        }.let {}

        override fun onMacInfo(info: SignedMacInfo) {
            val (mi, fp) = info.verified() ?: return
            // Only the key this very connection proved names this Mac.
            if (sessionPin == null || !fp.contentEquals(sessionPin)) return
            saved.byFingerprint(fp)?.let { saved.learn(it.macID, mi) }
        }

        override fun onThumbnail(t: Blobs.Thumbnail) {
            val bmp = BitmapFactory.decodeByteArray(t.jpeg, 0, t.jpeg.size) ?: return
            _thumbnails.value = _thumbnails.value + (t.windowID to bmp.asImageBitmap())
        }

        override fun onAppIcon(icon: Blobs.Icon) {
            val bmp = BitmapFactory.decodeByteArray(icon.png, 0, icon.png.size) ?: return
            _icons.value = _icons.value + (icon.bundleID to bmp.asImageBitmap())
        }

        override fun onAppList(apps: List<AppInfo>) { _apps.value = apps.sortedBy { it.name.lowercase() } }

        override fun onCursorShape(c: Blobs.Cursor) {
            val bmp = BitmapFactory.decodeByteArray(c.png, 0, c.png.size) ?: return
            _cursor.value = CursorImage(bmp.asImageBitmap(), c.hotX, c.hotY, c.width, c.height)
        }

        override fun onParameterSets(ps: ParameterSets) { decoder.parameterSets(ps) }

        override fun onFrame(accessUnit: ByteArray, keyframe: Boolean, timestamp: Double) { decoder.frame(accessUnit, keyframe) }

        override fun onMacPointer(p: MacPointer) {
            val s = session ?: return
            if ((p.seen ?: 0) < s.inputsSent) return // built before the Mac read our latest input
            val x = p.x; val y = p.y
            _macPointer.value = if (p.inside == true && x != null && y != null) x to y else null
        }

        override fun onMacMenu(m: MacMenu) = main.post { macMenu(m) }.let {}

        override fun onClipboard(c: Clipboard) {
            val text = c.text ?: return
            if (!shareClipboard.value) return
            main.post {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                ignoreNextLocalClip = text
                cm.setPrimaryClip(ClipData.newPlainText("Mac", text))
                _notices.tryEmit("Copied on the Mac")
            }
        }

        override fun onClipboardReceipt(token: ByteArray) = main.post {
            receipts.remove(Base64URL.encode(token))?.let { main.removeCallbacks(it); _clipboardNote.value = "Sent to the Mac’s clipboard. Press ⌘V there." }
        }.let {}

        override fun onGoodbye(g: Goodbye) { lastGoodbye = g }

        override fun onStats(fps: Int, frameAgeMs: Int, rttMs: Int) { _stats.value = LinkStats(fps, frameAgeMs, rttMs) }

        override fun onEnded(error: Throwable?) = main.post { sessionEnded(error) }.let {}
    }

    private fun windowList(list: WindowList) {
        val s = session ?: return
        _windows.value = list
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
                        s.select(StreamSource.Desktop)
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
        _status.value = words ?: "Reconnecting to ${t.name}…"
        _phase.value = Phase.RECONNECTING
        scheduleReconnect(if (g?.reason == "quit") 3_000 else 0)
    }

    private fun goodbyeWords(g: Goodbye, name: String): String = g.message?.let { dev.remotemac.protocol.SafeText.label(it, 300) }?.takeIf { it.isNotEmpty() }
        ?: when (g.reason) {
            "quit" -> "$name quit Sill."
            "removed" -> "$name removed this device. Tap it to pair again."
            "pairingRequired" -> "$name now asks devices to pair. Tap it to pair this device."
            "remoteOff" -> "Remote Access is off on $name."
            "internetOff" -> "$name no longer allows connections from the internet."
            "busy" -> "$name has too many devices connected."
            "update" -> "Update this app to keep using $name."
            else -> "$name ended the session."
        }

    private fun scheduleReconnect(firstDelay: Long = 0) {
        reconnectJob?.cancel()
        val t = _target.value ?: return
        reconnectJob = scope.launch {
            val delays = listOf(0L, 2_000, 4_000, 8_000, 10_000, 10_000, 20_000, 30_000, 30_000, 30_000, 30_000, 30_000)
            if (reconnectAttempts >= delays.size) {
                _phase.value = Phase.IDLE
                _status.value = "Stopped trying to reach ${t.name}. Tap it to try again."
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
                mac != null && (mac.addresses.isNotEmpty() || mac.typedAddresses.isNotEmpty() || mac.lastWorked != null) -> Target.Remote(mac)
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
        _videoSize.value = null
        _route.value = null
        _stats.value = LinkStats()
    }

    fun disconnect() {
        userEnded = true
        reconnectJob?.cancel()
        dialJob?.cancel()
        session?.close()
        session = null
        resetCatalog()
        _phase.value = Phase.IDLE
        _status.value = null
    }

    /** Back in front after the background: a dead browser and a dead link are found again. */
    fun cameForward() {
        discovery.restart()
        if (_phase.value == Phase.RECONNECTING) { reconnectAttempts = 0; scheduleReconnect() }
    }

    // =============================================================================================
    // What the stream screen sends

    fun select(source: StreamSource) {
        lastUserPick = System.currentTimeMillis()
        session?.select(source)
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

    fun pointer(action: String, x: Double, y: Double) {
        _macPointer.value = null
        session?.input(Input.pointer(action, x, y))
    }

    fun move(x: Double, y: Double) {
        _macPointer.value = null
        session?.pointerMove(x, y)
    }

    fun scroll(x: Double, y: Double, dx: Double, dy: Double) = session?.input(Input.scroll(x, y, dx, dy))
    fun scrollPhase(phase: String, x: Double, y: Double) = session?.input(Input.scrollGesture(phase, x, y))
    fun text(t: String) = session?.input(Input.text(t))
    fun key(usage: Int, down: Boolean, mods: Long) = session?.input(Input.key(usage, down, mods))

    /** A shortcut as a keyboard sends it: its modifiers' own keys down, the key, then them up. */
    fun chord(usage: Int, mods: Long) {
        val keys = HidKeys.modifierKeys(mods)
        keys.forEach { (k, held) -> key(k, true, held) }
        key(usage, true, mods)
        key(usage, false, mods)
        keys.reversed().forEachIndexed { i, (k, _) ->
            val remaining = keys.dropLast(i + 1).fold(0L) { acc, p -> acc or (p.second) }
            key(k, false, remaining)
        }
    }

    fun gesture(name: String, fingers: Int) {
        if (!gesturesOn.value || (_windows.value?.gestures ?: 0) < 1) return
        // A gesture acts on the Mac's own screen: the Desktop first while a window streams.
        if (_windows.value?.active is StreamSource.Window) select(StreamSource.Desktop)
        session?.gesture(TrackpadGesture(name, fingers))
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
                _notices.tryEmit("The Mac didn’t answer. Try again.")
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
            m.pressed != null -> if (m.pressed == false) _notices.tryEmit(m.note ?: "The Mac didn’t choose that item.")
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
        if (bytes.size > Clipboard.MAX_TEXT_BYTES) { _clipboardNote.value = "That’s too long to send."; return }
        s.clipboard(Clipboard(text = text))
        val token = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val key = Base64URL.encode(token)
        val miss = Runnable { receipts.remove(key); _clipboardNote.value = "Didn’t reach the Mac. Try again." }
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
        const val APP_VERSION = "1.0"
        private const val TAG = "Controller"
    }
}
