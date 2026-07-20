package com.example.lanshare

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.ArrayDeque

/**
 * Android NSD (mDNS) yardimcisi: "_lanshare._tcp" servisini kaydeder (gorunur ol)
 * ve ayni tipteki diger cihazlari kesfeder. mDNS paketleri icin MulticastLock
 * alinir (CHANGE_WIFI_MULTICAST_STATE). Kendi kaydimiz listeden filtrelenir.
 * Cozumleme (resolve) NSD'de seri calismak zorunda -> kuyruklanir.
 * Tum listener callback'leri main thread'e aktarilir.
 */
class NsdHelper(
    context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onDeviceFound(name: String, host: String, port: Int)
        fun onDeviceLost(name: String)
    }

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var multicastLock: WifiManager.MulticastLock? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    @Volatile
    private var registeredName: String? = null

    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private val queueLock = Any()

    fun start(port: Int, deviceName: String) {
        // mDNS multicast paketlerinin islenmesi icin kilit
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("lanshare-mdns").apply {
            setReferenceCounted(false)
            acquire()
        }

        // 1) Kayit (gorunur ol)
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = deviceName
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                // Sistem ad cakismasinda yeniden adlandirabilir -> gercek adi sakla
                registeredName = info.serviceName
                Log.i(TAG, "[NSD] kaydedildi: '${info.serviceName}' port=$port")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "[NSD] kayit basarisiz: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.d(TAG, "[NSD] kayit silindi")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "[NSD] kayit silme basarisiz: $errorCode")
            }
        }
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)

        // 2) Kesif
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "[NSD] kesif basladi")
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceType.startsWith(SERVICE_TYPE.trimEnd('.'))) return
                if (info.serviceName == registeredName) return // kendimiz
                Log.d(TAG, "[NSD] bulundu: '${info.serviceName}' -> cozumleme kuyrugu")
                enqueueResolve(info)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                Log.d(TAG, "[NSD] kayboldu: '${info.serviceName}'")
                mainHandler.post { listener.onDeviceLost(info.serviceName) }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "[NSD] kesif durdu")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "[NSD] kesif baslatilamadi: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "[NSD] kesif durdurulamadi: $errorCode")
            }
        }
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    private fun enqueueResolve(info: NsdServiceInfo) {
        synchronized(queueLock) {
            resolveQueue.add(info)
            if (!resolving) resolveNext()
        }
    }

    private fun resolveNext() {
        val next: NsdServiceInfo?
        synchronized(queueLock) {
            next = resolveQueue.poll()
            resolving = next != null
        }
        if (next == null) return
        @Suppress("DEPRECATION")
        nsdManager.resolveService(next, object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = info.host?.hostAddress
                val port = info.port
                Log.i(TAG, "[NSD] cozuldu: '${info.serviceName}' -> $host:$port")
                if (host != null) {
                    mainHandler.post { listener.onDeviceFound(info.serviceName, host, port) }
                }
                resolveNext()
            }

            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "[NSD] cozumleme basarisiz (${info.serviceName}): $errorCode")
                resolveNext()
            }
        })
    }

    fun stop() {
        try {
            registrationListener?.let { nsdManager.unregisterService(it) }
        } catch (_: Exception) {
        }
        try {
            discoveryListener?.let { nsdManager.stopServiceDiscovery(it) }
        } catch (_: Exception) {
        }
        registrationListener = null
        discoveryListener = null
        registeredName = null
        synchronized(queueLock) {
            resolveQueue.clear()
            resolving = false
        }
        multicastLock?.release()
        multicastLock = null
        Log.d(TAG, "[NSD] durduruldu")
    }

    companion object {
        const val SERVICE_TYPE = "_lanshare._tcp."
    }
}
