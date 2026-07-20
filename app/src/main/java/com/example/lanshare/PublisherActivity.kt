package com.example.lanshare

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.lanshare.databinding.ActivityPublisherBinding
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.RtpParameters
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Yayinci: ekrani MediaProjection ile yakalar, donanim H264 ile WebRTC uzerinden
 * gonderir. Signaling'e "publisher" olarak katilir; karsi peer katilinca offer uretir.
 */
class PublisherActivity : AppCompatActivity(), SignalingClient.Listener {

    private lateinit var binding: ActivityPublisherBinding
    private lateinit var eglBase: EglBase
    private lateinit var session: WebRtcSession
    private lateinit var signaling: SignalingClient

    private var screenCapturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    // Ses (opsiyonel)
    private var withAudio = false
    private var internalAudio: InternalAudioCapture? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null

    private var preferHevc = false
    private var showLiveStats = false

    // Kullanici ayarlarindan gelen yayin parametreleri (SettingsStore)
    private var vw = VIDEO_WIDTH
    private var vh = VIDEO_HEIGHT
    private var vfps = VIDEO_FPS
    private var vMaxBps = VIDEO_MAX_BITRATE_BPS
    private var vMinBps = VIDEO_MIN_BITRATE_BPS

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var roomId: String
    private lateinit var host: String
    private var port = 0

