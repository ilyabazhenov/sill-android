package dev.sash.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
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
 *
 * A browse whose first queries go unanswered (Wi-Fi just back from power saving, a router that
 * drops some multicast) hears again only as Android's queries back off, so while nothing is listed
 * the browse starts over every few seconds, and it starts over when a Wi-Fi or Ethernet network
 * comes or goes.
 */
class Discovery(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
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
    /** Between [start] and [stop]: the app wants Macs listed. */
    private var running = false
    private var retries = 0
    /** Counts browses: a callback from one before the current one is late, and drops its result. */
    private var generation = 0
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var watchingSince = 0L

    private val retry = Runnable {
        if (running && found.isEmpty()) { retries++; browseAgain() }
    }
    private val networkChanged = Runnable {
        if (running) { retries = 0; browseAgain() }
    }

    fun start() {
        if (running) return
        running = true
        retries = 0
        browse()
        watchNetworks()
    }

    fun stop() {
        running = false
        main.removeCallbacks(retry)
        main.removeCallbacks(networkChanged)
        networkCallback?.let { try { connectivity.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        networkCallback = null
        halt()
    }

    private fun browse() {
        if (listener != null) return
        // Each callback checks it still belongs to the browse running now: a browse stopped and
        // started again must not have the old one's late failure or results land on the new one.
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = main.post { if (listener === this) _failed.value = false }.let {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery failed: $errorCode")
                main.post {
                    if (listener !== this) return@post
                    listener = null
                    _failed.value = true
                    scheduleRetry()
                }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) = main.post { if (listener === this) resolve(info) }.let {}
            override fun onServiceLost(info: NsdServiceInfo) = main.post { if (listener === this) lost(info.serviceName) }.let {}
        }
        listener = l
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            Log.w(TAG, "discoverServices", e)
            listener = null
            _failed.value = true
        }
        scheduleRetry()
    }

    /** Stops the browse and forgets what it listed; [running] is left as it is. */
    private fun halt() {
        listener?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
        listener = null
        if (Build.VERSION.SDK_INT >= 34) {
            for (cb in callbacks.values) try { nsd.unregisterServiceInfoCallback(cb as NsdManager.ServiceInfoCallback) } catch (_: Exception) {}
        }
        callbacks.clear()
        queue.clear()
        // A resolve still running keeps [resolving] until it answers: the system resolver takes one
        // at a time, and the next browse's resolve has to wait for it.
        generation++
        found.clear()
        publish()
    }

    private fun browseAgain() {
        halt()
        browse()
    }

    /** Starts the browse over after a while, unless a Mac is listed by then. */
    private fun scheduleRetry() {
        main.removeCallbacks(retry)
        if (running) main.postDelayed(retry, RETRY_MS[minOf(retries, RETRY_MS.lastIndex)])
    }

    /** Wi-Fi or Ethernet coming or going (another network, Wi-Fi back on): the browse starts over. */
    private fun watchNetworks() {
        if (networkCallback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = changed()
            override fun onLost(network: Network) = changed()
            private fun changed() {
                main.post {
                    // Registering reports the networks there already; only later changes count.
                    if (SystemClock.uptimeMillis() - watchingSince < 1_500) return@post
                    main.removeCallbacks(networkChanged)
                    main.postDelayed(networkChanged, 1_000)
                }
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        watchingSince = SystemClock.uptimeMillis()
        try {
            connectivity.registerNetworkCallback(request, cb)
            networkCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "registerNetworkCallback", e)
        }
    }

    private fun resolve(info: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= 34) {
            if (callbacks.containsKey(info.serviceName)) return
            val gen = generation
            val cb = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    main.post { if (gen == generation) callbacks.remove(info.serviceName) }
                }
                override fun onServiceUpdated(si: NsdServiceInfo) = main.post { if (gen == generation) update(si, si.hostAddresses) }.let {}
                override fun onServiceLost() = main.post { if (gen == generation) lost(info.serviceName) }.let {}
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
        val gen = generation
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) = main.post { resolving = false; next() }.let {}
            override fun onServiceResolved(si: NsdServiceInfo) = main.post {
                resolving = false
                // From a browse since stopped: the resolver is free again, the result is stale.
                if (gen == generation) update(si, listOfNotNull(si.host))
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
        if (found.isEmpty()) scheduleRetry()
    }

    private fun publish() {
        _macs.value = found.values.sortedBy { it.serviceName.lowercase() }
    }

    companion object {
        const val SERVICE_TYPE = "_sill._tcp"
        /** How long an empty browse runs before it starts over: soon at first, then every 20 s. */
        private val RETRY_MS = longArrayOf(4_000, 6_000, 10_000, 15_000, 20_000)
        private const val TAG = "Discovery"
    }
}
