package com.example.lanshare

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.util.concurrent.TimeUnit

/**
 * Mevcut Node.js signaling sunucusuyla birebir uyumlu WebSocket istemcisi.
 *
 * Istemci -> sunucu:  {type:"join",roomId,role} / {type:"offer",sdp} /
 *                     {type:"answer",sdp} / {type:"ice",candidate:{...}} / {type:"bye"}
 * Sunucu -> istemci:  joined / peer-joined / peer-left / error ve relay'lenen offer/answer/ice
 */
class SignalingClient(
    private val host: String,
    private val port: Int,
    private val roomId: String,
    private val role: String,
    private val listener: Listener
) {
    interface Listener {
        fun onOpen()
        fun onJoined(peers: Int)
        fun onPeerJoined(role: String?)
        fun onPeerLeft()
        fun onRemoteOffer(sdp: SessionDescription)
        fun onRemoteAnswer(sdp: SessionDescription)
        fun onRemoteIce(candidate: IceCandidate)
        fun onError(message: String)
        fun onClosed()
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS) // olu baglantilari canli tut
        .build()
    private var ws: WebSocket? = null

    fun connect() {
        val url = "ws://$host:$port"
        Log.d(TAG, "Signaling baglaniyor: $url (room=$roomId role=$role)")
        val request = Request.Builder().url(url).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Signaling WS acildi")
                // Baglanir baglanmaz odaya katil
                val join = JSONObject()
                    .put("type", "join")
                    .put("roomId", roomId)
                    .put("role", role)
                webSocket.send(join.toString())
                listener.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Signaling WS hatasi: ${t.message}")
                listener.onError(t.message ?: "ws failure")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Signaling WS kapandi: $code $reason")
                listener.onClosed()
            }
        })
    }

    private fun handleMessage(text: String) {
        try {
            val msg = JSONObject(text)
            when (val type = msg.optString("type")) {
                "joined" -> {
                    val peers = msg.optInt("peers", 0)
                    Log.d(TAG, "joined: odadaki diger peer sayisi=$peers")
                    listener.onJoined(peers)
                }
                "peer-joined" -> {
                    val r = if (msg.has("role")) msg.optString("role") else null
                    Log.d(TAG, "peer-joined: role=$r")
                    listener.onPeerJoined(r)
                }
                "peer-left" -> {
                    Log.d(TAG, "peer-left")
                    listener.onPeerLeft()
                }
                "offer" -> {
                    val sdp = msg.getString("sdp")
                    Log.d(TAG, "offer alindi (${sdp.length} char)")
                    listener.onRemoteOffer(SessionDescription(SessionDescription.Type.OFFER, sdp))
                }
                "answer" -> {
                    val sdp = msg.getString("sdp")
                    Log.d(TAG, "answer alindi (${sdp.length} char)")
                    listener.onRemoteAnswer(SessionDescription(SessionDescription.Type.ANSWER, sdp))
                }
                "ice" -> {
                    val c = msg.getJSONObject("candidate")
                    val ice = IceCandidate(
                        c.getString("sdpMid"),
                        c.getInt("sdpMLineIndex"),
                        c.getString("candidate")
                    )
                    Log.d(TAG, "ICE aday alindi (mid=${ice.sdpMid})")
                    listener.onRemoteIce(ice)
                }
                "error" -> {
                    val m = msg.optString("message")
                    Log.e(TAG, "Sunucu error: $m")
                    listener.onError(m)
                }
                else -> Log.w(TAG, "Bilinmeyen signaling mesaji: $type")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Signaling mesaji parse edilemedi: ${e.message}")
        }
    }

    fun sendOffer(sdp: SessionDescription) {
        val json = JSONObject().put("type", "offer").put("sdp", sdp.description)
        ws?.send(json.toString())
        Log.d(TAG, "offer gonderildi")
    }

    fun sendAnswer(sdp: SessionDescription) {
        val json = JSONObject().put("type", "answer").put("sdp", sdp.description)
        ws?.send(json.toString())
        Log.d(TAG, "answer gonderildi")
    }

    fun sendIce(candidate: IceCandidate) {
        val cand = JSONObject()
            .put("sdpMid", candidate.sdpMid)
            .put("sdpMLineIndex", candidate.sdpMLineIndex)
            .put("candidate", candidate.sdp)
        val json = JSONObject().put("type", "ice").put("candidate", cand)
        val ok = ws?.send(json.toString()) ?: false
        Log.d(TAG, "ICE aday gonderildi (mid=${candidate.sdpMid}, ws=$ok)")
    }

    fun close() {
        try {
            ws?.send(JSONObject().put("type", "bye").toString())
        } catch (_: Exception) {
        }
        ws?.close(1000, "bye")
        ws = null
    }
}
