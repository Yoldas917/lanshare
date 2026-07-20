package com.example.lanshare

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress

/**
 * Cihaz ici (gomulu) WebRTC signaling sunucusu — ~/signaling-server/server.js'in
 * birebir portu. Protokol ayni: join/offer/answer/ice/bye istemciden;
 * joined/peer-joined/peer-left/error sunucudan; offer/answer/ice ayni odadaki
 * diger peer'lara relay edilir. Mevcut SignalingClient hicbir degisiklik
 * olmadan buna baglanir (ev sahibi 127.0.0.1'e, karsi cihaz LAN IP'sine).
 *
 * Port 0 ile baslatilir (ephemeral); gercek port [boundPort] ile okunur ve
 * NSD uzerinden duyurulur.
 *
 * [onRemoteJoin]: BOS odaya LAN'dan (loopback olmayan) ilk peer join oldugunda
 * rolüyle tetiklenir — ev sahibi uygulama karsi rolu otomatik baslatir
 * (dokun-baglan davet mekanizmasi; tel protokolu degismez).
 */
class EmbeddedSignalingServer(
    private val onRemoteJoin: ((role: String) -> Unit)? = null
) : WebSocketServer(InetSocketAddress(0)) {

    private data class ConnInfo(var roomId: String? = null, var role: String? = null)

    private val rooms = HashMap<String, MutableSet<WebSocket>>()
    private val info = HashMap<WebSocket, ConnInfo>()
    private val lock = Any()

    @Volatile
    var boundPort: Int = -1
        private set

    init {
        isReuseAddr = true
    }

    override fun onStart() {
        boundPort = address.port
        Log.i(TAG, "[EmbedSrv] gomulu signaling calisiyor: 0.0.0.0:$boundPort")
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        synchronized(lock) { info[conn] = ConnInfo() }
        Log.d(TAG, "[EmbedSrv] + baglanti ${conn.remoteSocketAddress}")
    }

    override fun onMessage(conn: WebSocket, message: String) {
        val msg = try {
            JSONObject(message)
        } catch (e: Exception) {
            send(conn, JSONObject().put("type", "error").put("message", "gecersiz JSON"))
            return
        }
        when (val type = msg.optString("type")) {
            "join" -> handleJoin(conn, msg)
            "offer", "answer", "ice" -> relay(conn, msg)
            "bye" -> leaveRoom(conn)
            else -> send(conn, JSONObject().put("type", "error")
                .put("message", "bilinmeyen type: $type"))
        }
    }

    private fun handleJoin(conn: WebSocket, msg: JSONObject) {
        val roomId = msg.optString("roomId")
        if (roomId.isEmpty()) {
            send(conn, JSONObject().put("type", "error").put("message", "join icin roomId gerekli"))
            return
        }
        val role = msg.optString("role", "unknown")
        var otherCount: Int
        var fireInvite = false
        synchronized(lock) {
            val ci = info[conn] ?: return
            if (ci.roomId != null) leaveRoomLocked(conn)
            ci.roomId = roomId
            ci.role = role
            val room = rooms.getOrPut(roomId) { mutableSetOf() }
            room.add(conn)
            otherCount = room.size - 1
            // Bos odaya LAN'dan gelen ilk peer -> ev sahibine davet sinyali
            val remoteAddr = conn.remoteSocketAddress?.address
            if (otherCount == 0 && remoteAddr != null && !remoteAddr.isLoopbackAddress) {
                fireInvite = true
            }
        }
        Log.d(TAG, "[EmbedSrv] join room=$roomId role=$role digerleri=$otherCount")
        send(conn, JSONObject().put("type", "joined").put("roomId", roomId).put("peers", otherCount))
        broadcast(conn, roomId, JSONObject().put("type", "peer-joined").put("role", role))
        if (fireInvite) {
            Log.i(TAG, "[EmbedSrv] uzak peer geldi (role=$role) -> davet callback")
            onRemoteJoin?.invoke(role)
        }
    }

    private fun relay(conn: WebSocket, msg: JSONObject) {
        val roomId = synchronized(lock) { info[conn]?.roomId }
        if (roomId == null) {
            send(conn, JSONObject().put("type", "error").put("message", "once join yapmalisin"))
            return
        }
        val targets = synchronized(lock) {
            rooms[roomId]?.filter { it != conn && it.isOpen } ?: emptyList()
        }
        for (peer in targets) peer.send(msg.toString())
        Log.d(TAG, "[EmbedSrv] relay '${msg.optString("type")}' -> ${targets.size} peer")
    }

    private fun leaveRoom(conn: WebSocket) = synchronized(lock) { leaveRoomLocked(conn) }

    private fun leaveRoomLocked(conn: WebSocket) {
        val ci = info[conn] ?: return
        val roomId = ci.roomId ?: return
        rooms[roomId]?.let { room ->
            room.remove(conn)
            for (peer in room) if (peer.isOpen) {
                peer.send(JSONObject().put("type", "peer-left").toString())
            }
            if (room.isEmpty()) rooms.remove(roomId)
        }
        Log.d(TAG, "[EmbedSrv] odadan ayrildi room=$roomId")
        ci.roomId = null
        ci.role = null
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        Log.d(TAG, "[EmbedSrv] - baglanti kapandi ($code)")
        synchronized(lock) {
            leaveRoomLocked(conn)
            info.remove(conn)
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e(TAG, "[EmbedSrv] hata: ${ex.message}")
    }

    private fun broadcast(from: WebSocket, roomId: String, msg: JSONObject) {
        val targets = synchronized(lock) {
            rooms[roomId]?.filter { it != from && it.isOpen } ?: emptyList()
        }
        for (peer in targets) peer.send(msg.toString())
    }

    private fun send(conn: WebSocket, msg: JSONObject) {
        if (conn.isOpen) conn.send(msg.toString())
    }

    fun shutdown() {
        try {
            stop(500)
        } catch (_: Exception) {
        }
        Log.i(TAG, "[EmbedSrv] durduruldu")
    }
}
