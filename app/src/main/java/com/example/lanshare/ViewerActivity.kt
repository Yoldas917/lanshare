package com.example.lanshare

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.example.lanshare.databinding.ActivityViewerBinding
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.RendererCommon
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import kotlin.math.roundToInt

/**
 * Izleyici: signaling'e "viewer" olarak katilir, gelen offer'a answer uretir,
 * remote video track'i SurfaceViewRenderer uzerinde gosterir. (Android TV dahil.)
 */
class ViewerActivity : AppCompatActivity(), SignalingClient.Listener {

    private lateinit var binding: ActivityViewerBinding
    private lateinit var eglBase: EglBase
    private lateinit var session: WebRtcSession
    private lateinit var signaling: SignalingClient

    private var rendererInitialized = false
    private var sinkAttached = false

    private lateinit var roomId: String
    private lateinit var host: String
    private var port = 0

    // Stats overlay (cozunurluk/fps/bitrate) icin
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastBytes = -1L
    private var lastTsUs = 0.0
    private val statsPoller = object : Runnable {
        override fun run() {
            pollStats()
            mainHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        roomId = intent.getStringExtra(EXTRA_ROOM) ?: "demo"
        host = intent.getStringExtra(EXTRA_HOST) ?: "192.168.1.165"
        port = intent.getIntExtra(EXTRA_PORT, 8080)

        eglBase = EglBase.create()
        binding.remoteView.init(eglBase.eglBaseContext, null)
        binding.remoteView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        binding.remoteView.setEnableHardwareScaler(true)
        rendererInitialized = true

        session = WebRtcSession(this, eglBase, sessionCallbacks)
        session.createPeerConnection()

        signaling = SignalingClient(host, port, roomId, "viewer", this)
        signaling.connect()
        status("Signaling'e baglaniliyor: ws://$host:$port (room=$roomId)")

        // Canli istatistik overlay'ini baslat
        mainHandler.postDelayed(statsPoller, 1500)
    }

    private fun pollStats() {
        session.pollInboundVideoStats { s ->
            if (s == null) return@pollInboundVideoStats
            var mbps = 0.0
            if (lastBytes >= 0 && s.timestampUs > lastTsUs) {
                val bits = (s.bytesReceived - lastBytes) * 8.0
                val secs = (s.timestampUs - lastTsUs) / 1_000_000.0
                if (secs > 0) mbps = bits / secs / 1_000_000.0
            }
            lastBytes = s.bytesReceived
            lastTsUs = s.timestampUs
            val codec = s.codec?.substringAfter('/') ?: "?"
            val text = "${s.width}x${s.height} · ${s.fps.roundToInt()}fps\n" +
                "%.1f Mbps · %s".format(mbps, codec)
            Log.d(TAG, "[Viewer][stats] $text".replace("\n", "  "))
            runOnUiThread { binding.statsText.text = text }
        }
    }

    // --- WebRtcSession.Callbacks ---
    private val sessionCallbacks = object : WebRtcSession.Callbacks {
        override fun onLocalIceCandidate(candidate: IceCandidate) {
            signaling.sendIce(candidate)
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            status("ICE: $state")
        }

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            status("PeerConnection: $state")
        }

        override fun onRemoteVideoTrack(track: VideoTrack) {
            runOnUiThread {
                if (!sinkAttached) {
                    track.addSink(binding.remoteView)
                    sinkAttached = true
                    status("Remote video baglandi -> render ediliyor")
                    Log.d(TAG, "[Viewer] remote video sink baglandi")
                }
            }
        }
    }

    // --- SignalingClient.Listener ---
    override fun onOpen() {}

    override fun onJoined(peers: Int) {
        status("Odaya katildim. Odadaki diger peer: $peers")
    }

    override fun onPeerJoined(role: String?) {
        status("Yayinci hazir (role=$role), offer bekleniyor...")
    }

    override fun onPeerLeft() {
        status("Yayinci ayrildi")
    }

    override fun onRemoteOffer(sdp: SessionDescription) {
        status("Offer alindi -> answer uretiliyor")
        session.setRemoteDescription(sdp) {
            session.createAnswer { answer ->
                session.setLocalDescription(answer) {
                    signaling.sendAnswer(answer)
                    status("Answer gonderildi")
                }
            }
        }
    }

    override fun onRemoteAnswer(sdp: SessionDescription) {
        // Izleyici answer uretir, beklemez
    }

    override fun onRemoteIce(candidate: IceCandidate) {
        session.addRemoteIceCandidate(candidate)
    }

    override fun onError(message: String) {
        status("HATA: $message")
    }

    override fun onClosed() {
        status("Signaling kapandi")
    }

    private fun status(msg: String) {
        Log.d(TAG, "[Viewer] $msg")
        runOnUiThread { binding.statusText.text = msg }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(statsPoller)
        if (::signaling.isInitialized) signaling.close()
        if (rendererInitialized) binding.remoteView.release()
        session.dispose()
        eglBase.release()
    }
}
