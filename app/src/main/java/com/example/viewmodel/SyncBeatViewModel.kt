package com.example.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioBufferManager
import com.example.audio.AudioDuckingHelper
import com.example.audio.AudioWaveformExtractor
import com.example.audio.BuiltInPartyTracks
import com.example.audio.SyncedAudioEngine
import com.example.data.SettingsRepository
import com.example.model.AudioTrack
import com.example.model.ChatMessage
import com.example.model.DeviceStatus
import com.example.model.DiscoveredRoom
import com.example.model.FloatingReaction
import com.example.model.Participant
import com.example.model.PartyAudioSettings
import com.example.model.PlaybackState
import com.example.model.RepeatMode
import com.example.model.RoomConnectionInfo
import com.example.model.SessionRole
import com.example.model.SongRequest
import com.example.model.SongRequestStatus
import com.example.model.SyncTelemetry
import com.example.model.TransportMode
import com.example.model.UserSettings
import com.example.network.NetworkUtils
import com.example.network.SyncBeatClient
import com.example.network.SyncBeatHostServer
import com.example.network.WebRtcPeerManager
import com.example.qr.QrCodeUtils
import com.example.service.SyncBeatForegroundService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.abs
import kotlin.random.Random

data class SyncBeatUiState(
    val isReady: Boolean = false,
    val isInParty: Boolean = false,
    val role: SessionRole = SessionRole.HOST,
    val connectionStatus: DeviceStatus = DeviceStatus.DISCONNECTED,
    val roomInfo: RoomConnectionInfo = RoomConnectionInfo(),
    val localHostIp: String = "127.0.0.1",
    val pendingRoomCode: String = "",
    val queue: List<AudioTrack> = emptyList(),
    val playbackState: PlaybackState = PlaybackState(),
    val localVolume: Float = 0.85f,
    val effectiveVolume: Float = 0.85f,
    val partyAudioSettings: PartyAudioSettings = PartyAudioSettings(),
    val isPartyChatActive: Boolean = false,
    val isAudioDucked: Boolean = false,
    val participants: List<Participant> = emptyList(),
    val chatMessages: List<ChatMessage> = emptyList(),
    val songRequests: List<SongRequest> = emptyList(),
    val floatingReactions: List<FloatingReaction> = emptyList(),
    val discoveredRooms: List<DiscoveredRoom> = emptyList(),
    val settings: UserSettings = UserSettings(),
    val sessionDurationSeconds: Long = 0L,
    val isJoining: Boolean = false,
    val statusBanner: String? = null
)

class SyncBeatViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val settingsRepository = SettingsRepository(appContext)
    private val myParticipantId = "dev_${UUID.randomUUID().toString().take(8)}"

    private val _uiState = MutableStateFlow(SyncBeatUiState())
    val uiState: StateFlow<SyncBeatUiState> = _uiState.asStateFlow()

    private lateinit var audioEngine: SyncedAudioEngine
    private lateinit var audioBufferManager: AudioBufferManager
    private lateinit var hostServer: SyncBeatHostServer
    private lateinit var client: SyncBeatClient
    private lateinit var webRtcManager: WebRtcPeerManager

    val positionMs: StateFlow<Long>
        get() = audioEngine.positionMs
    val durationMs: StateFlow<Long>
        get() = audioEngine.durationMs
    val telemetry: StateFlow<SyncTelemetry>
        get() = audioEngine.telemetry
    val frequencySpectrum: StateFlow<com.example.audio.FrequencySpectrumFrame>
        get() = audioBufferManager.frequencySpectrum

    private var lastTelemetrySentMs = 0L
    private var isChatTabActive = false
    private var isChatTypingActive = false
    private var isRecentChatMessageActive = false
    private var recentChatHoldJob: Job? = null
    private var duckRampJob: Job? = null
    private var currentDuckMultiplier = 1.0f

    init {
        client = SyncBeatClient(
            context = appContext,
            scope = viewModelScope,
            onConnectedSnapshot = { roomName, hostIp, httpPort, startedAt, totalJoined, tracks, playback, participants, chatHistory, requests ->
                val curRoom = _uiState.value.roomInfo
                _uiState.update { state ->
                    state.copy(
                        isInParty = true,
                        isJoining = false,
                        role = SessionRole.LISTENER,
                        connectionStatus = DeviceStatus.CONNECTED,
                        roomInfo = curRoom.copy(
                            roomName = roomName,
                            hostIp = hostIp,
                            httpPort = httpPort,
                            sessionStartedAtMs = startedAt,
                            totalListenersJoined = totalJoined,
                            qrPayload = QrCodeUtils.buildRoomPayload(hostIp, curRoom.wsPort, httpPort, curRoom.roomCode)
                        ),
                        queue = tracks,
                        playbackState = playback,
                        participants = participants,
                        chatMessages = chatHistory,
                        songRequests = requests,
                        statusBanner = null
                    )
                }
                val activeTrack = tracks.firstOrNull { it.id == playback.trackId } ?: tracks.firstOrNull()
                audioEngine.applyState(SessionRole.LISTENER, activeTrack, playback)
                audioBufferManager.onTransportUpdated(SessionRole.LISTENER, activeTrack, playback)
                SyncBeatForegroundService.startOrUpdate(
                    appContext,
                    "SYNCBEAT • $roomName",
                    "Synced to $hostIp"
                )
            },
            onPlaybackState = { playback ->
                _uiState.update { it.copy(playbackState = playback) }
                val activeTrack = _uiState.value.queue.firstOrNull { it.id == playback.trackId }
                audioEngine.applyState(SessionRole.LISTENER, activeTrack, playback)
                audioBufferManager.onTransportUpdated(SessionRole.LISTENER, activeTrack, playback)
            },
            onQueueUpdate = { tracks ->
                _uiState.update { it.copy(queue = tracks) }
                val activeTrack = tracks.firstOrNull { it.id == _uiState.value.playbackState.trackId }
                if (activeTrack != null) {
                    audioEngine.applyState(SessionRole.LISTENER, activeTrack, _uiState.value.playbackState)
                    audioBufferManager.onTransportUpdated(SessionRole.LISTENER, activeTrack, _uiState.value.playbackState)
                }
            },
            onParticipantsUpdate = { list, totalJoined ->
                _uiState.update { state ->
                    state.copy(
                        participants = list,
                        roomInfo = state.roomInfo.copy(totalListenersJoined = totalJoined)
                    )
                }
            },
            onChatMessage = { msg ->
                _uiState.update { state ->
                    val updated = (state.chatMessages + msg).takeLast(120)
                    state.copy(chatMessages = updated)
                }
                if (!msg.isSystem) {
                    notifyUserChatMessageActivity()
                }
            },
            onReaction = { id, emoji, senderName, xFraction ->
                spawnFloatingReaction(id, emoji, senderName, xFraction)
            },
            onSongRequestUpdate = { requests ->
                _uiState.update { it.copy(songRequests = requests) }
            },
            onClockUpdated = { offsetMs, rttMs, sampleCount ->
                audioEngine.updateClockStats(offsetMs, rttMs, sampleCount)
                val now = SystemClock.elapsedRealtime()
                if (now - lastTelemetrySentMs >= 1800L) {
                    lastTelemetrySentMs = now
                    client.sendTelemetry(audioEngine.telemetry.value.driftMs)
                }
            },
            onConnectionError = { reason ->
                _uiState.update {
                    it.copy(
                        isJoining = false,
                        statusBanner = reason
                    )
                }
            },
            onKicked = { reason ->
                leaveSessionInternal(banner = "Disconnected: $reason")
            },
            onWebRtcOffer = { senderId, sdp ->
                webRtcManager.handleRemoteOffer(senderId, sdp)
            },
            onWebRtcIceCandidate = { senderId, sdpMid, sdpMLineIndex, candidate ->
                webRtcManager.handleRemoteIceCandidate(senderId, sdpMid, sdpMLineIndex, candidate)
            },
            onAudioSettingsUpdated = { audioSettings ->
                _uiState.update { it.copy(partyAudioSettings = audioSettings) }
                refreshAudioDucking()
            }
        )

        audioEngine = SyncedAudioEngine(
            context = appContext,
            scope = viewModelScope,
            estimatedHostNowMs = { client.estimatedHostNowMs() },
            onHostTrackCompleted = {
                viewModelScope.launch { handleHostTrackCompleted() }
            },
            onClientTelemetrySample = { driftMs, _ ->
                val now = SystemClock.elapsedRealtime()
                if (now - lastTelemetrySentMs >= 1800L) {
                    lastTelemetrySentMs = now
                    client.sendTelemetry(driftMs)
                }
            }
        )

        hostServer = SyncBeatHostServer(
            context = appContext,
            scope = viewModelScope,
            onParticipantJoined = { participant ->
                viewModelScope.launch { handleHostParticipantJoined(participant) }
            },
            onParticipantDisconnected = { pid ->
                viewModelScope.launch { handleHostParticipantDisconnected(pid) }
            },
            onParticipantTelemetry = { pid, rttMs, driftMs ->
                _uiState.update { state ->
                    val updated = state.participants.map {
                        if (it.id == pid) it.copy(rttMs = rttMs, driftMs = driftMs, status = DeviceStatus.CONNECTED)
                        else it
                    }
                    state.copy(participants = updated)
                }
                hostServer.broadcastParticipants(_uiState.value.participants, _uiState.value.roomInfo.totalListenersJoined)
            },
            onChatMessageReceived = { msg ->
                appendAndBroadcastHostChat(msg)
            },
            onReactionReceived = { id, emoji, senderName, xFraction ->
                spawnFloatingReaction(id, emoji, senderName, xFraction)
                hostServer.broadcastReaction(id, emoji, senderName, xFraction)
            },
            onSongRequestReceived = { req ->
                val updated = listOf(req) + _uiState.value.songRequests
                _uiState.update { it.copy(songRequests = updated) }
                hostServer.broadcastSongRequests(updated)
            },
            onWebRtcAnswer = { peerId, sdp ->
                webRtcManager.handleRemoteAnswer(peerId, sdp)
            },
            onWebRtcIceCandidate = { peerId, sdpMid, sdpMLineIndex, candidate ->
                webRtcManager.handleRemoteIceCandidate(peerId, sdpMid, sdpMLineIndex, candidate)
            }
        )

        webRtcManager = WebRtcPeerManager(
            context = appContext,
            scope = viewModelScope,
            localPeerId = myParticipantId,
            onSendSignaling = { targetPeerId, jsonText ->
                if (_uiState.value.role == SessionRole.HOST) {
                    hostServer.sendToParticipant(targetPeerId, jsonText)
                } else {
                    client.sendSignaling(jsonText)
                }
            },
            onDataTextMessage = { fromPeerId, text, _ ->
                if (_uiState.value.role == SessionRole.HOST) {
                    hostServer.handleP2pDataMessage(fromPeerId, text)
                } else {
                    client.handleIncomingMessage(text)
                }
            },
            onPeerStateChanged = { peerId, iceState, isDataChannelOpen ->
                if (_uiState.value.role == SessionRole.HOST) {
                    _uiState.update { state ->
                        val updated = state.participants.map {
                            if (it.id == peerId) {
                                it.copy(
                                    transportMode = if (isDataChannelOpen) TransportMode.WEBRTC_P2P else TransportMode.WS_SIGNALING,
                                    iceState = iceState
                                )
                            } else it
                        }
                        state.copy(participants = updated)
                    }
                    audioEngine.updateWebRtcStatus(
                        isP2pActive = webRtcManager.hasAnyOpenP2pPeer(),
                        iceState = iceState,
                        p2pBytes = audioEngine.telemetry.value.p2pTransferredBytes
                    )
                    hostServer.broadcastParticipants(_uiState.value.participants, _uiState.value.roomInfo.totalListenersJoined)
                } else {
                    audioEngine.updateWebRtcStatus(
                        isP2pActive = isDataChannelOpen,
                        iceState = iceState,
                        p2pBytes = audioEngine.telemetry.value.p2pTransferredBytes
                    )
                    if (isDataChannelOpen) {
                        _uiState.value.playbackState.trackId?.let { activeId ->
                            client.requestTrackOverP2p(activeId)
                        }
                    }
                }
            },
            onP2pBytesProgress = { peerId, trackId, totalBytes, complete, localFilePath ->
                if (_uiState.value.role == SessionRole.HOST) {
                    _uiState.update { state ->
                        val updated = state.participants.map {
                            if (it.id == peerId) it.copy(p2pBytesTransferred = totalBytes) else it
                        }
                        state.copy(participants = updated)
                    }
                    audioEngine.updateWebRtcStatus(
                        isP2pActive = webRtcManager.hasAnyOpenP2pPeer(),
                        iceState = webRtcManager.getIceState(peerId),
                        p2pBytes = _uiState.value.participants.sumOf { it.p2pBytesTransferred }
                    )
                } else {
                    audioEngine.updateWebRtcStatus(
                        isP2pActive = webRtcManager.isPeerP2pReady(SyncBeatClient.HOST_PEER_ID),
                        iceState = webRtcManager.getIceState(SyncBeatClient.HOST_PEER_ID),
                        p2pBytes = totalBytes
                    )
                    if (complete && !localFilePath.isNullOrBlank()) {
                        audioEngine.registerP2pCachedTrack(trackId, localFilePath)
                    }
                }
            },
            onP2pAudioRequested = { peerId, trackId ->
                val track = _uiState.value.queue.firstOrNull { it.id == trackId }
                if (track != null && _uiState.value.role == SessionRole.HOST) {
                    webRtcManager.streamTrackOverP2p(peerId, track)
                }
            },
            onAudioFrameReceived = { _, frameBytes ->
                audioBufferManager.onIncomingBinaryAudioFrame(frameBytes)
            }
        )

        audioBufferManager = AudioBufferManager(
            context = appContext,
            scope = viewModelScope,
            estimatedHostNowMs = { client.estimatedHostNowMs() },
            getClockOffsetMs = { client.getClockOffsetMs() },
            sendBinaryFrame = { frameBytes -> webRtcManager.broadcastAudioFrame(frameBytes) },
            hasOpenAudioPeer = { webRtcManager.hasOpenAudioStreamChannel() }
        )

        hostServer.attachWebRtcManager(webRtcManager)
        client.attachWebRtcManager(webRtcManager)

        observeSettings()
        observeClientState()
        observeAudioBufferStats()
        initializeLocalEnvironment()
        startSessionTicker()
    }

    private fun observeAudioBufferStats() {
        viewModelScope.launch {
            audioBufferManager.bufferStats.collect { stats ->
                audioEngine.updateAudioBufferStats(
                    stats = stats,
                    isListenerRenderingP2p = audioBufferManager.isActivelyRenderingP2pAudio(),
                    listenerPosMs = audioBufferManager.listenerPositionMs.value
                )
            }
        }
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsRepository.settingsFlow.collect { prefs ->
                _uiState.update { state ->
                    val updatedRoomAudio = if (state.role == SessionRole.HOST || !state.isInParty) {
                        PartyAudioSettings(
                            chatDuckingEnabled = prefs.chatDuckingEnabled,
                            duckingReductionPercent = prefs.chatDuckingReductionPercent
                        )
                    } else {
                        state.partyAudioSettings
                    }
                    state.copy(settings = prefs, partyAudioSettings = updatedRoomAudio)
                }
                audioEngine.updateCalibration(prefs.calibrationOffsetMs, prefs.driftSensitivityMs)
                audioBufferManager.setCalibrationOffsetMs(prefs.calibrationOffsetMs)
                client.setAutoReconnect(prefs.autoReconnect)
                refreshAudioDucking()
            }
        }
    }

    private fun observeClientState() {
        viewModelScope.launch {
            client.connectionStatus.collect { status ->
                if (_uiState.value.role == SessionRole.LISTENER && _uiState.value.isInParty) {
                    _uiState.update { it.copy(connectionStatus = status) }
                    if (status == DeviceStatus.RECONNECTING) {
                        audioEngine.markReconnecting()
                    }
                }
            }
        }
        viewModelScope.launch {
            client.discoveredRooms.collect { rooms ->
                _uiState.update { it.copy(discoveredRooms = rooms) }
            }
        }
    }

    private fun initializeLocalEnvironment() {
        viewModelScope.launch {
            val ip = NetworkUtils.getLocalHostIpAddress(appContext)
            val builtIn = BuiltInPartyTracks.ensureBuiltInTracks(appContext)
            val initialCode = generateRoomCode()
            _uiState.update { state ->
                state.copy(
                    isReady = true,
                    localHostIp = ip,
                    pendingRoomCode = initialCode,
                    queue = builtIn,
                    playbackState = PlaybackState(trackId = builtIn.firstOrNull()?.id)
                )
            }
        }
    }

    private fun startSessionTicker() {
        viewModelScope.launch {
            while (isActive) {
                val state = _uiState.value
                if (state.isInParty) {
                    val elapsedSec = ((System.currentTimeMillis() - state.roomInfo.sessionStartedAtMs) / 1000L)
                        .coerceAtLeast(0L)
                    _uiState.update { it.copy(sessionDurationSeconds = elapsedSec) }
                }
                delay(1000L)
            }
        }
    }

    fun refreshLocalNetworkInfo() {
        val ip = NetworkUtils.getLocalHostIpAddress(appContext)
        _uiState.update { it.copy(localHostIp = ip) }
    }

    fun regeneratePendingRoomCode() {
        _uiState.update { it.copy(pendingRoomCode = generateRoomCode()) }
    }

    fun startRoomDiscovery() {
        client.startDiscovery()
    }

    fun stopRoomDiscovery() {
        client.stopDiscovery()
    }

    fun startHostingParty(roomName: String) {
        viewModelScope.launch {
            val cleanRoomName = roomName.trim().ifBlank { "SYNCBEAT Party" }
            val state = _uiState.value
            val tracks = state.queue.ifEmpty { BuiltInPartyTracks.ensureBuiltInTracks(appContext) }
            val code = state.pendingRoomCode.takeIf { it.length == 6 } ?: generateRoomCode()
            val settings = state.settings

            val hostParticipant = Participant(
                id = myParticipantId,
                name = settings.nickname,
                avatarColorHex = settings.avatarColorHex,
                isHost = true,
                status = DeviceStatus.CONNECTED,
                rttMs = 0L,
                driftMs = 0L
            )

            val initialPlayback = PlaybackState(
                trackId = tracks.firstOrNull()?.id,
                isPlaying = false,
                anchorPositionMs = 0L,
                anchorHostTimeMs = SystemClock.elapsedRealtime(),
                shuffle = false,
                repeatMode = RepeatMode.ALL,
                sequenceNumber = 1L
            )

            val hostAudioSettings = PartyAudioSettings(
                chatDuckingEnabled = settings.chatDuckingEnabled,
                duckingReductionPercent = settings.chatDuckingReductionPercent
            )
            val (httpPort, wsPort) = hostServer.start(
                roomCode = code,
                roomName = cleanRoomName,
                hostName = settings.nickname,
                initialTracks = tracks,
                initialState = initialPlayback,
                initialParticipants = listOf(hostParticipant),
                initialAudioSettings = hostAudioSettings
            )

            val hostIp = hostServer.hostIp
            val qrPayload = QrCodeUtils.buildRoomPayload(hostIp, wsPort, httpPort, code)
            val welcomeChat = ChatMessage(
                id = "sys_${System.nanoTime()}",
                senderId = "system",
                senderName = "SYNCBEAT",
                senderColorHex = "#38BDF8",
                text = "Party room $code is live on $hostIp. Share the QR or 6-digit code on the same Wi-Fi!",
                isSystem = true
            )

            _uiState.update { s ->
                s.copy(
                    isInParty = true,
                    role = SessionRole.HOST,
                    connectionStatus = DeviceStatus.CONNECTED,
                    localHostIp = hostIp,
                    queue = tracks,
                    playbackState = initialPlayback,
                    participants = listOf(hostParticipant),
                    chatMessages = listOf(welcomeChat),
                    songRequests = emptyList(),
                    partyAudioSettings = hostAudioSettings,
                    statusBanner = null,
                    roomInfo = RoomConnectionInfo(
                        roomCode = code,
                        roomName = cleanRoomName,
                        hostIp = hostIp,
                        wsPort = wsPort,
                        httpPort = httpPort,
                        sessionStartedAtMs = System.currentTimeMillis(),
                        totalListenersJoined = 1,
                        isServerRunning = true,
                        qrPayload = qrPayload
                    )
                )
            }

            audioEngine.applyState(SessionRole.HOST, tracks.firstOrNull(), initialPlayback)
            audioBufferManager.onTransportUpdated(SessionRole.HOST, tracks.firstOrNull(), initialPlayback)
            SyncBeatForegroundService.startOrUpdate(
                appContext,
                "Hosting $cleanRoomName ($code)",
                "Local server live on $hostIp"
            )
        }
    }

    fun joinRoomByDiscovered(room: DiscoveredRoom) {
        joinRoomDirect(
            hostIp = room.hostIp,
            wsPort = room.wsPort,
            httpPort = room.httpPort,
            roomCode = room.roomCode
        )
    }

    fun joinRoomByCode(roomCode: String, manualHostIp: String? = null) {
        val cleanCode = roomCode.trim()
        if (cleanCode.length != 6) {
            _uiState.update { it.copy(statusBanner = "Enter a valid 6-digit room code.") }
            return
        }
        val discovered = _uiState.value.discoveredRooms.firstOrNull { it.roomCode == cleanCode }
        if (discovered != null) {
            joinRoomByDiscovered(discovered)
            return
        }
        if (!manualHostIp.isNullOrBlank()) {
            joinRoomDirect(
                hostIp = manualHostIp.trim(),
                wsPort = SyncBeatHostServer.DEFAULT_WS_PORT,
                httpPort = SyncBeatHostServer.DEFAULT_HTTP_PORT,
                roomCode = cleanCode
            )
            return
        }
        _uiState.update {
            it.copy(statusBanner = "Searching Wi-Fi for room $cleanCode... Or scan QR / enter Host IP.")
        }
    }

    fun joinRoomDirect(hostIp: String, wsPort: Int, httpPort: Int, roomCode: String) {
        val cleanIp = hostIp.trim()
        val cleanCode = roomCode.trim()
        if (cleanIp.isBlank() || cleanCode.length != 6) {
            _uiState.update { it.copy(statusBanner = "Invalid Host IP or 6-digit room code.") }
            return
        }
        val settings = _uiState.value.settings
        _uiState.update {
            it.copy(
                isJoining = true,
                role = SessionRole.LISTENER,
                statusBanner = null,
                roomInfo = it.roomInfo.copy(
                    roomCode = cleanCode,
                    hostIp = cleanIp,
                    wsPort = wsPort,
                    httpPort = httpPort
                )
            )
        }
        client.connect(
            hostIp = cleanIp,
            wsPort = wsPort,
            roomCode = cleanCode,
            participantId = myParticipantId,
            nickname = settings.nickname,
            avatarColorHex = settings.avatarColorHex
        )
    }

    fun togglePlayPause() {
        val state = _uiState.value
        if (state.role != SessionRole.HOST) return
        val currentTrackId = state.playbackState.trackId ?: state.queue.firstOrNull()?.id ?: return
        val nowElapsed = SystemClock.elapsedRealtime()
        val curPos = audioEngine.currentPositionSnapshotMs()

        val nextPlayback = if (state.playbackState.isPlaying) {
            state.playbackState.copy(
                trackId = currentTrackId,
                isPlaying = false,
                anchorPositionMs = curPos,
                anchorHostTimeMs = nowElapsed,
                sequenceNumber = state.playbackState.sequenceNumber + 1
            )
        } else {
            // Schedule 280ms lead time so all Wi-Fi listeners launch on the exact same beat!
            state.playbackState.copy(
                trackId = currentTrackId,
                isPlaying = true,
                anchorPositionMs = curPos,
                anchorHostTimeMs = nowElapsed + 280L,
                sequenceNumber = state.playbackState.sequenceNumber + 1
            )
        }
        commitHostPlaybackState(nextPlayback)
    }

    fun seekTo(positionMs: Long) {
        val state = _uiState.value
        if (state.role != SessionRole.HOST) return
        val nowElapsed = SystemClock.elapsedRealtime()
        val lead = if (state.playbackState.isPlaying) 240L else 0L
        val nextPlayback = state.playbackState.copy(
            isPlaying = state.playbackState.isPlaying,
            anchorPositionMs = positionMs.coerceAtLeast(0L),
            anchorHostTimeMs = nowElapsed + lead,
            sequenceNumber = state.playbackState.sequenceNumber + 1
        )
        commitHostPlaybackState(nextPlayback)
    }

    fun selectTrack(track: AudioTrack, autoPlay: Boolean = true) {
        val state = _uiState.value
        if (state.role != SessionRole.HOST) return
        val nowElapsed = SystemClock.elapsedRealtime()
        val nextPlayback = state.playbackState.copy(
            trackId = track.id,
            isPlaying = autoPlay,
            anchorPositionMs = 0L,
            anchorHostTimeMs = if (autoPlay) nowElapsed + 360L else nowElapsed,
            sequenceNumber = state.playbackState.sequenceNumber + 1
        )
        commitHostPlaybackState(nextPlayback)
    }

    fun nextTrack() {
        val state = _uiState.value
        if (state.role != SessionRole.HOST || state.queue.isEmpty()) return
        val idx = state.queue.indexOfFirst { it.id == state.playbackState.trackId }
        val nextIdx = if (state.playbackState.shuffle && state.queue.size > 1) {
            val candidates = state.queue.indices.filter { it != idx }
            candidates.random()
        } else {
            if (idx < 0 || idx + 1 >= state.queue.size) 0 else idx + 1
        }
        selectTrack(state.queue[nextIdx], autoPlay = true)
    }

    fun previousTrack() {
        val state = _uiState.value
        if (state.role != SessionRole.HOST || state.queue.isEmpty()) return
        if (audioEngine.currentPositionSnapshotMs() > 3500L) {
            seekTo(0L)
            return
        }
        val idx = state.queue.indexOfFirst { it.id == state.playbackState.trackId }
        val prevIdx = if (idx <= 0) state.queue.lastIndex else idx - 1
        selectTrack(state.queue[prevIdx], autoPlay = state.playbackState.isPlaying)
    }

    fun toggleShuffle() {
        val state = _uiState.value
        if (state.role != SessionRole.HOST) return
        commitHostPlaybackState(
            state.playbackState.copy(
                shuffle = !state.playbackState.shuffle,
                sequenceNumber = state.playbackState.sequenceNumber + 1
            )
        )
    }

    fun cycleRepeatMode() {
        val state = _uiState.value
        if (state.role != SessionRole.HOST) return
        val next = when (state.playbackState.repeatMode) {
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
            RepeatMode.OFF -> RepeatMode.ALL
        }
        commitHostPlaybackState(
            state.playbackState.copy(
                repeatMode = next,
                sequenceNumber = state.playbackState.sequenceNumber + 1
            )
        )
    }

    fun importAudioUri(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                appContext.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val meta = AudioWaveformExtractor.extractMetadataAndWaveform(appContext, uri)
            val artworkKeys = listOf("pulse", "horizon", "voltage")
            val newTrack = AudioTrack(
                id = "trk_${UUID.randomUUID().toString().take(8)}",
                title = meta.title,
                artist = meta.artist,
                durationMs = meta.durationMs,
                artworkKey = artworkKeys[_uiState.value.queue.size % artworkKeys.size],
                mimeType = meta.mimeType,
                fileSizeBytes = meta.fileSizeBytes,
                contentUriString = uri.toString(),
                waveformBins = meta.waveformBins
            )
            val updatedQueue = _uiState.value.queue + newTrack
            addImportedTrack(newTrack)
        }
    }

    fun addImportedTrack(track: AudioTrack) {
        val currentQueue = _uiState.value.queue
        val updatedQueue = if (currentQueue.any { it.id == track.id }) {
            currentQueue.map { if (it.id == track.id) track else it }
        } else {
            currentQueue + track
        }
        _uiState.update { it.copy(queue = updatedQueue) }
        if (_uiState.value.isInParty && _uiState.value.role == SessionRole.HOST) {
            hostServer.broadcastQueue(updatedQueue)
            val pcmNote = if (!track.pcmStreamPath.isNullOrBlank()) " (PCM WebRTC stream ready)" else ""
            appendAndBroadcastHostChat(
                ChatMessage(
                    id = "sys_${System.nanoTime()}",
                    senderId = "system",
                    senderName = "SYNCBEAT",
                    senderColorHex = "#8B5CF6",
                    text = "Added \"${track.title}\" to party queue$pcmNote.",
                    isSystem = true
                )
            )
        }
    }

    fun setLocalVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        val effective = AudioDuckingHelper.computeEffectiveVolume(clamped, currentDuckMultiplier)
        _uiState.update { it.copy(localVolume = clamped, effectiveVolume = effective) }
        audioEngine.setLocalVolume(effective)
        audioBufferManager.setLocalVolume(effective)
    }

    fun setChatTabActive(active: Boolean) {
        if (isChatTabActive == active) return
        isChatTabActive = active
        refreshAudioDucking()
    }

    fun setChatTypingActive(active: Boolean) {
        if (isChatTypingActive == active) return
        isChatTypingActive = active
        refreshAudioDucking()
    }

    private fun notifyUserChatMessageActivity() {
        isRecentChatMessageActive = true
        refreshAudioDucking()
        recentChatHoldJob?.cancel()
        recentChatHoldJob = viewModelScope.launch {
            delay(AudioDuckingHelper.CHAT_ACTIVE_HOLD_MS)
            isRecentChatMessageActive = false
            refreshAudioDucking()
        }
    }

    private fun refreshAudioDucking() {
        val state = _uiState.value
        val chatActive = isChatTabActive || isChatTypingActive || isRecentChatMessageActive
        val targetMultiplier = AudioDuckingHelper.computeTargetDuckMultiplier(
            audioSettings = state.partyAudioSettings,
            isPartyChatActive = chatActive
        )
        val shouldDuck = state.partyAudioSettings.chatDuckingEnabled && chatActive
        _uiState.update {
            it.copy(
                isPartyChatActive = chatActive,
                isAudioDucked = shouldDuck
            )
        }

        if (abs(currentDuckMultiplier - targetMultiplier) < 0.005f) {
            currentDuckMultiplier = targetMultiplier
            val effective = AudioDuckingHelper.computeEffectiveVolume(_uiState.value.localVolume, currentDuckMultiplier)
            _uiState.update { it.copy(effectiveVolume = effective) }
            audioEngine.setLocalVolume(effective)
            audioBufferManager.setLocalVolume(effective)
            return
        }

        duckRampJob?.cancel()
        duckRampJob = viewModelScope.launch {
            val start = currentDuckMultiplier
            val steps = 12
            for (step in 1..steps) {
                val frac = step / steps.toFloat()
                currentDuckMultiplier = start + (targetMultiplier - start) * frac
                val effective = AudioDuckingHelper.computeEffectiveVolume(_uiState.value.localVolume, currentDuckMultiplier)
                _uiState.update { it.copy(effectiveVolume = effective) }
                audioEngine.setLocalVolume(effective)
                audioBufferManager.setLocalVolume(effective)
                delay(28L)
            }
            currentDuckMultiplier = targetMultiplier
        }
    }

    fun sendChatMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val settings = _uiState.value.settings
        val msg = ChatMessage(
            id = "msg_${System.nanoTime()}",
            senderId = myParticipantId,
            senderName = settings.nickname,
            senderColorHex = settings.avatarColorHex,
            text = trimmed,
            timestampMs = System.currentTimeMillis(),
            isSystem = false
        )
        if (_uiState.value.role == SessionRole.HOST) {
            appendAndBroadcastHostChat(msg)
        } else {
            client.sendChatMessage(msg)
        }
    }

    fun sendReaction(emoji: String) {
        val id = "rx_${System.nanoTime()}"
        val sender = _uiState.value.settings.nickname
        val xFrac = Random.nextFloat() * 0.72f + 0.14f
        if (_uiState.value.role == SessionRole.HOST) {
            spawnFloatingReaction(id, emoji, sender, xFrac)
            hostServer.broadcastReaction(id, emoji, sender, xFrac)
        } else {
            client.sendReaction(id, emoji, sender, xFrac)
        }
    }

    fun submitSongRequest(title: String, artist: String, note: String) {
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty()) return
        val req = SongRequest(
            id = "req_${System.nanoTime()}",
            requesterId = myParticipantId,
            requesterName = _uiState.value.settings.nickname,
            title = cleanTitle,
            artist = artist.trim().ifEmpty { "Requested Track" },
            note = note.trim()
        )
        if (_uiState.value.role == SessionRole.HOST) {
            val updated = listOf(req) + _uiState.value.songRequests
            _uiState.update { it.copy(songRequests = updated) }
            hostServer.broadcastSongRequests(updated)
        } else {
            client.sendSongRequest(req)
        }
    }

    fun decideSongRequest(requestId: String, approve: Boolean) {
        if (_uiState.value.role != SessionRole.HOST) return
        val newStatus = if (approve) SongRequestStatus.APPROVED else SongRequestStatus.REJECTED
        val targetReq = _uiState.value.songRequests.firstOrNull { it.id == requestId }
        val updated = _uiState.value.songRequests.map {
            if (it.id == requestId) it.copy(status = newStatus) else it
        }
        _uiState.update { it.copy(songRequests = updated) }
        hostServer.broadcastSongRequests(updated)

        if (targetReq != null) {
            val statusWord = if (approve) "approved" else "declined"
            appendAndBroadcastHostChat(
                ChatMessage(
                    id = "sys_${System.nanoTime()}",
                    senderId = "system",
                    senderName = "SYNCBEAT",
                    senderColorHex = if (approve) "#10B981" else "#F43F5E",
                    text = "Host $statusWord request: \"${targetReq.title}\" from ${targetReq.requesterName}",
                    isSystem = true
                )
            )
        }
    }

    fun kickParticipant(participantId: String) {
        if (_uiState.value.role != SessionRole.HOST) return
        val target = _uiState.value.participants.firstOrNull { it.id == participantId } ?: return
        if (target.isHost) return
        hostServer.kickParticipant(participantId)
        val updated = _uiState.value.participants.filterNot { it.id == participantId }
        _uiState.update { it.copy(participants = updated) }
        hostServer.broadcastParticipants(updated, _uiState.value.roomInfo.totalListenersJoined)
        appendAndBroadcastHostChat(
            ChatMessage(
                id = "sys_${System.nanoTime()}",
                senderId = "system",
                senderName = "SYNCBEAT",
                senderColorHex = "#F43F5E",
                text = "${target.name} was removed from the party.",
                isSystem = true
            )
        )
    }

    fun updateNickname(name: String) {
        viewModelScope.launch { settingsRepository.updateNickname(name) }
    }

    fun updateAvatarColor(hex: String) {
        viewModelScope.launch { settingsRepository.updateAvatarColor(hex) }
    }

    fun updateCalibrationOffset(offsetMs: Int) {
        viewModelScope.launch { settingsRepository.updateCalibrationOffset(offsetMs) }
    }

    fun updateAutoReconnect(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateAutoReconnect(enabled) }
    }

    fun updateDriftSensitivity(ms: Int) {
        viewModelScope.launch { settingsRepository.updateDriftSensitivity(ms) }
    }

    fun updateChatDuckingEnabled(enabled: Boolean) {
        val state = _uiState.value
        if (state.isInParty && state.role != SessionRole.HOST) return
        val updatedPartyAudio = state.partyAudioSettings.copy(chatDuckingEnabled = enabled)
        _uiState.update { it.copy(partyAudioSettings = updatedPartyAudio) }
        refreshAudioDucking()
        if (state.isInParty && state.role == SessionRole.HOST) {
            hostServer.broadcastAudioSettings(updatedPartyAudio)
        }
        viewModelScope.launch { settingsRepository.updateChatDuckingEnabled(enabled) }
    }

    fun updateChatDuckingReductionPercent(percent: Int) {
        val clamped = percent.coerceIn(15, 65)
        val state = _uiState.value
        if (state.isInParty && state.role != SessionRole.HOST) return
        val updatedPartyAudio = state.partyAudioSettings.copy(duckingReductionPercent = clamped)
        _uiState.update { it.copy(partyAudioSettings = updatedPartyAudio) }
        refreshAudioDucking()
        if (state.isInParty && state.role == SessionRole.HOST) {
            hostServer.broadcastAudioSettings(updatedPartyAudio)
        }
        viewModelScope.launch { settingsRepository.updateChatDuckingReductionPercent(clamped) }
    }

    fun clearStatusBanner() {
        _uiState.update { it.copy(statusBanner = null) }
    }

    fun leaveParty() {
        leaveSessionInternal(banner = null)
    }

    private fun leaveSessionInternal(banner: String?) {
        audioBufferManager.reset()
        webRtcManager.closeAll()
        hostServer.stop()
        client.disconnect()
        audioEngine.clearP2pCache()
        audioEngine.updateWebRtcStatus(false, "IDLE", 0L)
        audioEngine.releasePlayer()
        SyncBeatForegroundService.stop(appContext)
        recentChatHoldJob?.cancel()
        duckRampJob?.cancel()
        isChatTabActive = false
        isChatTypingActive = false
        isRecentChatMessageActive = false
        currentDuckMultiplier = 1.0f
        _uiState.update { state ->
            state.copy(
                isInParty = false,
                isJoining = false,
                connectionStatus = DeviceStatus.DISCONNECTED,
                isPartyChatActive = false,
                isAudioDucked = false,
                effectiveVolume = state.localVolume,
                partyAudioSettings = PartyAudioSettings(
                    chatDuckingEnabled = state.settings.chatDuckingEnabled,
                    duckingReductionPercent = state.settings.chatDuckingReductionPercent
                ),
                participants = emptyList(),
                chatMessages = emptyList(),
                songRequests = emptyList(),
                floatingReactions = emptyList(),
                playbackState = state.playbackState.copy(isPlaying = false, anchorPositionMs = 0L),
                roomInfo = RoomConnectionInfo(),
                statusBanner = banner
            )
        }
        audioEngine.setLocalVolume(_uiState.value.localVolume)
        audioBufferManager.setLocalVolume(_uiState.value.localVolume)
    }

    private fun commitHostPlaybackState(newState: PlaybackState) {
        _uiState.update { it.copy(playbackState = newState) }
        val activeTrack = _uiState.value.queue.firstOrNull { it.id == newState.trackId }
        audioEngine.applyState(SessionRole.HOST, activeTrack, newState)
        audioBufferManager.onTransportUpdated(SessionRole.HOST, activeTrack, newState)
        hostServer.updatePlaybackState(newState)
    }

    private fun handleHostTrackCompleted() {
        val state = _uiState.value
        if (state.role != SessionRole.HOST || state.queue.isEmpty()) return
        when (state.playbackState.repeatMode) {
            RepeatMode.ONE -> {
                val cur = state.queue.firstOrNull { it.id == state.playbackState.trackId } ?: return
                selectTrack(cur, autoPlay = true)
            }
            RepeatMode.ALL -> nextTrack()
            RepeatMode.OFF -> {
                val idx = state.queue.indexOfFirst { it.id == state.playbackState.trackId }
                if (idx in 0 until state.queue.lastIndex) {
                    selectTrack(state.queue[idx + 1], autoPlay = true)
                } else {
                    commitHostPlaybackState(
                        state.playbackState.copy(
                            isPlaying = false,
                            anchorPositionMs = 0L,
                            anchorHostTimeMs = SystemClock.elapsedRealtime(),
                            sequenceNumber = state.playbackState.sequenceNumber + 1
                        )
                    )
                }
            }
        }
    }

    private fun handleHostParticipantJoined(participant: Participant) {
        val current = _uiState.value.participants.filterNot { it.id == participant.id }
        val updated = current + participant
        val total = _uiState.value.roomInfo.totalListenersJoined + 1
        _uiState.update {
            it.copy(
                participants = updated,
                roomInfo = it.roomInfo.copy(totalListenersJoined = total)
            )
        }
        hostServer.broadcastParticipants(updated, total)
        appendAndBroadcastHostChat(
            ChatMessage(
                id = "sys_${System.nanoTime()}",
                senderId = "system",
                senderName = "SYNCBEAT",
                senderColorHex = "#10B981",
                text = "${participant.name} joined the party.",
                isSystem = true
            )
        )
        // Initiate direct Peer-to-Peer WebRTC DataChannel handshake over local WS signaling
        webRtcManager.createOfferForPeer(participant.id)
    }

    private fun handleHostParticipantDisconnected(participantId: String) {
        webRtcManager.closePeer(participantId)
        val existing = _uiState.value.participants.firstOrNull { it.id == participantId } ?: return
        val updated = _uiState.value.participants.map {
            if (it.id == participantId) it.copy(status = DeviceStatus.DISCONNECTED, iceState = "DISCONNECTED") else it
        }
        _uiState.update { it.copy(participants = updated) }
        hostServer.broadcastParticipants(updated, _uiState.value.roomInfo.totalListenersJoined)
        appendAndBroadcastHostChat(
            ChatMessage(
                id = "sys_${System.nanoTime()}",
                senderId = "system",
                senderName = "SYNCBEAT",
                senderColorHex = "#F59E0B",
                text = "${existing.name} disconnected.",
                isSystem = true
            )
        )
    }

    private fun appendAndBroadcastHostChat(msg: ChatMessage) {
        val updated = (_uiState.value.chatMessages + msg).takeLast(120)
        _uiState.update { it.copy(chatMessages = updated) }
        hostServer.broadcastChatMessage(msg, updated)
        if (!msg.isSystem) {
            notifyUserChatMessageActivity()
        }
    }

    private fun spawnFloatingReaction(id: String, emoji: String, senderName: String, xFraction: Float) {
        val reaction = FloatingReaction(
            id = id,
            emoji = emoji,
            senderName = senderName,
            xFraction = xFraction
        )
        _uiState.update { state ->
            state.copy(floatingReactions = (state.floatingReactions + reaction).takeLast(16))
        }
        viewModelScope.launch {
            delay(2600L)
            _uiState.update { state ->
                state.copy(floatingReactions = state.floatingReactions.filterNot { it.id == id })
            }
        }
    }

    private fun generateRoomCode(): String {
        return Random.nextInt(100000, 999999).toString()
    }

    override fun onCleared() {
        audioBufferManager.reset()
        webRtcManager.closeAll()
        hostServer.stop()
        client.stopDiscovery()
        client.disconnect()
        audioEngine.shutdown()
        SyncBeatForegroundService.stop(appContext)
        super.onCleared()
    }
}
