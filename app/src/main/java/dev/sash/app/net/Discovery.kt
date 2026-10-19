package dev.sash.app.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.sash.protocol.HomeDoor
import dev.sash.protocol.RecognitionTag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executors

/** One Mac Bonjour lists on this network: `_sill._tcp`, its address and its TXT record. */
data class FoundMac(
    val serviceName: String,
    val addresses: List<InetAddress>,
    val port: Int,
    val door: HomeDoor,
    /** The TXT `r` recognition tag, which only a paired device can resolve. */
    val tag: String?,
) {
    /** IPv4 first: a phone's IPv6 link-local scope is the likeliest to go wrong. */
    val bestAddress: InetAddress? get() = addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull()
}

/**
 * NsdManager browsing for `_sill._tcp`, each service resolved for its address, port and TXT record.
 * From Android 14 a service-info callback follows the record as it changes; before, services are
 * resolved one at a time (resolveService refuses a second while one runs).
 */
class Discovery(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val _macs = MutableStateFlow<List<FoundMac>>(emptyList())
    val macs: StateFlow<List<FoundMac>> = _macs
    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed

    private val found = LinkedHashMap<String, FoundMac>()
    private val callbacks = HashMap<String, Any>()
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) { _failed.value = false }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery failed: $errorCode")
                _failed.value = true
                main.post { listener = null }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) = main.post { resolve(info) }.let {}
            override fun onServiceLost(info: NsdServiceInfo) = main.post { lost(info.serviceName) }.let {}
        }
        listener = l
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            Log.w(TAG, "discoverServices", e)
            listener = null
            _failed.value = true
        }
    }

    fun stop() {
        listener?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
        listener = null
        if (Build.VERSION.SDK_INT >= 34) {
            for (cb in callbacks.values) try { nsd.unregisterServiceInfoCallback(cb as NsdManager.ServiceInfoCallback) } catch (_: Exception) {}
        }
        callbacks.clear()
        queue.clear()
        resolving = false
        found.clear()
        publish()
    }

    /** A fresh browse: Android, like iOS, can leave a browser stale after a long background stay. */
    fun restart() {
        stop()
        start()
    }

    private fun resolve(info: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= 34) {
            if (callbacks.containsKey(info.serviceName)) return
            val cb = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    main.post { callbacks.remove(info.serviceName) }
                }
                override fun onServiceUpdated(si: NsdServiceInfo) = main.post { update(si, si.hostAddresses) }.let {}
                override fun onServiceLost() = main.post { lost(info.serviceName) }.let {}
                override fun onServiceInfoCallbackUnregistered() {}
            }
            callbacks[info.serviceName] = cb
            try {
                nsd.registerServiceInfoCallback(info, executor, cb)
            } catch (e: Exception) {
                callbacks.remove(info.serviceName)
            }
        } else {
            queue.addLast(info)
            next()
        }
    }

    @Suppress("DEPRECATION")
    private fun next() {
        if (resolving) return
        val info = queue.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) = main.post { resolving = false; next() }.let {}
            override fun onServiceResolved(si: NsdServiceInfo) = main.post {
                resolving = false
                update(si, listOfNotNull(si.host))
                next()
            }.let {}
        })
    }

    private fun update(si: NsdServiceInfo, addresses: List<InetAddress>) {
        if (addresses.isEmpty() || si.port <= 0) return
        val txt: Map<String, ByteArray?> = si.attributes ?: emptyMap()
        val tag = txt.entries.firstOrNull { it.key.equals(RecognitionTag.TXT_KEY, true) }?.value?.toString(Charsets.UTF_8)
        found[si.serviceName] = FoundMac(si.serviceName, addresses, si.port, HomeDoor.fromTxt(txt), tag)
        publish()
    }

    private fun lost(name: String) {
        if (Build.VERSION.SDK_INT >= 34) {
            callbacks.remove(name)?.let { try { nsd.unregisterServiceInfoCallback(it as NsdManager.ServiceInfoCallback) } catch (_: Exception) {} }
        }
        if (found.remove(name) != null) publish()
    }

    private fun publish() {
        _macs.value = found.values.sortedBy { it.serviceName.lowercase() }
    }

    companion object {
        const val SERVICE_TYPE = "_sill._tcp"
        private const val TAG = "Discovery"
    }
}
