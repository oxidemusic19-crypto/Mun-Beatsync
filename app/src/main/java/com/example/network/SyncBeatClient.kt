package com.example.network

import android.content.Context
import android.os.SystemClock
import com.example.model.AudioTrack
import com.example.model.ChatMessage
import com.example.model.DeviceStatus
import com.example.model.DiscoveredRoom
import com.example.model.Participant
import com.example.model.PartyAudioSettings
import com.example.model.PlaybackState
import com.example.model.SongRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class SyncBeatClient(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onConnectedSnapshot: (
        roomName: String,
        hostIp: String,
        httpPort: Int,
        sessionStartedAtMs: Long,
        totalListenersJoined: Int,
        tracks: List<AudioTrack>,
        playbackState: PlaybackState,
        participants: List<Participant>,
        chatHistory: List<ChatMessage>,
        songRequests: List<SongRequest>
    ) -> Unit,
    private val onPlaybackState: (PlaybackState) -> Unit,
    private val onQueueUpdate: (List<AudioTrack>) -> Unit,
    private val onParticipantsUpdate: (List<Participant>, Int) -> Unit,
    private val onChatMessage: (ChatMessage) -> Unit,
    private val onReaction: (id: String, emoji: String, senderName: String, xFraction: Float) -> Unit,
    private val onSongRequestUpdate: (List<SongRequest>) -> Unit,
    private val onClockUpdated: (offsetMs: Long, rttMs: Long, sampleCount: Int) -> Unit,
    private val onConnectionError: (String) -> Unit,
    private val onKicked: (String) -> Unit,
    private val onWebRtcOffer: (senderId: String, sdp: String) -> Unit = { _, _ -> },
    private val onWebRtcIceCandidate: (senderId: String, sdpMid: String, sdpMLineIndex: Int, candidate: String) -> Unit = { _, _, _, _ -> },
    private val onAudioSettingsUpdated: (PartyAudioSettings) -> Unit = {}
) {
    companion object {
        const val HOST_PEER_ID = "host"
    }

    private data class ClockSample(val offsetMs: Long, val rttMs: Long)
    private var webRtcPeerManager: WebRtcPeerManager? = null

    fun attachWebRtcManager(manager: WebRtcPeerManager?) {
        webRtcPeerManager = manager
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var clockSyncJob: Job? = null
    private var reconnectJob: Job? = null
    private var discoveryJob: Job? = null

    private val pingSeq = AtomicLong(1L)
    private val clockSamples = ArrayList<ClockSample>()
    @Volatile private var currentOffsetMs: Long = 0L
    @Volatile private var currentRttMs: Long = 0L
    @Volatile private var intentionalDisconnect = false
    @Volatile private var autoReconnectEnabled = true

    private var targetHostIp: String = ""
    private var targetWsPort: Int = SyncBeatHostServer.DEFAULT_WS_PORT
    private var targetRoomCode: String = ""
    private var myParticipantId: String = ""
    private var myNickname: String = ""
    private var myAvatarColorHex: String = "#38BDF8"

    private val _connectionStatus = MutableStateFlow(DeviceStatus.DISCONNECTED)
    val connectionStatus: StateFlow<DeviceStatus> = _connectionStatus.asStateFlow()

    private val _discoveredRooms = MutableStateFlow<List<DiscoveredRoom>>(emptyList())
    val discoveredRooms: StateFlow<List<DiscoveredRoom>> = _discoveredRooms.asStateFlow()

    fun estimatedHostNowMs(): Long = SystemClock.elapsedRealtime() + currentOffsetMs

    fun getClockOffsetMs(): Long = currentOffsetMs

    fun setAutoReconnect(enabled: Boolean) {
        autoReconnectEnabled = enabled
    }

    fun startDiscovery() {
        if (discoveryJob?.isActive == true) return
        discoveryJob = scope.launch(Dispatchers.IO) {
            val roomMap = linkedMapOf<String, DiscoveredRoom>()
            val sock = runCatching {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 700
                    bind(InetSocketAddress(SyncBeatHostServer.DISCOVERY_UDP_PORT))
                }
            }.getOrElse {
                // Port may be busy if running on same device; bind ephemeral socket for active probe replies
                runCatching {
                    DatagramSocket().apply {
                        broadcast = true
                        soTimeout = 700
                    }
                }.getOrNull()
            } ?: return@launch

            sock.use { udp ->
                var lastProbeMs = 0L
                val buf = ByteArray(2048)
                val packet = DatagramPacket(buf, buf.size)
                while (isActive) {
                    val now = System.currentTimeMillis()
                    if (now - lastProbeMs >= 1500L) {
                        lastProbeMs = now
                        sendProbeBurst(udp)
                    }

                    val received = runCatching {
                        udp.receive(packet)
                        String(packet.data, 0, packet.length, Charsets.UTF_8)
                    }.getOrNull()

                    if (received != null && received.contains(SyncBeatHostServer.DISCOVERY_MAGIC)) {
                        runCatching {
                            val obj = JSONObject(received)
                            val code = obj.optString("roomCode")
                            val hostIp = obj.optString("hostIp").ifBlank { packet.address.hostAddress ?: "" }
                            if (code.length == 6 && hostIp.isNotBlank()) {
                                roomMap[code] = DiscoveredRoom(
                                    roomCode = code,
                                    roomName = obj.optString("roomName", "SYNCBEAT Party"),
                                    hostName = obj.optString("hostName", "Host"),
                                    hostIp = hostIp,
                                    wsPort = obj.optInt("wsPort", SyncBeatHostServer.DEFAULT_WS_PORT),
                                    httpPort = obj.optInt("httpPort", SyncBeatHostServer.DEFAULT_HTTP_PORT),
                                    currentTrackTitle = obj.optString("currentTrackTitle", "Live Party"),
                                    listenerCount = obj.optInt("listenerCount", 1),
                                    lastSeenMs = now
                                )
                            }
                        }
                    }

                    // Prune stale rooms > 5s old
                    val pruned = roomMap.values.filter { now - it.lastSeenMs < 5500L }
                    roomMap.clear()
                    pruned.forEach { roomMap[it.roomCode] = it }
                    _discoveredRooms.value = pruned.sortedByDescending { it.lastSeenMs }
                }
            }
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
    }

    private fun sendProbeBurst(udp: DatagramSocket) {
        val probeBytes = SyncBeatHostServer.PROBE_MAGIC.toByteArray(Charsets.UTF_8)
        NetworkUtils.getBroadcastAndGatewayTargets(context).forEach { target ->
            runCatching {
                udp.send(
                    DatagramPacket(
                        probeBytes,
                        probeBytes.size,
                        target,
                        SyncBeatHostServer.DISCOVERY_UDP_PORT
                    )
                )
            }
        }
    }

    fun connect(
        hostIp: String,
        wsPort: Int,
        roomCode: String,
        participantId: String,
        nickname: String,
        avatarColorHex: String
    ) {
        intentionalDisconnect = false
        reconnectJob?.cancel()
        targetHostIp = hostIp.trim()
        targetWsPort = wsPort
        targetRoomCode = roomCode.trim()
        myParticipantId = participantId
        myNickname = nickname
        myAvatarColorHex = avatarColorHex

        synchronized(clockSamples) { clockSamples.clear() }
        openWebSocket()
    }

    private fun openWebSocket() {
        webSocket?.cancel()
        clockSyncJob?.cancel()
        _connectionStatus.value = DeviceStatus.RECONNECTING

        val request = Request.Builder()
            .url("ws://$targetHostIp:$targetWsPort/ws")
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(
                    ProtocolMessages.encodeJoin(
                        roomCode = targetRoomCode,
                        participantId = myParticipantId,
                        name = myNickname,
                        avatarColorHex = myAvatarColorHex
                    )
                )
                startClockSyncLoop(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!intentionalDisconnect) {
                    scheduleReconnect("Connection closed by host")
                } else {
                    _connectionStatus.value = DeviceStatus.DISCONNECTED
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!intentionalDisconnect) {
                    scheduleReconnect(t.localizedMessage ?: "Unable to reach host on Wi-Fi")
                } else {
                    _connectionStatus.value = DeviceStatus.DISCONNECTED
                }
            }
        })
    }

    private fun startClockSyncLoop(ws: WebSocket) {
        clockSyncJob?.cancel()
        clockSyncJob = scope.launch(Dispatchers.IO) {
            // Fast initial 8-sample NTP calibration burst
            repeat(8) {
                if (!isActive) return@launch
                ws.send(ProtocolMessages.encodeSyncPing(pingSeq.getAndIncrement(), SystemClock.elapsedRealtime()))
                delay(65L)
            }
            // Continuous drift & RTT monitoring every 2 seconds (prefers WebRTC P2P UDP DataChannel when open)
            while (isActive) {
                delay(2000L)
                val pingJson = ProtocolMessages.encodeSyncPing(pingSeq.getAndIncrement(), SystemClock.elapsedRealtime())
                if (webRtcPeerManager?.sendLowLatencyText(HOST_PEER_ID, pingJson) != true) {
                    ws.send(pingJson)
                }
            }
        }
    }

    fun handleIncomingMessage(rawText: String) {
        val json = runCatching { JSONObject(rawText) }.getOrNull() ?: return
        when (json.optString("type")) {
            ProtocolMessages.TYPE_SYNC_PONG -> {
                val clientRecv = SystemClock.elapsedRealtime()
                val clientSend = json.optLong("clientSendMs", 0L)
                val hostRecv = json.optLong("hostReceiveMs", 0L)
                val hostSend = json.optLong("hostSendMs", 0L)

                val rtt = ((clientRecv - clientSend) - (hostSend - hostRecv)).coerceAtLeast(0L)
                val offset = ((hostRecv - clientSend) + (hostSend - clientRecv)) / 2L

                val (bestOffset, bestRtt, count) = synchronized(clockSamples) {
                    clockSamples.add(ClockSample(offset, rtt))
                    if (clockSamples.size > 12) clockSamples.removeAt(0)
                    val lowestRtt = clockSamples.sortedBy { it.rttMs }.take(5)
                    val avgOffset = lowestRtt.map { it.offsetMs }.average().toLong()
                    val minRtt = lowestRtt.firstOrNull()?.rttMs ?: rtt
                    Triple(avgOffset, minRtt, clockSamples.size)
                }
                currentOffsetMs = bestOffset
                currentRttMs = bestRtt
                onClockUpdated(bestOffset, bestRtt, count)
            }

            ProtocolMessages.TYPE_JOIN_ACK -> {
                val accepted = json.optBoolean("accepted", false)
                if (!accepted) {
                    intentionalDisconnect = true
                    val reason = json.optString("reason", "Room rejected join")
                    _connectionStatus.value = DeviceStatus.DISCONNECTED
                    onConnectionError(reason)
                } else {
                    _connectionStatus.value = DeviceStatus.CONNECTED
                }
            }

            ProtocolMessages.TYPE_KICKED -> {
                intentionalDisconnect = true
                _connectionStatus.value = DeviceStatus.DISCONNECTED
                onKicked(json.optString("reason", "Removed by host"))
            }

            ProtocolMessages.TYPE_ROOM_SNAPSHOT -> {
                _connectionStatus.value = DeviceStatus.CONNECTED
                val roomName = json.optString("roomName", "SYNCBEAT Party")
                val hostIp = json.optString("hostIp", targetHostIp)
                val httpPort = json.optInt("httpPort", SyncBeatHostServer.DEFAULT_HTTP_PORT)
                val startedAt = json.optLong("sessionStartedAtMs", System.currentTimeMillis())
                val totalJoined = json.optInt("totalListenersJoined", 1)
                val tracks = json.optJSONArray("tracks")?.let { ProtocolMessages.jsonArrayToTracks(it) } ?: emptyList()
                val playback = json.optJSONObject("playbackState")?.let { ProtocolMessages.jsonToPlaybackState(it) } ?: PlaybackState()
                val participants = json.optJSONArray("participants")?.let { ProtocolMessages.jsonArrayToParticipants(it) } ?: emptyList()
                val chatArr = json.optJSONArray("chatHistory")
                val chatList = if (chatArr != null) {
                    List(chatArr.length()) { ProtocolMessages.jsonToChat(chatArr.getJSONObject(it)) }
                } else emptyList()
                val reqArr = json.optJSONArray("songRequests")
                val reqList = if (reqArr != null) {
                    List(reqArr.length()) { ProtocolMessages.jsonToSongRequest(reqArr.getJSONObject(it)) }
                } else emptyList()
                val audioSettings = ProtocolMessages.jsonToAudioSettings(json.optJSONObject("audioSettings"))

                onAudioSettingsUpdated(audioSettings)
                onConnectedSnapshot(
                    roomName,
                    hostIp,
                    httpPort,
                    startedAt,
                    totalJoined,
                    tracks,
                    playback,
                    participants,
                    chatList,
                    reqList
                )
            }

            ProtocolMessages.TYPE_PLAYBACK_STATE -> {
                val obj = json.optJSONObject("state") ?: return
                onPlaybackState(ProtocolMessages.jsonToPlaybackState(obj))
            }

            ProtocolMessages.TYPE_QUEUE_UPDATE -> {
                val arr = json.optJSONArray("tracks") ?: return
                onQueueUpdate(ProtocolMessages.jsonArrayToTracks(arr))
            }

            ProtocolMessages.TYPE_PARTICIPANTS_UPDATE -> {
                val arr = json.optJSONArray("participants") ?: return
                val total = json.optInt("totalListenersJoined", arr.length())
                onParticipantsUpdate(ProtocolMessages.jsonArrayToParticipants(arr), total)
            }

            ProtocolMessages.TYPE_CHAT_MESSAGE -> {
                val obj = json.optJSONObject("message") ?: return
                onChatMessage(ProtocolMessages.jsonToChat(obj))
            }

            ProtocolMessages.TYPE_REACTION -> {
                onReaction(
                    json.optString("id"),
                    json.optString("emoji", "🔥"),
                    json.optString("senderName", "Guest"),
                    json.optDouble("xFraction", 0.5).toFloat()
                )
            }

            ProtocolMessages.TYPE_SONG_REQUEST_UPDATE -> {
                val arr = json.optJSONArray("requests") ?: return
                val list = List(arr.length()) { ProtocolMessages.jsonToSongRequest(arr.getJSONObject(it)) }
                onSongRequestUpdate(list)
            }

            ProtocolMessages.TYPE_AUDIO_SETTINGS -> {
                val settings = ProtocolMessages.jsonToAudioSettings(json.optJSONObject("audioSettings"))
                onAudioSettingsUpdated(settings)
            }

            ProtocolMessages.TYPE_WEBRTC_OFFER -> {
                val sdp = json.optString("sdp")
                if (sdp.isNotBlank()) {
                    onWebRtcOffer(HOST_PEER_ID, sdp)
                }
            }

            ProtocolMessages.TYPE_WEBRTC_ICE -> {
                val sdpMid = json.optString("sdpMid")
                val mLine = json.optInt("sdpMLineIndex", 0)
                val candidate = json.optString("candidate")
                if (candidate.isNotBlank()) {
                    onWebRtcIceCandidate(HOST_PEER_ID, sdpMid, mLine, candidate)
                }
            }
        }
    }

    private fun scheduleReconnect(reason: String) {
        clockSyncJob?.cancel()
        if (!autoReconnectEnabled) {
            _connectionStatus.value = DeviceStatus.DISCONNECTED
            onConnectionError(reason)
            return
        }
        _connectionStatus.value = DeviceStatus.RECONNECTING
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch(Dispatchers.IO) {
            val delays = listOf(1200L, 2000L, 3500L, 5000L)
            for (wait in delays) {
                if (intentionalDisconnect || !isActive) return@launch
                delay(wait)
                if (intentionalDisconnect || !isActive) return@launch
                openWebSocket()
                return@launch
            }
            _connectionStatus.value = DeviceStatus.DISCONNECTED
            onConnectionError(reason)
        }
    }

    fun sendSignaling(json: String) {
        webSocket?.send(json)
    }

    fun requestTrackOverP2p(trackId: String) {
        val req = ProtocolMessages.encodeP2pAudioRequest(trackId)
        webRtcPeerManager?.sendReliableText(HOST_PEER_ID, req)
    }

    fun sendTelemetry(driftMs: Long) {
        val json = ProtocolMessages.encodeParticipantTelemetry(
            participantId = myParticipantId,
            rttMs = currentRttMs,
            driftMs = driftMs
        )
        if (webRtcPeerManager?.sendLowLatencyText(HOST_PEER_ID, json) != true) {
            webSocket?.send(json)
        }
    }

    fun sendChatMessage(message: ChatMessage) {
        val json = ProtocolMessages.encodeChatMessage(message)
        if (webRtcPeerManager?.sendReliableText(HOST_PEER_ID, json) != true) {
            webSocket?.send(json)
        }
    }

    fun sendReaction(id: String, emoji: String, senderName: String, xFraction: Float) {
        val json = ProtocolMessages.encodeReaction(id, emoji, senderName, xFraction)
        if (webRtcPeerManager?.sendLowLatencyText(HOST_PEER_ID, json) != true) {
            webSocket?.send(json)
        }
    }

    fun sendSongRequest(request: SongRequest) {
        val json = ProtocolMessages.encodeSongRequest(request)
        if (webRtcPeerManager?.sendReliableText(HOST_PEER_ID, json) != true) {
            webSocket?.send(json)
        }
    }

    fun disconnect() {
        intentionalDisconnect = true
        reconnectJob?.cancel()
        clockSyncJob?.cancel()
        runCatching { webSocket?.close(1000, "Leaving room") }
        webSocket = null
        _connectionStatus.value = DeviceStatus.DISCONNECTED
    }
}
