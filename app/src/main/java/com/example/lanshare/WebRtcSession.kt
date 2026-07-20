package com.example.lanshare

import android.content.Context
import android.util.Log
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpCapabilities
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer

/**
 * Tek bir WebRTC PeerConnection'i yoneten yardimci. Publisher ve Viewer
 * ayni cekirdegi kullanir. Donanim hizlandirmali (H264 dahil) codec factory'ler
 * kullanilir. LAN icinde oldugumuz icin STUN/TURN gerekmez (host candidate'lar yeter).
 */
class WebRtcSession(
    context: Context,
    val eglBase: EglBase,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onLocalIceCandidate(candidate: IceCandidate)
        fun onIceConnectionChange(state: PeerConnection.IceConnectionState)
        fun onConnectionChange(state: PeerConnection.PeerConnectionState)
        fun onRemoteVideoTrack(track: VideoTrack)
    }

    val factory: PeerConnectionFactory
    private val audioDeviceModule: JavaAudioDeviceModule
    private var peerConnection: PeerConnection? = null

    // Ic ses yakalayici — ses acikken Publisher tarafindan atanir. ADM kayit
    // callback'i bu referans uzerinden PCM'i buffer'a yazar (yoksa dokunmaz).
    @Volatile
    var internalAudioCapture: InternalAudioCapture? = null

    // ICE aday kuyrugu: remote description set edilmeden gelen adaylari biriktir
    private var remoteDescriptionSet = false
    private val pendingIce = mutableListOf<IceCandidate>()

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(context.applicationContext)
                .createInitializationOptions()
        )
        // Donanim H264 + VP8 destekli encoder; donanim decoder
        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase.eglBaseContext,
            /* enableIntelVp8Encoder = */ true,
            /* enableH264HighProfile = */ true
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        // Ozel ADM: AudioBufferCallback, AudioRecordThread'de mic verisi native'e
        // gitmeden ONCE cagrilir (mute sonrasi). internalAudioCapture atanmissa
        // buffer'i tamamen ic ses PCM'i ile DEGISTIRIR (mic'e eklenmez). Mic ayrica
        // muteMicrophone(true) ile sifirlanir -> mic asla gitmez.
        // Giris 48kHz mono -> InternalAudioCapture ile ayni format.
        audioDeviceModule = JavaAudioDeviceModule.builder(context.applicationContext)
            .setInputSampleRate(AUDIO_SAMPLE_RATE)
            .setUseStereoInput(false)
            .setAudioBufferCallback(object : JavaAudioDeviceModule.AudioBufferCallback {
                override fun onBuffer(
                    buffer: ByteBuffer,
                    audioFormat: Int,
                    channelCount: Int,
                    sampleRate: Int,
                    bytesRead: Int,
                    captureTimeNs: Long
                ): Long {
                    internalAudioCapture?.fill(buffer)
                    return captureTimeNs
                }
            })
            .createAudioDeviceModule()

        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
        Log.d(TAG, "PeerConnectionFactory olusturuldu (HW encoder/decoder + ozel ADM)")
    }

    fun createPeerConnection() {
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // GATHER_ONCE: LAN'dayiz, ag degisimi beklemiyoruz. CONTINUALLY,
            // NetworkMonitor'e bagimli — monitor ag listesi veremezse hic aday
            // uretilmeyebiliyordu. ONCE, arayuzleri tek seferde enumere eder.
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                Log.d(TAG, "ICE aday uretildi (mid=${candidate.sdpMid}): ${candidate.sdp.take(60)}...")
                callbacks.onLocalIceCandidate(candidate)
            }

            override fun onIceCandidateError(event: org.webrtc.IceCandidateErrorEvent) {
                Log.e(TAG, "ICE aday HATASI: code=${event.errorCode} ${event.errorText} " +
                    "(addr=${event.address}:${event.port})")
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                Log.d(TAG, "ICE state: $newState")
                callbacks.onIceConnectionChange(newState)
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                Log.d(TAG, "PeerConnection state -> $newState")
                callbacks.onConnectionChange(newState)
            }

            override fun onTrack(transceiver: RtpTransceiver) {
                val track = transceiver.receiver.track()
                if (track is VideoTrack) {
                    Log.d(TAG, "onTrack: remote video track alindi")
                    callbacks.onRemoteVideoTrack(track)
                }
            }

            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
                // Bazi surumlerde onTrack yerine burasi tetiklenebilir
                val track = receiver.track()
                if (track is VideoTrack) {
                    Log.d(TAG, "onAddTrack: remote video track alindi")
                    callbacks.onRemoteVideoTrack(track)
                }
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                Log.d(TAG, "ICE gathering -> $state")
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dc: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
        })
    }

    fun peerConnection(): PeerConnection? = peerConnection

    /**
     * Mikrofonu susturur: ADM kayit thread'i mic orneklerini sifirlar; ardindan
     * AudioBufferCallback (varsa) ic ses PCM'ini yazar. Ic ses paylasiminda
     * mic'in ASLA gitmemesini garanti eder.
     */
    fun muteMicrophone(mute: Boolean) {
        audioDeviceModule.setMicrophoneMute(mute)
        Log.d(TAG, "microphone mute = $mute")
    }

    /**
     * Video transceiver'da codec sirasini ayarlar: birincil codec (H264 veya
     * HEVC) en one alinir. Desteklenen tum codec'ler loglanir. addTrack'ten
     * SONRA cagrilmali (transceiver olusmus olmali).
     */
    fun applyVideoCodecPreferences(preferHevc: Boolean) {
        val pc = peerConnection ?: return
        val transceiver = pc.transceivers.firstOrNull {
            it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
        }
        if (transceiver == null) {
            Log.w(TAG, "video transceiver bulunamadi, codec tercihi atlandi")
            return
        }
        val caps = factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)
        val codecs = caps.codecs
        Log.d(TAG, "Desteklenen video codec'ler: " + codecs.joinToString { it.name })

        val primary = if (preferHevc) "H265" else "H264"
        fun rank(c: RtpCapabilities.CodecCapability): Int {
            val n = c.name.uppercase()
            return when {
                n.contains(primary) -> 0
                n.contains("H264") || n.contains("H265") || n.contains("HEVC") -> 1
                n.contains("RTX") || n.contains("RED") || n.contains("FEC") -> 3
                else -> 2
            }
        }
        // stableSort: ayni rank icindekilerin sirasi korunur
        val ordered = codecs.sortedBy { rank(it) }
        try {
            transceiver.setCodecPreferences(ordered)
            Log.d(TAG, "Codec tercihi uygulandi (birincil=$primary): " +
                ordered.take(4).joinToString { it.name })
        } catch (e: Exception) {
            Log.e(TAG, "setCodecPreferences hatasi: ${e.message}")
        }
    }

    data class VideoStats(
        val width: Int,
        val height: Int,
        val fps: Double,
        val bytesReceived: Long,
        val timestampUs: Double,
        val codec: String?
    )

    data class OutboundVideoStats(
        val fps: Double,               // encoder cikisi (framesPerSecond)
        val framesEncoded: Long,
        val framesSent: Long,
        val qualityLimitation: String?, // none / cpu / bandwidth / other
        val encoderImpl: String?,
        val width: Int,
        val height: Int,
        val timestampUs: Double
    )

    /** Giden (outbound) video istatistikleri — fps zinciri teshisi icin. */
    fun pollOutboundVideoStats(cb: (OutboundVideoStats?) -> Unit) {
        val pc = peerConnection
        if (pc == null) { cb(null); return }
        pc.getStats { report ->
            var result: OutboundVideoStats? = null
            for (st in report.statsMap.values) {
                val kind = st.members["kind"] ?: st.members["mediaType"]
                if (st.type == "outbound-rtp" && kind == "video") {
                    val m = st.members
                    result = OutboundVideoStats(
                        fps = (m["framesPerSecond"] as? Number)?.toDouble() ?: 0.0,
                        framesEncoded = (m["framesEncoded"] as? Number)?.toLong() ?: 0L,
                        framesSent = (m["framesSent"] as? Number)?.toLong() ?: 0L,
                        qualityLimitation = m["qualityLimitationReason"] as? String,
                        encoderImpl = m["encoderImplementation"] as? String,
                        width = (m["frameWidth"] as? Number)?.toInt() ?: 0,
                        height = (m["frameHeight"] as? Number)?.toInt() ?: 0,
                        timestampUs = report.timestampUs
                    )
                }
            }
            cb(result)
        }
    }

    /** Gelen (inbound) video istatistiklerini okur; callback WebRTC thread'inde calisir. */
    fun pollInboundVideoStats(cb: (VideoStats?) -> Unit) {
        val pc = peerConnection
        if (pc == null) { cb(null); return }
        pc.getStats { report ->
            var result: VideoStats? = null
            for (st in report.statsMap.values) {
                val kind = st.members["kind"] ?: st.members["mediaType"]
                if (st.type == "inbound-rtp" && kind == "video") {
                    val m = st.members
                    val w = (m["frameWidth"] as? Number)?.toInt() ?: 0
                    val h = (m["frameHeight"] as? Number)?.toInt() ?: 0
                    val fps = (m["framesPerSecond"] as? Number)?.toDouble() ?: 0.0
                    val bytes = (m["bytesReceived"] as? Number)?.toLong() ?: 0L
                    val codecId = m["codecId"] as? String
                    val codecMime = codecId?.let {
                        report.statsMap[it]?.members?.get("mimeType") as? String
                    }
                    result = VideoStats(w, h, fps, bytes, report.timestampUs, codecMime)
                }
            }
            cb(result)
        }
    }

    fun createOffer(onCreated: (SessionDescription) -> Unit) {
        val constraints = MediaConstraints()
        peerConnection?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                onCreated(sdp)
            }

            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "createOffer basarisiz: $error")
            }
        }, constraints)
    }

    fun createAnswer(onCreated: (SessionDescription) -> Unit) {
        val constraints = MediaConstraints()
        peerConnection?.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                onCreated(sdp)
            }

            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "createAnswer basarisiz: $error")
            }
        }, constraints)
    }

    fun setLocalDescription(sdp: SessionDescription, onDone: () -> Unit) {
        peerConnection?.setLocalDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                Log.d(TAG, "local description set (${sdp.type})")
                onDone()
            }

            override fun onSetFailure(error: String?) {
                Log.e(TAG, "setLocalDescription basarisiz: $error")
            }
        }, sdp)
    }

    fun setRemoteDescription(sdp: SessionDescription, onDone: () -> Unit) {
        peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                Log.d(TAG, "remote description set (${sdp.type})")
                remoteDescriptionSet = true
                // Bekleyen ICE adaylarini uygula
                pendingIce.forEach { peerConnection?.addIceCandidate(it) }
                if (pendingIce.isNotEmpty()) {
                    Log.d(TAG, "${pendingIce.size} bekleyen ICE candidate uygulandi")
                }
                pendingIce.clear()
                onDone()
            }

            override fun onSetFailure(error: String?) {
                Log.e(TAG, "setRemoteDescription basarisiz: $error")
            }
        }, sdp)
    }

    fun addRemoteIceCandidate(candidate: IceCandidate) {
        if (remoteDescriptionSet) {
            peerConnection?.addIceCandidate(candidate)
            Log.d(TAG, "ICE aday eklendi (mid=${candidate.sdpMid})")
        } else {
            pendingIce.add(candidate)
            Log.d(TAG, "ICE aday kuyruga alindi (remote desc bekleniyor, kuyruk=${pendingIce.size})")
        }
    }

    fun dispose() {
        try {
            peerConnection?.dispose()
        } catch (_: Exception) {
        }
        peerConnection = null
        try {
            factory.dispose()
        } catch (_: Exception) {
        }
        try {
            audioDeviceModule.release()
        } catch (_: Exception) {
        }
    }

    companion object {
        /**
         * SDP icindeki m=video satirinda verilen codec'i ( or. "H264") en one alir,
         * boylece donanim H264 tercih edilir.
         */
        fun preferCodec(sdpDescription: String, codec: String): String {
            val lines = sdpDescription.split("\r\n").toMutableList()
            val mLineIndex = lines.indexOfFirst { it.startsWith("m=video ") }
            if (mLineIndex < 0) return sdpDescription

            val rtpmap = Regex("^a=rtpmap:(\\d+) ${Regex.escape(codec)}/", RegexOption.IGNORE_CASE)
            val payloads = lines.mapNotNull { rtpmap.find(it)?.groupValues?.get(1) }
            if (payloads.isEmpty()) return sdpDescription

            // m=video PORT PROTO pt1 pt2 ...  -> ilk 3 alan sabit, gerisi payload'lar
            val parts = lines[mLineIndex].split(" ").toMutableList()
            if (parts.size <= 3) return sdpDescription
            val header = parts.subList(0, 3)
            val existing = parts.subList(3, parts.size)
            val reordered = payloads + existing.filter { it !in payloads }
            lines[mLineIndex] = (header + reordered).joinToString(" ")
            return lines.joinToString("\r\n")
        }
    }
}

/** Bos govdeli SdpObserver; sadece gerekli metodlar override edilir */
open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}
