package com.example.lanshare

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.util.Log
import java.nio.ByteBuffer

/**
 * Cihaz IC sesini (uygulamalarin oynattigi ses) MediaProjection uzerinden
 * AudioPlaybackCaptureConfiguration ile yakalar (Android 10+). Yakalanan PCM,
 * WebRTC AudioDeviceModule'un kayit callback'ine (AudioRecordDataCallback)
 * beslenmek uzere bir ring buffer'da tutulur.
 *
 * Not: Yalnizca allowAudioPlaybackCapture izni veren uygulamalarin sesi gelir;
 * korumali (DRM) icerik sessiz kalir — beklenen davranis, DRM asma yoktur.
 */
class InternalAudioCapture(
    private val mediaProjection: MediaProjection,
    private val sampleRate: Int = AUDIO_SAMPLE_RATE,
    private val channels: Int = AUDIO_CHANNELS
) {
    private var audioRecord: AudioRecord? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    // Gecikme butcesi: ring'de en fazla ~60ms birikmesine izin verilir. Fazlasi
    // ATILIR (en eski veri). Boylece ses hatti WebRTC'ye girmeden once sabit bir
    // gecikme biriktiremez (onceki 1 sn'lik ring, sesin kalici geride kalmasina
    // yol aciyordu: ADM drenaja baslamadan ring doluyor, birikinti hic erimiyordu).
    private val bytesPerMs = sampleRate * channels * 2 / 1000
    private val maxBacklogBytes = 60 * bytesPerMs          // ~60 ms tavan
    private val ringSize = 240 * bytesPerMs                // fiziksel ~240 ms
    private val ring = ByteArray(ringSize)
    private var writePos = 0
    private var readPos = 0
    private var available = 0
    private var droppedBytes = 0L
    private val lock = Object()

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val channelMask =
            if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()

        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT
        )
        // AudioRecord ic tamponu kucuk tut (min'in altina inme); okumayi 10ms'lik
        // parcalarla yap — buyuk parca (onceki ~200ms) parca basina ekstra ses
        // gecikmesi demekti.
        val chunkBytes = 10 * bytesPerMs
        val bufSize = maxOf(minBuf, 4 * chunkBytes)

        return try {
            val record = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufSize)
                .setAudioPlaybackCaptureConfig(config)
                .build()
            audioRecord = record
            record.startRecording()
            running = true
            thread = Thread {
                val tmp = ByteArray(chunkBytes)
                while (running) {
                    val n = record.read(tmp, 0, tmp.size)
                    if (n > 0) writeToRing(tmp, n)
                }
            }.apply { name = "InternalAudioCapture"; start() }
            Log.d(TAG, "InternalAudioCapture basladi (${sampleRate}Hz, ch=$channels, " +
                "chunk=10ms, backlogTavan=60ms)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "InternalAudioCapture baslatilamadi: ${e.message}")
            false
        }
    }

    private fun writeToRing(data: ByteArray, len: Int) {
        synchronized(lock) {
            for (i in 0 until len) {
                ring[writePos] = data[i]
                writePos = (writePos + 1) % ringSize
                if (available < ringSize) {
                    available++
                } else {
                    readPos = (readPos + 1) % ringSize
                }
            }
            // Gecikme tavani: 60ms'den fazlasi birikmisse en eskiyi at.
            // (ADM heniz drenaja baslamadiysa ya da geride kaldiysa ses,
            // videoya gore sabit sekilde gecikmesin.)
            if (available > maxBacklogBytes) {
                val drop = available - maxBacklogBytes
                readPos = (readPos + drop) % ringSize
                available = maxBacklogBytes
                droppedBytes += drop
            }
        }
    }

    /** Ring'de bekleyen (henuz WebRTC'ye verilmemis) ses miktari, ms. */
    fun backlogMs(): Int = synchronized(lock) { available / bytesPerMs }

    /** Gecikme tavani nedeniyle atilan toplam sure, ms (teshis). */
    fun droppedMs(): Long = synchronized(lock) { droppedBytes / bytesPerMs }

    /**
     * WebRTC ADM kayit callback'inden cagrilir. dst buffer'i tamamen ic ses
     * PCM'i ile doldurur; yeterli veri yoksa kalan kismi sessizlikle tamamlar.
     */
    fun fill(dst: ByteBuffer) {
        val cap = dst.capacity()
        dst.position(0)
        dst.limit(cap)
        synchronized(lock) {
            var written = 0
            while (written < cap && available > 0) {
                dst.put(ring[readPos])
                readPos = (readPos + 1) % ringSize
                available--
                written++
            }
            while (written < cap) {
                dst.put(0)
                written++
            }
        }
        dst.position(0)
    }

    fun stop() {
        running = false
        try { thread?.join(200) } catch (_: Exception) {}
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        Log.d(TAG, "InternalAudioCapture durdu")
    }
}
