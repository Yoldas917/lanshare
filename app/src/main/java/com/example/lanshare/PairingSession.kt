package com.example.lanshare

import android.content.Context
import android.os.Build
import android.util.Log

/** NSD ile kesfedilip cozumlenen bir cihaz */
data class NearbyDevice(val name: String, val host: String, val port: Int)

/**
 * Eslestirme oturumu: gomulu signaling sunucusu + NSD kaydi/kesfi + bulunan
 * cihaz listesi. Activity yasam dongusunden bagimsiz singleton — yayin/izleme
 * suresince ayakta kalir; "Gorunurlugu kapat" veya uygulama cikisi ile durur.
 */
object PairingSession {

    var server: EmbeddedSignalingServer? = null
        private set
    private var nsd: NsdHelper? = null

    /** Kesfedilen cihazlar (yalnizca main thread'den erisilir; NsdHelper main'e post eder) */
    val devices = LinkedHashMap<String, NearbyDevice>()

    /** Liste degisince UI'yi tazelemek icin (MainActivity atar, main thread) */
    @Volatile
    var onDevicesChanged: (() -> Unit)? = null

    /** Uzak cihaz odaya join olunca (davet) — MainActivity atar */
    @Volatile
    var onInvite: ((remoteRole: String) -> Unit)? = null

    val isActive: Boolean get() = server != null

    val port: Int get() = server?.boundPort ?: -1

    private val nsdListener = object : NsdHelper.Listener {
        override fun onDeviceFound(name: String, host: String, port: Int) {
            devices[name] = NearbyDevice(name, host, port)
            onDevicesChanged?.invoke()
        }

        override fun onDeviceLost(name: String) {
            devices.remove(name)
            onDevicesChanged?.invoke()
        }
    }

    /** Bloklayabilir (sunucu porta baglanana kadar ~<1 sn) — worker thread'de cagir. */
    fun start(context: Context): Boolean {
        if (isActive) return true
        val srv = EmbeddedSignalingServer { role -> onInvite?.invoke(role) }
        return try {
            srv.start() // asenkron: kendi selector thread'ini acar
            // onStart() tamamlanip GERCEK ephemeral port belli olana kadar bekle
            if (!srv.awaitStart(5000)) {
                srv.shutdown()
                Log.e(TAG, "[Pairing] sunucu porta baglanamadi (onStart zaman asimi)")
                false
            } else {
                server = srv
                // NSD kaydi yalnizca gecerli (gercek) portla yapilir
                nsd = NsdHelper(context, nsdListener).also {
                    it.start(srv.boundPort, "LanShare " + Build.MODEL)
                }
                Log.i(TAG, "[Pairing] aktif: port=${srv.boundPort}")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "[Pairing] baslatilamadi: ${e.message}")
            try { srv.shutdown() } catch (_: Exception) {}
            false
        }
    }

    fun stop() {
        nsd?.stop()
        nsd = null
        server?.shutdown()
        server = null
        devices.clear()
        onDevicesChanged?.invoke()
        onInvite = null
        Log.i(TAG, "[Pairing] durduruldu")
    }
}
