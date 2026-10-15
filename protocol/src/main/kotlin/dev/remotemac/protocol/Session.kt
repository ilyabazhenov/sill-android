package dev.remotemac.protocol

import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** What a session hears from the Mac. Called on the session's reader thread. */
interface SessionListener {
    fun onWindowList(list: WindowList) {}
    fun onHostSettings(state: HostSettingsState) {}
    fun onMacInfo(info: SignedMacInfo) {}
    fun onThumbnail(t: Blobs.Thumbnail) {}
    fun onAppIcon(icon: Blobs.Icon) {}
    fun onAppList(apps: List<AppInfo>) {}
    fun onCursorShape(c: Blobs.Cursor) {}
    fun onParameterSets(ps: ParameterSets) {}
    fun onFrame(accessUnit: ByteArray, keyframe: Boolean, timestamp: Double) {}
    fun onMacPointer(p: MacPointer) {}
    fun onMacMenu(m: MacMenu) {}
    fun onClipboard(c: Clipboard) {}
    fun onGoodbye(g: Goodbye) {}
    fun onClipboardReceipt(token: ByteArray) {}
    fun onStats(fps: Int, frameAgeMs: Int, rttMs: Int) {}
    /** The session is over: [error] null for a clean end (the Mac closed it, or [Session.close]). */
    fun onEnded(error: Throwable?) {}
}

/**
 * One session connection (`sill/1`, or a plain door): the hello first, then the catalog, frames and
 * input. Pings every 0.25 s keep the RTT measured and the host from evicting us; stats go out once
 * a second; a link silent for max(6 s, 4 × the worst round trip) counts as lost.
 */
