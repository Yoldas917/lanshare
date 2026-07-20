package com.example.lanshare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * mediaProjection tipinde foreground service. Android 14+ ekran yakalamanin
 * calismasi icin, MediaProjection token'i kullanilmadan ONCE bu servisin
 * foreground olarak (mediaProjection tipiyle) baslamis olmasi gerekir.
 */
class ScreenCaptureService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val withMic = intent?.getBooleanExtra(EXTRA_WITH_MIC, false) ?: false
        // startForeground SENKRON cagrilir; donduugunde FGS aktiftir.
        startForegroundInternal(withMic)
        Log.i(TAG, "FGS aktif (type=mediaProjection${if (withMic) "|microphone" else ""})")
        // Android 14: MediaProjection ancak FGS aktifken olusturulabilir.
        // Bekleyen activity'ye "artik guvenli" sinyali ver (ayni process, main thread).
        readyListener?.invoke()
        readyListener = null // tek atimlik
        return START_NOT_STICKY
    }

    private fun startForegroundInternal(withMic: Boolean) {
        val channelId = "screen_capture"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Ekran Paylasimi",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Ekran paylasiliyor")
            .setContentText("LAN ekran paylasimi yayinda")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (withMic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIF_ID, notification, type)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        private const val NOTIF_ID = 1001

        /**
         * startForeground tamamlandiginda (FGS gercekten aktif) bir kez cagrilir.
         * PublisherActivity, startCapture'i bu sinyalden SONRA tetikler.
         */
        @Volatile
        var readyListener: (() -> Unit)? = null
    }
}
