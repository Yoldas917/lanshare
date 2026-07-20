package com.example.lanshare

import android.content.Context

/**
 * Kullanici ayarlari (SharedPreferences 'lanshare'). Yayin parametreleri artik
 * sabit degil: fps (30/48/60), kalite (720p/900p/1080p) ve bitrate (auto/sabit)
 * kullanici tarafindan secilir; PublisherActivity degerleri buradan okur.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("lanshare", Context.MODE_PRIVATE)

    var fps: Int
        get() = prefs.getInt(KEY_FPS, VIDEO_FPS)
        set(v) = prefs.edit().putInt(KEY_FPS, v).apply()

    /** "720p" | "900p" | "1080p" */
    var quality: String
        get() = prefs.getString(KEY_QUALITY, QUALITY_720) ?: QUALITY_720
        set(v) = prefs.edit().putString(KEY_QUALITY, v).apply()

    val width: Int
        get() = when (quality) {
            QUALITY_1080 -> 1920
            QUALITY_900 -> 1600
            else -> 1280
        }

    val height: Int
        get() = when (quality) {
            QUALITY_1080 -> 1080
            QUALITY_900 -> 900
            else -> 720
        }

    /** 0 = otomatik (kaliteye gore), aksi halde sabit bps */
    var bitrateBps: Int
        get() = prefs.getInt(KEY_BITRATE, 0)
        set(v) = prefs.edit().putInt(KEY_BITRATE, v).apply()

    val maxBitrateBps: Int
        get() = if (bitrateBps > 0) bitrateBps else when (quality) {
            QUALITY_1080 -> 12_000_000
            QUALITY_900 -> 10_000_000
            else -> 8_000_000
        }

    val minBitrateBps: Int
        get() = maxBitrateBps * 2 / 3

    /** Izleyici stats overlay'i acik mi (varsayilan kapali) */
    var overlayVisible: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY, false)
        set(v) = prefs.edit().putBoolean(KEY_OVERLAY, v).apply()

    var roomId: String?
        get() = prefs.getString("roomId", null)
        set(v) = prefs.edit().putString("roomId", v).apply()

    var server: String?
        get() = prefs.getString("server", null)
        set(v) = prefs.edit().putString("server", v).apply()

    companion object {
        const val QUALITY_720 = "720p"
        const val QUALITY_900 = "900p"
        const val QUALITY_1080 = "1080p"
        private const val KEY_FPS = "target_fps"
        private const val KEY_QUALITY = "quality"
        private const val KEY_BITRATE = "bitrate_bps"
        private const val KEY_OVERLAY = "overlay_visible"
    }
}