    // Ekran yakalama izni sonucu
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            status(getString(R.string.st_perm_granted))
            val data = result.data!!
            // Android 14: MediaProjection olusturulmadan ONCE mediaProjection tipli
            // FGS AKTIF olmali. startForegroundService async'tir (Binder IPC ->
            // onStartCommand main thread'e sonradan siralanir); tek bir post yarisi
            // kaybedebilir -> SecurityException. Bu yuzden capture, servisin
            // startForeground'u SENKRON tamamladiktan sonra verdigi sinyalle baslar.
            ScreenCaptureService.readyListener = {
                mainHandler.removeCallbacks(fgsTimeoutRunnable)
                Log.d(TAG, "FGS mediaProjection aktif -> startCapture cagriliyor")
                beginCapture(data)
            }
            // Emniyet: sinyal 4 sn'de gelmezse sessiz takilma olmasin
            mainHandler.postDelayed(fgsTimeoutRunnable, 4000)
            val svc = Intent(this, ScreenCaptureService::class.java)
                .putExtra(EXTRA_WITH_MIC, withAudio)
            startForegroundService(svc)
        } else {
            status(getString(R.string.st_perm_denied))
            Log.e(TAG, "MediaProjection izni reddedildi")
        }
    }

    private val fgsTimeoutRunnable = Runnable {
        ScreenCaptureService.readyListener = null
        status(getString(R.string.st_fgs_timeout))
        Log.e(TAG, "FGS ready sinyali zaman asimi; startCapture cagrilmadi")
    }

    // Runtime izinleri (POST_NOTIFICATIONS + ses aciksa RECORD_AUDIO); sonra ekran yakalama
    private val runtimePermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (withAudio && grants[Manifest.permission.RECORD_AUDIO] == false) {
            withAudio = false
            status(getString(R.string.st_mic_denied))
        }
        requestScreenCapture()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPublisherBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Yayin surerken ekran kararmasin/uyumasin (activity kapaninca otomatik kalkar)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        roomId = intent.getStringExtra(EXTRA_ROOM) ?: "demo"
        host = intent.getStringExtra(EXTRA_HOST) ?: "192.168.1.165"
        port = intent.getIntExtra(EXTRA_PORT, 8080)
        withAudio = intent.getBooleanExtra(EXTRA_AUDIO, false)

        // Kullanicinin sectigi kalite/fps/bitrate
        val settings = SettingsStore(this)
        vw = settings.width
        vh = settings.height
        vfps = settings.fps
        vMaxBps = settings.maxBitrateBps
        vMinBps = settings.minBitrateBps
        showLiveStats = settings.showLiveStats
        Log.d(TAG, "Ayarlar: ${vw}x$vh@$vfps, bitrate max=$vMaxBps min=$vMinBps " +
            "liveStats=$showLiveStats")

        binding.disconnectButton.setOnClickListener {
            // Tum temizlik onDestroy'da: capture/ses/FGS/signaling/PeerConnection
            finish()
        }

        eglBase = EglBase.create()
        session = WebRtcSession(this, eglBase, sessionCallbacks)

        // Gerekli runtime izinlerini topla
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (withAudio &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.RECORD_AUDIO
        }

        if (needed.isNotEmpty()) {
            runtimePermLauncher.launch(needed.toTypedArray())
        } else {
            requestScreenCapture()
        }
    }

    private fun requestScreenCapture() {
        status(getString(R.string.st_perm_request))
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun beginCapture(permissionData: Intent) {
        // Codec karari: cihazin donanim H264 encoder'i secilen formati
        // destekliyor mu? Desteklemiyorsa HEVC'e dus (varsa). Karari logla.
        preferHevc = decidePreferHevc()

        status(getString(R.string.st_capture_start, vw, vh, vfps,
            if (preferHevc) "HEVC" else "H264"))

        val capturer = ScreenCapturerAndroid(permissionData, object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection durdu")
            }
        })
        screenCapturer = capturer

        val source = session.factory.createVideoSource(/* isScreencast = */ true)
        videoSource = source

        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        capturer.initialize(surfaceTextureHelper, applicationContext, source.capturerObserver)
        capturer.startCapture(vw, vh, vfps)
        // NOT: adaptOutputFormat KALDIRILDI — VideoAdapter'in fps sinirlayicisi,
        // jitter'li capture zaman damgalarinda 60fps girisi ~30'a dusurebiliyordu.
        // Ekran yakalama zaten istenen boyutta uretir; ek sinirlayiciya gerek yok.

        // Panel tazeleme hizini logla: hedefin altindaysa kaynak o fps'i uretemez
        val refresh = windowManager.defaultDisplay.refreshRate
        if (refresh < (vfps - 1)) {
            Log.w(TAG, "[fps] panel ${refresh}Hz -> capture capped at ${refresh.toInt()} by source")
        } else {
            Log.d(TAG, "[fps] panel refresh: ${refresh}Hz (hedef $vfps fps icin yeterli)")
        }

        val track = session.factory.createVideoTrack("video0", source)
        videoTrack = track

        session.createPeerConnection()
        val pc = session.peerConnection()
        val sender = pc?.addTrack(track, listOf("stream0"))

        // Sender parametreleri: 1080p60 hedefi, ~12 Mbps, LAN'da akicilik oncelikli.
        sender?.let {
            val params = it.parameters
            if (params.encodings.isNotEmpty()) {
                val enc = params.encodings[0]
                enc.maxBitrateBps = vMaxBps
                enc.minBitrateBps = vMinBps
                enc.maxFramerate = vfps
            }
            // Secilen fps sabit kalsin: kisitlanirsa cozunurlukten fedakarlik et
            params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
            it.parameters = params
            Log.d(TAG, "Sender: maxBitrate=$vMaxBps min=$vMinBps " +
                "fps=$vfps degradation=MAINTAIN_FRAMERATE")
        }

        // fps zinciri teshis loggeri: capture fps + encoder fps + kisit nedeni (1 sn)
        startFpsDiagnostics(capturer)

        // Codec tercihini transceiver'a uygula (H264/HEVC one)
        session.applyVideoCodecPreferences(preferHevc)

        // Opsiyonel ic ses: ScreenCapturer'in olusturdugu MediaProjection'i paylas
        if (withAudio) {
            startInternalAudio(capturer, pc)
        }

        // Signaling'e baglan
        signaling = SignalingClient(host, port, roomId, "publisher", this)
        signaling.connect()
        status(getString(R.string.st_connecting, "ws://$host:$port", roomId))
    }

    private fun startInternalAudio(capturer: ScreenCapturerAndroid, pc: PeerConnection?) {
        val projection = capturer.mediaProjection
        if (projection == null) {
            status(getString(R.string.st_no_projection))
            return
        }
        val capture = InternalAudioCapture(projection)
        if (!capture.start()) {
            status(getString(R.string.st_audio_failed))
            return
        }
        internalAudio = capture
        session.internalAudioCapture = capture

        // KRITIK: mic'i sustur. ADM mic orneklerini sifirlar, AudioBufferCallback
        // sifirlanmis buffer'in YERINE ic ses PCM'ini yazar. Mic asla gonderilmez.
        session.muteMicrophone(true)
        Log.i(TAG, "audio source = INTERNAL_PLAYBACK (mic muted)")

        val aSource = session.factory.createAudioSource(MediaConstraints())
        val aTrack = session.factory.createAudioTrack("audio0", aSource)
        audioSource = aSource
        audioTrack = aTrack
        pc?.addTrack(aTrack, listOf("stream0"))
        Log.d(TAG, "Ic ses track eklendi")
        status(getString(R.string.st_audio_active))
    }

    // --- fps zinciri teshisi: capture fps / encode fps / kisit nedeni ---
    private var lastCapturedFrames = 0L
    private var lastFramesEncoded = 0L
    private var lastFramesSent = 0L
    private var lastEncodeTimeS = 0.0
    private var lastBytesSent = 0L
    private var fpsCapturerRef: ScreenCapturerAndroid? = null
    private val fpsDiagRunnable = object : Runnable {
        override fun run() {
            val cap = fpsCapturerRef ?: return
            val captured = cap.numCapturedFrames
            val capFps = captured - lastCapturedFrames
            lastCapturedFrames = captured

            val audioInfo = internalAudio?.let {
                " audioBacklog=${it.backlogMs()}ms(drop=${it.droppedMs()}ms)"
            } ?: ""
            session.pollOutboundVideoStats { s ->
                if (s != null) {
                    val encDelta = s.framesEncoded - lastFramesEncoded
                    val sentDelta = s.framesSent - lastFramesSent
                    lastFramesEncoded = s.framesEncoded
                    lastFramesSent = s.framesSent
                    val encMs = if (encDelta > 0)
                        "%.1f".format((s.totalEncodeTimeS - lastEncodeTimeS) * 1000.0 / encDelta)
                    else "-"
                    lastEncodeTimeS = s.totalEncodeTimeS
                    val mbps = (s.bytesSent - lastBytesSent) * 8.0 / 1_000_000.0
                    lastBytesSent = s.bytesSent
                    Log.d(TAG, "[fps] capture=$capFps encode=$encDelta sent=$sentDelta " +
                        "statsFps=${s.fps} ${s.width}x${s.height} " +
                        "limit=${s.qualityLimitation} target=${s.targetBitrateBps / 1000}kbps " +
                        "encMs=$encMs enc=${s.encoderImpl}$audioInfo")
                    if (showLiveStats) {
                        val line = "${s.width}x${s.height} · ${s.fps.toInt()} fps · " +
                            "%.1f Mbps".format(mbps)
                        runOnUiThread {
                            binding.liveStatsText.visibility = android.view.View.VISIBLE
                            binding.liveStatsText.text = line
                        }
                    }
                    if (s.encoderImpl?.contains("SW", ignoreCase = true) == true ||
                        s.encoderImpl?.contains("libaom") == true ||
                        s.encoderImpl?.contains("OpenH264", ignoreCase = true) == true
                    ) {
                        Log.w(TAG, "[fps] YAZILIM encoder'a dusulmus: ${s.encoderImpl} — " +
                            "1080p60 icin donanim gerekli (HEVC fallback dusunulebilir)")
                    }
                    if (capFps in 1..(vfps - 15)) {
                        Log.w(TAG, "[fps] capture ~$capFps: kaynak $vfps uretmiyor " +
                            "(statik ekran veya panel siniri olabilir)")
                    }
                } else {
                    Log.d(TAG, "[fps] capture=$capFps (henuz baglanti yok)")
                }
            }
            mainHandler.postDelayed(this, 1000)
        }
    }

    private fun startFpsDiagnostics(capturer: ScreenCapturerAndroid) {
        fpsCapturerRef = capturer
        lastCapturedFrames = 0
        mainHandler.postDelayed(fpsDiagRunnable, 1000)
    }

    /**
     * Donanim H264 encoder'i 1080p60 destekliyor mu? Desteklemiyorsa ve bir HEVC
     * donanim encoder'i destekliyorsa HEVC tercih edilir. Karar loglanir.
     */
    private fun decidePreferHevc(): Boolean {
        val h264 = hwEncoderSupports(MediaFormat.MIMETYPE_VIDEO_AVC, vw, vh, vfps)
        if (h264) {
            Log.d(TAG, "H264 donanim encoder ${vw}x$vh@$vfps destekliyor -> H264")
            return false
        }
        val hevc = hwEncoderSupports(MediaFormat.MIMETYPE_VIDEO_HEVC, vw, vh, vfps)
        Log.w(TAG, "H264 ${vw}x$vh@$vfps desteklemiyor; HEVC destegi=$hevc -> " +
            if (hevc) "HEVC fallback" else "yine de H264 (en iyi caba)")
        return hevc
    }

    private fun hwEncoderSupports(mime: String, w: Int, h: Int, fps: Int): Boolean {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in list.codecInfos) {
            if (!info.isEncoder) continue
            if (!info.supportedTypes.any { it.equals(mime, ignoreCase = true) }) continue
            // Yazilim encoder'lari genelde 60fps 1080p'yi karsilayamaz; donanimi tercih et
            val isHw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isHardwareAccelerated else true
            if (!isHw) continue
            try {
                val caps = info.getCapabilitiesForType(mime).videoCapabilities ?: continue
                if (caps.areSizeAndRateSupported(w, h, fps.toDouble())) return true
            } catch (_: Exception) {
            }
        }
        return false
    }

    private fun createAndSendOffer() {
        // Codec sirasi transceiver'da setCodecPreferences ile ayarlandi; SDP oldugu gibi.
        session.createOffer { offer ->
            session.setLocalDescription(offer) {
                signaling.sendOffer(offer)
                status(getString(R.string.st_offer_sent))
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
            if (state == PeerConnection.IceConnectionState.CONNECTED) {
                // LAN: BWE'nin dusuk baslayip yavas tirmanmasini bekleme —
                // hedef bitrate'e dogrudan kickstart et.
                val ok = session.peerConnection()?.setBitrate(vMinBps, vMaxBps, vMaxBps)
                Log.d(TAG, "setBitrate kickstart (min=$vMinBps cur/max=$vMaxBps) -> $ok")
            }
        }

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            status(getString(R.string.st_pc, state.toString()))
        }

        override fun onRemoteVideoTrack(track: VideoTrack) {
            // Yayinci remote track beklemez
        }
    }

    // --- SignalingClient.Listener ---
    override fun onOpen() {}

    override fun onJoined(peers: Int) {
        status(getString(R.string.st_joined, peers))
        // Zaten izleyici varsa hemen offer uret
        if (peers > 0) runOnUiThread { createAndSendOffer() }
    }

    override fun onPeerJoined(role: String?) {
        status(getString(R.string.st_viewer_joined))
        runOnUiThread { createAndSendOffer() }
    }

    override fun onPeerLeft() {
        status(getString(R.string.st_viewer_left))
    }

    override fun onRemoteOffer(sdp: SessionDescription) {
        // Yayinci offer beklemez
    }

    override fun onRemoteAnswer(sdp: SessionDescription) {
        status(getString(R.string.st_answer_received))
        session.setRemoteDescription(sdp) {}
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
        Log.d(TAG, "[Publisher] $msg")
        runOnUiThread { binding.statusText.text = msg }
    }

    override fun onDestroy() {
        super.onDestroy()
        ScreenCaptureService.readyListener = null
        mainHandler.removeCallbacks(fgsTimeoutRunnable)
        mainHandler.removeCallbacks(fpsDiagRunnable)
        fpsCapturerRef = null
        try {
            screenCapturer?.stopCapture()
        } catch (_: Exception) {
        }
        screenCapturer?.dispose()
        session.internalAudioCapture = null
        internalAudio?.stop()
        audioTrack?.dispose()
        audioSource?.dispose()
        videoTrack?.dispose()
        videoSource?.dispose()
        surfaceTextureHelper?.dispose()
        if (::signaling.isInitialized) signaling.close()
        session.dispose()
        eglBase.release()
        stopService(Intent(this, ScreenCaptureService::class.java))
    }
}
