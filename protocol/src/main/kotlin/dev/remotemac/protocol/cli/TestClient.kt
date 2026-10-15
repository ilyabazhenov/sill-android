package dev.remotemac.protocol.cli

import dev.remotemac.protocol.Alpn
import dev.remotemac.protocol.Base64URL
import dev.remotemac.protocol.Blobs
import dev.remotemac.protocol.Clipboard
import dev.remotemac.protocol.FetchMenu
import dev.remotemac.protocol.Goodbye
import dev.remotemac.protocol.HostSettingsChange
import dev.remotemac.protocol.HostSettingsState
import dev.remotemac.protocol.Identity
import dev.remotemac.protocol.Input
import dev.remotemac.protocol.MacMenu
import dev.remotemac.protocol.PairLink
import dev.remotemac.protocol.PairingClient
import dev.remotemac.protocol.ParameterSets
import dev.remotemac.protocol.PointerAction
import dev.remotemac.protocol.Session
import dev.remotemac.protocol.SessionListener
import dev.remotemac.protocol.SignedMacInfo
import dev.remotemac.protocol.StreamSource
import dev.remotemac.protocol.Transport
import dev.remotemac.protocol.Viewport
import dev.remotemac.protocol.WindowList
import dev.remotemac.protocol.verified
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Speaks to a real Sill host as the Android app does, without Android. TEST ONLY: point it at a
 * `SillHost --synthetic` on this Mac's loopback, which posts no input.
 *
 *   TestClient HOST PORT [--identity DIR] [--link URL | --ask | --code DIGITS | --plain] [--seconds N]
 */
fun main(args: Array<String>) {
    val host = args[0]
    val port = args[1].toInt()
    fun opt(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args[it + 1] }
    val dir = File(opt("--identity") ?: "build/testclient")
    val seconds = opt("--seconds")?.toLong() ?: 6
    dir.mkdirs()
    val identity = loadIdentity(dir)
    println("device key ${Base64URL.encode(identity.fingerprint)}")
    val pinFile = File(dir, "mac.pin")

    when {
        opt("--link") != null -> {
            val link = PairLink.parse(opt("--link")!!) ?: error("not a pairing link")
            val out = PairingClient.pair(host, port, identity, PairingClient.Secret.Link(link.secret), link.fingerprint, "Test Android", "JVM")
            println("pairing: $out")
            if (out is PairingClient.Outcome.Paired) pinFile.writeText(Base64URL.encode(out.macFingerprint))
        }
        args.contains("--ask") -> println("ask: ${PairingClient.ask(host, port, identity, "Test Android", "JVM", false)}")
        opt("--code") != null -> {
            val out = PairingClient.pair(host, port, identity, PairingClient.Secret.Code(opt("--code")!!), null, "Test Android", "JVM")
            println("pairing: $out")
            if (out is PairingClient.Outcome.Paired) pinFile.writeText(Base64URL.encode(out.macFingerprint))
        }
    }
    if (args.contains("--pair-only")) return

    val transport = if (args.contains("--plain")) {
        Transport.plain(host, port)
    } else {
        val pin = if (pinFile.exists()) Base64URL.decode(pinFile.readText().trim()) else null
        Transport.tls(host, port, identity, Alpn.SESSION, pin)
    }
    println("connected tls=${transport.isTls} alpn=${transport.alpn} mac=${transport.peerFingerprint?.let { Base64URL.encode(it) }}")
    val done = CountDownLatch(1)
    var session: Session? = null
    var frames = 0
    var keyframes = 0
    var bytes = 0L
    var lastList: WindowList? = null
    session = Session(transport, "Test Android (JVM)", "1.0", object : SessionListener {
        override fun onWindowList(list: WindowList) {
            println("window list: ${list.macName} ${list.windows.size} windows active=${list.active} host=${list.hostVersion} gestures=${list.gestures} clipboard=${list.clipboard}")
            if (lastList == null) {
                session!!.viewport(Viewport(800.0, 500.0, null, true, 60))
                session!!.fetchMenu(FetchMenu(token = 1))
                if ((list.clipboard ?: 0) >= 1) session!!.clipboard(Clipboard())
                if (list.active == StreamSource.None) session!!.select(StreamSource.Desktop)
            }
            lastList = list
        }
        override fun onHostSettings(state: HostSettingsState) = println("settings: ${state.settings} stream=${state.stream} answering=${state.answering}")
        override fun onMacInfo(info: SignedMacInfo) = println("mac info verified=${info.verified()?.first}")
        override fun onAppList(apps: List<dev.remotemac.protocol.AppInfo>) = println("apps: ${apps.size}")
        override fun onCursorShape(c: Blobs.Cursor) = println("cursor ${c.width}x${c.height} png ${c.png.size}")
        override fun onParameterSets(ps: ParameterSets) = println("parameter sets: nal=${ps.nalUnitHeaderLength} ${ps.sets.map { it.size }}")
        override fun onFrame(accessUnit: ByteArray, keyframe: Boolean, timestamp: Double) {
            frames++; bytes += accessUnit.size; if (keyframe) keyframes++
        }
        override fun onMacMenu(m: MacMenu) = println("menus: ${m.app} ${m.menus?.mapNotNull { it.title }} note=${m.note}")
        override fun onGoodbye(g: Goodbye) = println("goodbye: $g")
        override fun onStats(fps: Int, frameAgeMs: Int, rttMs: Int) = println("stats: fps=$fps age=$frameAgeMs rtt=$rttMs")
        override fun onEnded(error: Throwable?) { println("ended: $error"); done.countDown() }
    })
    session.start()
    Thread.sleep(2500)
    session.pointerMove(0.5, 0.5)
    session.input(Input.pointer(PointerAction.LEFT_DOWN, 0.5, 0.5))
    session.input(Input.pointer(PointerAction.LEFT_UP, 0.5, 0.5))
    session.changeSettings(HostSettingsChange(token = 1, maxFPS = 60))
    done.await(seconds, TimeUnit.SECONDS)
    println("frames=$frames keyframes=$keyframes bytes=$bytes")
    session.close()
    if (frames == 0) System.exit(3)
}

private fun loadIdentity(dir: File): Identity {
    val priv = File(dir, "device.key")
    val pub = File(dir, "device.pub")
    if (priv.exists() && pub.exists()) return Identity.restore(priv.readBytes(), pub.readBytes())
    val id = Identity.generate()
    priv.writeBytes(id.exportPrivate())
    pub.writeBytes(id.publicKey.encoded)
    return id
}
