package com.example.network

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import com.example.model.AudioTrack
import com.example.model.ChatMessage
import com.example.model.DeviceStatus
import com.example.model.Participant
import com.example.model.PartyAudioSettings
import com.example.model.PlaybackState
import com.example.model.SongRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class SyncBeatHostServer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onParticipantJoined: (Participant) -> Unit,
    private val onParticipantDisconnected: (String) -> Unit,
    private val onParticipantTelemetry: (participantId: String, rttMs: Long, driftMs: Long) -> Unit,
    private val onChatMessageReceived: (ChatMessage) -> Unit,
    private val onReactionReceived: (id: String, emoji: String, senderName: String, xFraction: Float) -> Unit,
    private val onSongRequestReceived: (SongRequest) -> Unit,
    private val onWebRtcAnswer: (peerId: String, sdp: String) -> Unit = { _, _ -> },
    private val onWebRtcIceCandidate: (peerId: String, sdpMid: String, sdpMLineIndex: Int, candidate: String) -> Unit = { _, _, _, _ -> }
) {
    private var webRtcPeerManager: WebRtcPeerManager? = null

    fun attachWebRtcManager(manager: WebRtcPeerManager?) {
        webRtcPeerManager = manager
    }
    companion object {
        const val DEFAULT_HTTP_PORT = 8765
        const val DEFAULT_WS_PORT = 8766
        const val DISCOVERY_UDP_PORT = 45454
        const val DISCOVERY_MAGIC = "SYNCBEAT_DISCOVER_V1"
        const val PROBE_MAGIC = "SYNCBEAT_PROBE"
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    }

    private var httpServerSocket: ServerSocket? = null
    private var wsServerSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null

    private var serverJob: Job? = null
    private var udpBeaconJob: Job? = null
    private var udpListenerJob: Job? = null

    @Volatile
    var isRunning: Boolean = false
        private set

    var httpPort: Int = DEFAULT_HTTP_PORT
        private set
    var wsPort: Int = DEFAULT_WS_PORT
        private set
    var hostIp: String = "127.0.0.1"
        private set

    private var roomCode: String = ""
    private var roomName: String = "SYNCBEAT Party"
    private var hostName: String = "Host"
    private var sessionStartedAtMs: Long = System.currentTimeMillis()

    // Canonical host state getters/snapshots updated by ViewModel
    private val trackMap = ConcurrentHashMap<String, AudioTrack>()
    @Volatile private var currentTracks: List<AudioTrack> = emptyList()
    @Volatile private var currentPlaybackState: PlaybackState = PlaybackState()
    @Volatile private var currentParticipants: List<Participant> = emptyList()
    @Volatile private var currentChatHistory: List<ChatMessage> = emptyList()
    @Volatile private var currentSongRequests: List<SongRequest> = emptyList()
    @Volatile private var currentAudioSettings: PartyAudioSettings = PartyAudioSettings()
    @Volatile private var totalListenersJoined: Int = 1

    private val wsClients = CopyOnWriteArrayList<ClientSession>()

    private inner class ClientSession(
        val socket: Socket,
        val output: OutputStream,
        @Volatile var participantId: String? = null,
        @Volatile var isAlive: Boolean = true
    ) {
        private val writeLock = Any()

        fun sendText(text: String): Boolean {
            if (!isAlive) return false
            return try {
                val payload = text.toByteArray(Charsets.UTF_8)
                val frame = encodeWsFrame(0x1, payload)
                synchronized(writeLock) {
                    output.write(frame)
                    output.flush()
                }
                true
            } catch (_: Exception) {
                isAlive = false
                false
            }
        }

        fun sendPong(payload: ByteArray) {
            if (!isAlive) return
            runCatching {
                val frame = encodeWsFrame(0xA, payload)
                synchronized(writeLock) {
                    output.write(frame)
                    output.flush()
                }
            }
        }

        fun close() {
            isAlive = false
            runCatching {
                synchronized(writeLock) {
                    output.write(encodeWsFrame(0x8, ByteArray(0)))
                    output.flush()
                }
            }
            runCatching { socket.close() }
        }
    }

    fun start(
        roomCode: String,
        roomName: String,
        hostName: String,
        initialTracks: List<AudioTrack>,
        initialState: PlaybackState,
        initialParticipants: List<Participant>,
        initialAudioSettings: PartyAudioSettings = PartyAudioSettings()
    ): Pair<Int, Int> {
        stop()

        this.roomCode = roomCode
        this.roomName = roomName
        this.hostName = hostName
        this.sessionStartedAtMs = System.currentTimeMillis()
        this.hostIp = NetworkUtils.getLocalHostIpAddress(context)

        updateTrackCatalog(initialTracks)
        currentPlaybackState = initialState
        currentParticipants = initialParticipants
        currentAudioSettings = initialAudioSettings
        totalListenersJoined = initialParticipants.size.coerceAtLeast(1)

        val httpSock = bindServerSocket(DEFAULT_HTTP_PORT)
        val wsSock = bindServerSocket(DEFAULT_WS_PORT)
        httpServerSocket = httpSock
        wsServerSocket = wsSock
        httpPort = httpSock.localPort
        wsPort = wsSock.localPort
        isRunning = true

        serverJob = scope.launch(Dispatchers.IO) {
            launch { acceptHttpLoop(httpSock) }
            launch { acceptWsLoop(wsSock) }
        }

        startUdpDiscovery()
        return httpPort to wsPort
    }

    fun refreshHostIp(): String {
        hostIp = NetworkUtils.getLocalHostIpAddress(context)
        return hostIp
    }

    fun updateTrackCatalog(tracks: List<AudioTrack>) {
        currentTracks = tracks
        trackMap.clear()
        tracks.forEach { trackMap[it.id] = it }
    }

    fun updatePlaybackState(state: PlaybackState) {
        currentPlaybackState = state
        val encoded = ProtocolMessages.encodePlaybackState(state)
        broadcastWithWebRtcPreferred(encoded, lowLatency = true)
    }

    fun broadcastQueue(tracks: List<AudioTrack>) {
        updateTrackCatalog(tracks)
        broadcastWithWebRtcPreferred(
            ProtocolMessages.encodeQueueUpdate(tracks, hostIp, httpPort),
            lowLatency = false
        )
    }

    fun broadcastParticipants(participants: List<Participant>, totalJoined: Int) {
        currentParticipants = participants
        totalListenersJoined = totalJoined
        broadcastWithWebRtcPreferred(
            ProtocolMessages.encodeParticipantsUpdate(participants, totalJoined),
            lowLatency = false
        )
    }

    fun broadcastChatMessage(message: ChatMessage, updatedHistory: List<ChatMessage>) {
        currentChatHistory = updatedHistory
        broadcastWithWebRtcPreferred(ProtocolMessages.encodeChatMessage(message), lowLatency = false)
    }

    fun broadcastReaction(id: String, emoji: String, senderName: String, xFraction: Float) {
        broadcastWithWebRtcPreferred(
            ProtocolMessages.encodeReaction(id, emoji, senderName, xFraction),
            lowLatency = true
        )
    }

    fun broadcastSongRequests(requests: List<SongRequest>) {
        currentSongRequests = requests
        broadcastWithWebRtcPreferred(
            ProtocolMessages.encodeSongRequestUpdate(requests),
            lowLatency = false
        )
    }

    fun broadcastAudioSettings(settings: PartyAudioSettings) {
        currentAudioSettings = settings
        broadcastWithWebRtcPreferred(
            ProtocolMessages.encodeAudioSettings(settings),
            lowLatency = false
        )
    }

    fun sendToParticipant(participantId: String, text: String): Boolean {
        val target = wsClients.firstOrNull { it.participantId == participantId && it.isAlive }
        return target?.sendText(text) ?: false
    }

    fun kickParticipant(participantId: String) {
        val targets = wsClients.filter { it.participantId == participantId }
        targets.forEach { client ->
            client.sendText(ProtocolMessages.encodeKicked("Removed by host"))
            client.close()
            wsClients.remove(client)
        }
        webRtcPeerManager?.closePeer(participantId)
    }

    private fun broadcastWithWebRtcPreferred(message: String, lowLatency: Boolean) {
        val rtc = webRtcPeerManager
        val dead = mutableListOf<ClientSession>()
        wsClients.forEach { client ->
            val pid = client.participantId
            val sentViaRtc = if (rtc != null && pid != null) {
                if (lowLatency) rtc.sendLowLatencyText(pid, message)
                else rtc.sendReliableText(pid, message)
            } else {
                false
            }
            if (!sentViaRtc && !client.sendText(message)) {
                dead.add(client)
            }
        }
        if (dead.isNotEmpty()) {
            wsClients.removeAll(dead.toSet())
        }
    }

    private fun broadcastText(message: String, exclude: ClientSession? = null) {
        val dead = mutableListOf<ClientSession>()
        wsClients.forEach { client ->
            if (client !== exclude) {
                if (!client.sendText(message)) {
                    dead.add(client)
                }
            }
        }
        if (dead.isNotEmpty()) {
            wsClients.removeAll(dead.toSet())
        }
    }

    private fun bindServerSocket(preferredPort: Int): ServerSocket {
        return try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(preferredPort))
            }
        } catch (_: Exception) {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(0))
            }
        }
    }

    private suspend fun acceptHttpLoop(serverSocket: ServerSocket) {
        while (isRunning && !serverSocket.isClosed) {
            val socket = try {
                serverSocket.accept()
            } catch (_: Exception) {
                break
            }
            scope.launch(Dispatchers.IO) {
                handleHttpClient(socket)
            }
        }
    }

    private fun handleHttpClient(socket: Socket) {
        socket.use { sock ->
            sock.soTimeout = 15_000
            val input = BufferedInputStream(sock.getInputStream())
            val output = sock.getOutputStream()

            val headerBytes = readHttpHeaders(input) ?: return
            val headerText = headerBytes.toString(Charsets.ISO_8859_1)
            val lines = headerText.split("\r\n")
            val requestLine = lines.firstOrNull() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0].uppercase()
            val path = parts[1].substringBefore("?")

            if (!path.startsWith("/audio/")) {
                val body = "SYNCBEAT Local Audio Server OK".toByteArray()
                val resp = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                output.write(resp.toByteArray())
                output.write(body)
                output.flush()
                return
            }

            val trackId = path.removePrefix("/audio/")
            val track = trackMap[trackId]
            if (track == null) {
                output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                output.flush()
                return
            }

            val rangeHeader = lines.firstOrNull { it.startsWith("Range:", ignoreCase = true) }
                ?.substringAfter(":")?.trim()

            serveTrackStream(track, method == "HEAD", rangeHeader, output)
        }
    }

    private fun serveTrackStream(
        track: AudioTrack,
        headOnly: Boolean,
        rangeHeader: String?,
        output: OutputStream
    ) {
        val localFile = track.localPath?.let { File(it) }?.takeIf { it.exists() && it.canRead() }
        if (localFile != null) {
            val totalLen = localFile.length()
            val (start, end, isPartial) = parseByteRange(rangeHeader, totalLen)
            val contentLen = (end - start + 1).coerceAtLeast(0L)

            val headers = buildString {
                if (isPartial) {
                    append("HTTP/1.1 206 Partial Content\r\n")
                    append("Content-Range: bytes $start-$end/$totalLen\r\n")
                } else {
                    append("HTTP/1.1 200 OK\r\n")
                }
                append("Content-Type: ${track.mimeType}\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Length: $contentLen\r\n")
                append("Connection: close\r\n\r\n")
            }
            output.write(headers.toByteArray(Charsets.ISO_8859_1))

            if (!headOnly && contentLen > 0) {
                RandomAccessFile(localFile, "r").use { raf ->
                    raf.seek(start)
                    val buffer = ByteArray(32768)
                    var remaining = contentLen
                    while (remaining > 0) {
                        val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                        val read = raf.read(buffer, 0, toRead)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            }
            output.flush()
            return
        }

        val uriStr = track.contentUriString
        if (!uriStr.isNullOrBlank()) {
            val uri = Uri.parse(uriStr)
            val totalLen = track.fileSizeBytes.takeIf { it > 0 } ?: runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            }.getOrDefault(-1L)

            if (totalLen > 0) {
                val (start, end, isPartial) = parseByteRange(rangeHeader, totalLen)
                val contentLen = (end - start + 1).coerceAtLeast(0L)
                val headers = buildString {
                    if (isPartial) {
                        append("HTTP/1.1 206 Partial Content\r\n")
                        append("Content-Range: bytes $start-$end/$totalLen\r\n")
                    } else {
                        append("HTTP/1.1 200 OK\r\n")
                    }
                    append("Content-Type: ${track.mimeType}\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    append("Content-Length: $contentLen\r\n")
                    append("Connection: close\r\n\r\n")
                }
                output.write(headers.toByteArray(Charsets.ISO_8859_1))
                if (!headOnly && contentLen > 0) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        skipFully(stream, start)
                        val buffer = ByteArray(32768)
                        var remaining = contentLen
                        while (remaining > 0) {
                            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                            val read = stream.read(buffer, 0, toRead)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            remaining -= read
                        }
                    }
                }
                output.flush()
            } else {
                val headers = "HTTP/1.1 200 OK\r\nContent-Type: ${track.mimeType}\r\nConnection: close\r\n\r\n"
                output.write(headers.toByteArray(Charsets.ISO_8859_1))
                if (!headOnly) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.copyTo(output, 32768)
                    }
                }
                output.flush()
            }
        }
    }

    private fun skipFully(stream: InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        val discard = ByteArray(8192)
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                val read = stream.read(discard, 0, minOf(discard.size.toLong(), remaining).toInt())
                if (read <= 0) break
                remaining -= read
            }
        }
    }

    private fun parseByteRange(rangeHeader: String?, totalLength: Long): Triple<Long, Long, Boolean> {
        if (rangeHeader.isNullOrBlank() || !rangeHeader.startsWith("bytes=")) {
            return Triple(0L, (totalLength - 1).coerceAtLeast(0L), false)
        }
        val spec = rangeHeader.removePrefix("bytes=").substringBefore(",").trim()
        val parts = spec.split("-")
        val start = parts.getOrNull(0)?.toLongOrNull() ?: 0L
        val end = parts.getOrNull(1)?.toLongOrNull()?.coerceAtMost(totalLength - 1) ?: (totalLength - 1)
        val clampedStart = start.coerceIn(0L, (totalLength - 1).coerceAtLeast(0L))
        val clampedEnd = end.coerceIn(clampedStart, (totalLength - 1).coerceAtLeast(clampedStart))
        return Triple(clampedStart, clampedEnd, true)
    }

    private suspend fun acceptWsLoop(serverSocket: ServerSocket) {
        while (isRunning && !serverSocket.isClosed) {
            val socket = try {
                serverSocket.accept()
            } catch (_: Exception) {
                break
            }
            scope.launch(Dispatchers.IO) {
                handleWsClient(socket)
            }
        }
    }

    private fun handleWsClient(socket: Socket) {
        var session: ClientSession? = null
        try {
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()

            val headerBytes = readHttpHeaders(input) ?: return
            val headerText = headerBytes.toString(Charsets.ISO_8859_1)
            val lines = headerText.split("\r\n")
            val wsKey = lines.firstOrNull { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
                ?.substringAfter(":")?.trim()

            if (wsKey.isNullOrBlank()) {
                output.write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n".toByteArray())
                output.flush()
                socket.close()
                return
            }

            val acceptKey = computeWebSocketAccept(wsKey)
            val handshake = buildString {
                append("HTTP/1.1 101 Switching Protocols\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Accept: $acceptKey\r\n\r\n")
            }
            output.write(handshake.toByteArray(Charsets.ISO_8859_1))
            output.flush()

            session = ClientSession(socket, output)
            wsClients.add(session)

            while (isRunning && session.isAlive && !socket.isClosed) {
                val frame = readWsFrame(input) ?: break
                when (frame.opcode) {
                    0x1 -> {
                        val text = frame.payload.toString(Charsets.UTF_8)
                        handleWsTextMessage(session, text)
                    }
                    0x8 -> {
                        break
                    }
                    0x9 -> {
                        session.sendPong(frame.payload)
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            session?.let { s ->
                wsClients.remove(s)
                s.participantId?.let { pid -> onParticipantDisconnected(pid) }
                s.close()
            } ?: runCatching { socket.close() }
        }
    }

    private fun handleWsTextMessage(session: ClientSession, rawText: String) {
        val json = runCatching { JSONObject(rawText) }.getOrNull() ?: return
        when (json.optString("type")) {
            ProtocolMessages.TYPE_SYNC_PING -> {
                val hostRecv = SystemClock.elapsedRealtime()
                val seq = json.optLong("seq", 0L)
                val clientSend = json.optLong("clientSendMs", 0L)
                val hostSend = SystemClock.elapsedRealtime()
                session.sendText(ProtocolMessages.encodeSyncPong(seq, clientSend, hostRecv, hostSend))
            }

            ProtocolMessages.TYPE_JOIN -> {
                val reqCode = json.optString("roomCode", "").trim()
                if (reqCode != roomCode) {
                    session.sendText(
                        ProtocolMessages.encodeJoinAck(
                            accepted = false,
                            roomCode = roomCode,
                            roomName = roomName,
                            hostIp = hostIp,
                            httpPort = httpPort,
                            sessionStartedAtMs = sessionStartedAtMs,
                            totalListenersJoined = totalListenersJoined,
                            reason = "Invalid room code"
                        )
                    )
                    session.close()
                    return
                }

                val pid = json.optString("participantId").ifBlank { "p_${System.nanoTime()}" }
                val name = json.optString("name", "Guest").take(24)
                val color = json.optString("avatarColorHex", "#38BDF8")
                session.participantId = pid

                val participant = Participant(
                    id = pid,
                    name = name,
                    avatarColorHex = color,
                    isHost = false,
                    status = DeviceStatus.CONNECTED,
                    rttMs = 0L,
                    driftMs = 0L
                )
                onParticipantJoined(participant)

                session.sendText(
                    ProtocolMessages.encodeJoinAck(
                        accepted = true,
                        roomCode = roomCode,
                        roomName = roomName,
                        hostIp = hostIp,
                        httpPort = httpPort,
                        sessionStartedAtMs = sessionStartedAtMs,
                        totalListenersJoined = totalListenersJoined + 1
                    )
                )

                // Send complete initial snapshot immediately
                session.sendText(
                    ProtocolMessages.encodeRoomSnapshot(
                        roomName = roomName,
                        hostIp = hostIp,
                        httpPort = httpPort,
                        sessionStartedAtMs = sessionStartedAtMs,
                        totalListenersJoined = totalListenersJoined,
                        tracks = currentTracks,
                        playbackState = currentPlaybackState,
                        participants = currentParticipants,
                        chatHistory = currentChatHistory,
                        songRequests = currentSongRequests,
                        audioSettings = currentAudioSettings
                    )
                )
            }

            ProtocolMessages.TYPE_PARTICIPANT_TELEMETRY -> {
                val pid = session.participantId ?: json.optString("participantId")
                val rtt = json.optLong("rttMs", 0L)
                val drift = json.optLong("driftMs", 0L)
                if (pid.isNotBlank()) {
                    onParticipantTelemetry(pid, rtt, drift)
                }
            }

            ProtocolMessages.TYPE_CHAT_MESSAGE -> {
                val msgObj = json.optJSONObject("message") ?: return
                val msg = ProtocolMessages.jsonToChat(msgObj)
                onChatMessageReceived(msg)
            }

            ProtocolMessages.TYPE_REACTION -> {
                val id = json.optString("id", "r_${System.nanoTime()}")
                val emoji = json.optString("emoji", "🔥")
                val senderName = json.optString("senderName", "Guest")
                val xFraction = json.optDouble("xFraction", 0.5).toFloat()
                onReactionReceived(id, emoji, senderName, xFraction)
            }

            ProtocolMessages.TYPE_SONG_REQUEST -> {
                val reqObj = json.optJSONObject("request") ?: return
                val req = ProtocolMessages.jsonToSongRequest(reqObj)
                onSongRequestReceived(req)
            }

            ProtocolMessages.TYPE_WEBRTC_ANSWER -> {
                val pid = session.participantId ?: json.optString("senderId")
                val sdp = json.optString("sdp")
                if (pid.isNotBlank() && sdp.isNotBlank()) {
                    onWebRtcAnswer(pid, sdp)
                }
            }

            ProtocolMessages.TYPE_WEBRTC_ICE -> {
                val pid = session.participantId ?: json.optString("senderId")
                val sdpMid = json.optString("sdpMid")
                val mLine = json.optInt("sdpMLineIndex", 0)
                val candidate = json.optString("candidate")
                if (pid.isNotBlank() && candidate.isNotBlank()) {
                    onWebRtcIceCandidate(pid, sdpMid, mLine, candidate)
                }
            }
        }
    }

    fun handleP2pDataMessage(peerId: String, rawText: String) {
        val json = runCatching { JSONObject(rawText) }.getOrNull() ?: return
        when (json.optString("type")) {
            ProtocolMessages.TYPE_SYNC_PING -> {
                val hostRecv = SystemClock.elapsedRealtime()
                val seq = json.optLong("seq", 0L)
                val clientSend = json.optLong("clientSendMs", 0L)
                val hostSend = SystemClock.elapsedRealtime()
                val pong = ProtocolMessages.encodeSyncPong(seq, clientSend, hostRecv, hostSend)
                if (webRtcPeerManager?.sendLowLatencyText(peerId, pong) != true) {
                    sendToParticipant(peerId, pong)
                }
            }

            ProtocolMessages.TYPE_PARTICIPANT_TELEMETRY -> {
                val rtt = json.optLong("rttMs", 0L)
                val drift = json.optLong("driftMs", 0L)
                onParticipantTelemetry(peerId, rtt, drift)
            }

            ProtocolMessages.TYPE_CHAT_MESSAGE -> {
                val msgObj = json.optJSONObject("message") ?: return
                onChatMessageReceived(ProtocolMessages.jsonToChat(msgObj))
            }

            ProtocolMessages.TYPE_REACTION -> {
                val id = json.optString("id", "r_${System.nanoTime()}")
                val emoji = json.optString("emoji", "🔥")
                val senderName = json.optString("senderName", "Guest")
                val xFraction = json.optDouble("xFraction", 0.5).toFloat()
                onReactionReceived(id, emoji, senderName, xFraction)
            }

            ProtocolMessages.TYPE_SONG_REQUEST -> {
                val reqObj = json.optJSONObject("request") ?: return
                onSongRequestReceived(ProtocolMessages.jsonToSongRequest(reqObj))
            }
        }
    }

    private fun startUdpDiscovery() {
        udpListenerJob = scope.launch(Dispatchers.IO) {
            try {
                val sock = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(DISCOVERY_UDP_PORT))
                }
                udpSocket = sock
                val buf = ByteArray(1024)
                val packet = DatagramPacket(buf, buf.size)
                while (isRunning && isActive && !sock.isClosed) {
                    sock.receive(packet)
                    val msg = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    if (msg.startsWith(PROBE_MAGIC)) {
                        val payload = buildBeaconPayload().toByteArray(Charsets.UTF_8)
                        val reply = DatagramPacket(payload, payload.size, packet.address, packet.port)
                        runCatching { sock.send(reply) }
                    }
                }
            } catch (_: Exception) {
            }
        }

        udpBeaconJob = scope.launch(Dispatchers.IO) {
            val senderSock = runCatching {
                DatagramSocket().apply { broadcast = true }
            }.getOrNull() ?: return@launch

            senderSock.use { sock ->
                while (isRunning && isActive) {
                    val payload = buildBeaconPayload().toByteArray(Charsets.UTF_8)
                    val targets = NetworkUtils.getBroadcastAndGatewayTargets(context)
                    targets.forEach { addr ->
                        runCatching {
                            val packet = DatagramPacket(payload, payload.size, addr, DISCOVERY_UDP_PORT)
                            sock.send(packet)
                        }
                    }
                    delay(1200L)
                }
            }
        }
    }

    private fun buildBeaconPayload(): String {
        val currentTrackTitle = currentTracks.firstOrNull { it.id == currentPlaybackState.trackId }?.title
            ?: "Ready to Play"
        val listenerCount = currentParticipants.count { it.status == DeviceStatus.CONNECTED }
        return JSONObject().apply {
            put("magic", DISCOVERY_MAGIC)
            put("roomCode", roomCode)
            put("roomName", roomName)
            put("hostName", hostName)
            put("hostIp", hostIp)
            put("wsPort", wsPort)
            put("httpPort", httpPort)
            put("currentTrackTitle", currentTrackTitle)
            put("listenerCount", listenerCount)
        }.toString()
    }

    fun stop() {
        isRunning = false
        udpBeaconJob?.cancel()
        udpListenerJob?.cancel()
        serverJob?.cancel()
        wsClients.forEach { it.close() }
        wsClients.clear()
        runCatching { udpSocket?.close() }
        runCatching { httpServerSocket?.close() }
        runCatching { wsServerSocket?.close() }
        udpSocket = null
        httpServerSocket = null
        wsServerSocket = null
    }

    private data class WsFrame(val opcode: Int, val payload: ByteArray)

    private fun readHttpHeaders(input: InputStream): ByteArray? {
        val baos = ByteArrayOutputStream()
        var state = 0
        while (baos.size() < 16384) {
            val b = input.read()
            if (b == -1) return null
            baos.write(b)
            state = when {
                state == 0 && b == '\r'.code -> 1
                state == 1 && b == '\n'.code -> 2
                state == 2 && b == '\r'.code -> 3
                state == 3 && b == '\n'.code -> return baos.toByteArray()
                b == '\r'.code -> 1
                else -> 0
            }
        }
        return null
    }

    private fun readWsFrame(input: InputStream): WsFrame? {
        val b0 = input.read()
        if (b0 == -1) return null
        val b1 = input.read()
        if (b1 == -1) return null

        val opcode = b0 and 0x0F
        val masked = (b1 and 0x80) != 0
        var len = (b1 and 0x7F).toLong()

        if (len == 126L) {
            val h = input.read()
            val l = input.read()
            if (h == -1 || l == -1) return null
            len = ((h shl 8) or l).toLong()
        } else if (len == 127L) {
            var acc = 0L
            for (i in 0 until 8) {
                val v = input.read()
                if (v == -1) return null
                acc = (acc shl 8) or v.toLong()
            }
            len = acc
        }

        if (len < 0 || len > 4 * 1024 * 1024) return null

        val maskKey = ByteArray(4)
        if (masked) {
            if (!readExact(input, maskKey, 4)) return null
        }

        val payload = ByteArray(len.toInt())
        if (len > 0 && !readExact(input, payload, len.toInt())) return null

        if (masked) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
            }
        }
        return WsFrame(opcode, payload)
    }

    private fun readExact(input: InputStream, dest: ByteArray, count: Int): Boolean {
        var offset = 0
        while (offset < count) {
            val read = input.read(dest, offset, count - offset)
            if (read <= 0) return false
            offset += read
        }
        return true
    }

    private fun computeWebSocketAccept(key: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest((key + WS_GUID).toByteArray(Charsets.ISO_8859_1))
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    private fun encodeWsFrame(opcode: Int, payload: ByteArray): ByteArray {
        val len = payload.size
        val header = when {
            len <= 125 -> byteArrayOf((0x80 or opcode).toByte(), len.toByte())
            len <= 65535 -> byteArrayOf(
                (0x80 or opcode).toByte(),
                126.toByte(),
                ((len shr 8) and 0xFF).toByte(),
                (len and 0xFF).toByte()
            )
            else -> byteArrayOf(
                (0x80 or opcode).toByte(),
                127.toByte(),
                0, 0, 0, 0,
                ((len shr 24) and 0xFF).toByte(),
                ((len shr 16) and 0xFF).toByte(),
                ((len shr 8) and 0xFF).toByte(),
                (len and 0xFF).toByte()
            )
        }
        return header + payload
    }
}
