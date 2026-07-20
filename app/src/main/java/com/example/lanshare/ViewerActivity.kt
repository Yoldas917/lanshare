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

    private lateinit var settings: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Izleme surerken TV ekrani uykuya/karartmaya gecmesin
        // (activity kapaninca flag otomatik kalkar; wakelock gerekmez)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        settings = SettingsStore(this)
        binding.disconnectButton.setOnClickListener {
            // Temizlik onDestroy'da (signaling/PC/renderer)
            finish()
        }
        // Stats overlay: varsayilan kapali; dokunma veya TV kumandasi OK ile ac/kapa.
        // Root view odaklanabilir -> D-pad center click olarak gelir.
        binding.statsText.visibility =
            if (settings.overlayVisible) android.view.View.VISIBLE else android.view.View.GONE
        binding.root.isClickable = true
        binding.root.isFocusable = true
        binding.root.setOnClickListener {
            val show = binding.statsText.visibility != android.view.View.VISIBLE
            binding.statsText.visibility =
                if (show) android.view.View.VISIBLE else android.view.View.GONE
            settings.overlayVisible = show
        }

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
        status(getString(R.string.st_connecting, "ws://$host:$port", roomId))

        // Canli istatistik overlay'ini baslat
        mainHandler.postDelayed(statsPoller, 1500)
    }

    // Status metni 5 sn sonra otomatik gizlenir; yeni durumda tekrar gorunur
    private val hideStatusRunnable = Runnable {
        binding.statusText.visibility = android.view.View.GONE
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
        // A/V senkron gostergesi (ses track'i varsa)
        session.pollAvSyncStats { sync ->
            if (sync != null) {
                Log.d(TAG, "[Viewer][sync] audioJit=%.0fms videoJit=%.0fms fark=%+.0fms"
                    .format(sync.audioJitterMs, sync.videoJitterMs, sync.diffMs))
            }
        }
    }

    // --- WebRtcSession.Callbacks ---
    private val sessionCallbacks = object : WebRtcSession.Callbacks {
        override fun onLocalIceCandidate(candidate: IceCandidate) {
            signaling.sendIce(candidate)
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            status(getString(R.string.st_ice, state.toString()))
        }

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            status(getString(R.string.st_pc, state.toString()))
        }

        override fun onRemoteVideoTrack(track: VideoTrack) {
            runOnUiThread {
                if (!sinkAttached) {
                    track.addSink(binding.remoteView)
                    sinkAttached = true
                    status(getString(R.string.vst_video_connected))
                    Log.d(TAG, "[Viewer] remote video sink baglandi")
                }
            }
        }
    }

    // --- SignalingClient.Listener ---
    override fun onOpen() {}

    override fun onJoined(peers: Int) {
        status(getString(R.string.st_joined, peers))
    }

    override fun onPeerJoined(role: String?) {
        status(getString(R.string.vst_pub_ready))
    }

    override fun onPeerLeft() {
        status(getString(R.string.vst_pub_left))
    }

    override fun onRemoteOffer(sdp: SessionDescription) {
        status(getString(R.string.vst_offer_received))
        session.setRemoteDescription(sdp) {
            session.createAnswer { answer ->
                session.setLocalDescription(answer) {
                    signaling.sendAnswer(answer)
                    status(getString(R.string.vst_answer_sent))
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
        status(getString(R.string.st_error, message))
    }

    override fun onClosed() {
        status(getString(R.string.st_sig_closed))
    }

    private fun status(msg: String) {
        Log.d(TAG, "[Viewer] $msg")
        runOnUiThread {
            binding.statusText.text = msg
            binding.statusText.visibility = android.view.View.VISIBLE
            mainHandler.removeCallbacks(hideStatusRunnable)
            mainHandler.postDelayed(hideStatusRunnable, 5000)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(hideStatusRunnable)
        mainHandler.removeCallbacks(statsPoller)
        if (::signaling.isInitialized) signaling.close()
        if (rendererInitialized) binding.remoteView.release()
        session.dispose()
        eglBase.release()
    }
}