class Session(
    val transport: Transport,
    private val deviceName: String,
    private val appVersion: String,
    private val listener: SessionListener,
) {
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "sill-writer").apply { isDaemon = true } }
    private val timers = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "sill-timers").apply { isDaemon = true } }
    private val closed = AtomicBoolean(false)
    private val started = System.nanoTime()
    @Volatile private var lastReceived = System.nanoTime()
    @Volatile private var worstRttMs = 0.0
    private var tasks = ArrayList<ScheduledFuture<*>>()

    // One-second window of samples, guarded by `this`.
    private var frames = 0
    private val ages = ArrayList<Int>()
    private val rtts = ArrayList<Int>()

    // Pointer moves coalesce to one per 8 ms; anything else flushes the pending one first.
    private val pointerLock = Any()
    private var pendingMove: ByteArray? = null
    private var moveScheduled = false

    private val inputs = AtomicInteger(0)

    /** Input messages (kind 8) sent so far; kind 26's `seen` is judged against it. */
    val inputsSent: Int get() = inputs.get()

    fun start() {
        send(Kind.HELLO, encodePayload(Hello(appVersion, null, 1, deviceName)))
        Thread({ readLoop() }, "sill-reader").apply { isDaemon = true }.start()
        tasks += timers.scheduleAtFixedRate({ ping() }, 0, 250, TimeUnit.MILLISECONDS)
        tasks += timers.scheduleAtFixedRate({ reportStats() }, 1, 1, TimeUnit.SECONDS)
        tasks += timers.scheduleAtFixedRate({ checkLiveness() }, 1000, 250, TimeUnit.MILLISECONDS)
    }

    fun close() = end(null)

    val isOpen get() = !closed.get()

    // ------------------------------------------------------------------------------------------
    // Sending

    fun send(kind: Int, payload: ByteArray = ByteArray(0)) {
        if (closed.get()) return
        val m = Message.of(kind, payload)
        // The session can end on another thread between the check above and here: a closed
        // session drops what is sent to it rather than throwing at the caller (often the UI).
        try {
            writer.execute {
                try { transport.send(m) } catch (e: Exception) { end(e) }
            }
        } catch (_: RejectedExecutionException) {}
    }

    fun select(source: StreamSource) = send(Kind.SELECT_SOURCE, encodeSource(source))
    fun launchApp(bundleID: String) = send(Kind.LAUNCH_APP, encodePayload(LaunchApp(bundleID)))
    fun windowCommand(id: Long, action: String) = send(Kind.WINDOW_COMMAND, encodePayload(WindowCommand(id, action)))
    fun viewport(v: Viewport) = send(Kind.VIEWPORT, encodePayload(v))
    fun changeSettings(c: HostSettingsChange) = send(Kind.CHANGE_SETTINGS, encodePayload(c))
    fun fetchMenu(f: FetchMenu) = send(Kind.FETCH_MENU, encodePayload(f))
    fun pressMenuItem(p: PressMenuItem) = send(Kind.PRESS_MENU_ITEM, encodePayload(p))
    fun gesture(g: TrackpadGesture) = send(Kind.GESTURE, encodePayload(g))
    fun clipboard(c: Clipboard) = send(Kind.CLIPBOARD, encodePayload(c))
    fun pairingWanted() = send(Kind.PAIRING_WANTED)

    /** Any input but a pointer move: the pending move goes first, so order is kept. */
    fun input(payload: ByteArray) {
        flushMove()
        inputs.incrementAndGet()
        send(Kind.INPUT, payload)
    }

    fun pointerMove(x: Double, y: Double) {
        if (closed.get()) return
        val p = Input.pointer(PointerAction.MOVE, x, y)
        synchronized(pointerLock) {
            pendingMove = p
            if (moveScheduled) return
            moveScheduled = true
        }
        try {
            timers.schedule({ flushMove() }, 8, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            synchronized(pointerLock) { moveScheduled = false; pendingMove = null }
        }
    }

    private fun flushMove() {
        val p: ByteArray
        synchronized(pointerLock) {
            moveScheduled = false
            p = pendingMove ?: return
            pendingMove = null
        }
        inputs.incrementAndGet()
        send(Kind.INPUT, p)
    }

    /** A ping whose 16-byte payload comes back as a receipt (the clipboard's "sent"). */
    fun receiptPing(token: ByteArray) {
        require(token.size == 16)
        send(Kind.PING, token)
    }

    private fun ping() {
        val b = ByteBuffer.allocate(8).putDouble(monotonic()).array()
        send(Kind.PING, b)
    }

    // ------------------------------------------------------------------------------------------
    // Receiving

    private fun readLoop() {
        val reader = MessageReader(transport.input)
        reader.onBytes = { lastReceived = System.nanoTime() }
        try {
            while (!closed.get()) {
                val m = reader.read() ?: break
                handle(m)
            }
            end(null)
        } catch (e: Exception) {
            end(if (closed.get()) null else e)
        }
    }

    private fun handle(m: Message) {
        val p = m.payload
        when (m.kind) {
            Kind.WINDOW_LIST -> decodePayload<WindowList>(p)?.let(listener::onWindowList)
            Kind.HOST_SETTINGS -> decodePayload<HostSettingsState>(p)?.let(listener::onHostSettings)
            Kind.MAC_INFO -> decodePayload<SignedMacInfo>(p)?.let(listener::onMacInfo)
            Kind.THUMBNAIL -> Blobs.thumbnail(p)?.let(listener::onThumbnail)
            Kind.APP_ICON -> Blobs.icon(p)?.let(listener::onAppIcon)
            Kind.APP_LIST -> decodePayload<List<AppInfo>>(p)?.let(listener::onAppList)
            Kind.CURSOR_SHAPE -> Blobs.cursor(p)?.let(listener::onCursorShape)
            Kind.PARAMETER_SETS -> ParameterSets.decode(p)?.let(listener::onParameterSets)
            Kind.FRAME -> {
                val age = ((System.currentTimeMillis() / 1000.0 - m.timestamp) * 1000).toInt().coerceAtLeast(0)
                synchronized(this) { frames++; ages += age }
                listener.onFrame(p, m.isKeyframe, m.timestamp)
            }
            Kind.PONG -> when (p.size) {
                8 -> {
                    val sent = ByteBuffer.wrap(p).double
                    val rtt = ((monotonic() - sent) * 1000)
                    if (rtt >= 0 && rtt < 60_000) {
                        synchronized(this) { rtts += rtt.toInt() }
                        if (rtt > worstRttMs) worstRttMs = rtt else worstRttMs = worstRttMs * 0.95 + rtt * 0.05
                    }
                }
                16 -> listener.onClipboardReceipt(p)
            }
            Kind.MAC_POINTER -> decodePayload<MacPointer>(p)?.let(listener::onMacPointer)
            Kind.MAC_MENU -> decodePayload<MacMenu>(p)?.let(listener::onMacMenu)
            Kind.CLIPBOARD -> decodePayload<Clipboard>(p)?.let(listener::onClipboard)
            Kind.GOODBYE -> decodePayload<Goodbye>(p)?.let(listener::onGoodbye)
            else -> {} // ticks and kinds this build does not know
        }
    }

    private fun reportStats() {
        val f: Int
        val a: List<Int>
        val r: List<Int>
        synchronized(this) {
            f = frames; a = ages.toList(); r = rtts.toList()
            frames = 0; ages.clear(); rtts.clear()
        }
        fun median(l: List<Int>) = if (l.isEmpty()) -1 else l.sorted()[l.size / 2]
        val stats = ClientStats(f, median(a), median(r), deviceName, a.maxOrNull() ?: -1, r.maxOrNull() ?: -1)
        send(Kind.CLIENT_STATS, encodePayload(stats))
        listener.onStats(f, stats.frameAgeMs, stats.rttMs)
    }

    private fun checkLiveness() {
        val silentMs = (System.nanoTime() - lastReceived) / 1_000_000
        val limit = maxOf(6000.0, 4 * worstRttMs)
        if (silentMs > limit) end(java.io.IOException("connection silent for ${silentMs / 1000} s"))
    }

    private fun end(error: Throwable?) {
        if (!closed.compareAndSet(false, true)) return
        tasks.forEach { it.cancel(false) }
        timers.shutdown()
        writer.shutdown()
        transport.close()
        listener.onEnded(error)
    }

    private fun monotonic() = (System.nanoTime() - started) / 1e9
}
