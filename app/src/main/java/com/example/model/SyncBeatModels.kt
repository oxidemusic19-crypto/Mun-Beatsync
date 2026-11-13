package com.example.model

enum class SessionRole {
    HOST,
    LISTENER
}

enum class RepeatMode {
    OFF,
    ALL,
    ONE
}

enum class DeviceStatus {
    CONNECTED,
    RECONNECTING,
    DISCONNECTED
}

enum class SyncHealth {
    HOST_CLOCK,
    LOCKED,
    CORRECTING,
    BUFFERING,
    RECONNECTING
}

enum class TransportMode {
    WEBRTC_P2P,
    WS_SIGNALING
}

enum class SongRequestStatus {
    PENDING,
    APPROVED,
    REJECTED
}

data class AudioTrack(
    val id: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val artworkKey: String,
    val mimeType: String,
    val fileSizeBytes: Long,
    val localPath: String? = null,
    val contentUriString: String? = null,
    val streamUrl: String? = null,
    val pcmStreamPath: String? = null,
    val waveformBins: List<Float> = emptyList()
)

data class PlaybackState(
    val trackId: String? = null,
    val isPlaying: Boolean = false,
    val anchorPositionMs: Long = 0L,
    val anchorHostTimeMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.ALL,
    val sequenceNumber: Long = 0L
)

data class Participant(
    val id: String,
    val name: String,
    val avatarColorHex: String,
    val isHost: Boolean,
    val status: DeviceStatus = DeviceStatus.CONNECTED,
    val rttMs: Long = 0L,
    val driftMs: Long = 0L,
    val transportMode: TransportMode = TransportMode.WS_SIGNALING,
    val iceState: String = "IDLE",
    val p2pBytesTransferred: Long = 0L,
    val joinedAtEpochMs: Long = System.currentTimeMillis()
)

data class SongRequest(
    val id: String,
    val requesterId: String,
    val requesterName: String,
    val title: String,
    val artist: String,
    val note: String = "",
    val timestampMs: Long = System.currentTimeMillis(),
    val status: SongRequestStatus = SongRequestStatus.PENDING
)

data class ChatMessage(
    val id: String,
    val senderId: String,
    val senderName: String,
    val senderColorHex: String,
    val text: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val isSystem: Boolean = false
)

data class FloatingReaction(
    val id: String,
    val emoji: String,
    val senderName: String,
    val xFraction: Float,
    val createdAtMs: Long = System.currentTimeMillis()
)

data class DiscoveredRoom(
    val roomCode: String,
    val roomName: String,
    val hostName: String,
    val hostIp: String,
    val wsPort: Int,
    val httpPort: Int,
    val currentTrackTitle: String,
    val listenerCount: Int,
    val lastSeenMs: Long = System.currentTimeMillis()
)

data class SyncTelemetry(
    val offsetMs: Long = 0L,
    val rttMs: Long = 0L,
    val driftMs: Long = 0L,
    val driftVarianceMs: Long = 0L,
    val syncHealth: SyncHealth = SyncHealth.BUFFERING,
    val playbackSpeed: Float = 1.0f,
    val bufferReady: Boolean = false,
    val sampleCount: Int = 0,
    val isWebRtcP2pActive: Boolean = false,
    val iceState: String = "IDLE",
    val p2pTransferredBytes: Long = 0L,
    val bufferFillPercent: Int = 0,
    val bufferedFrames: Int = 0,
    val framesStreamed: Long = 0L,
    val isAudioFrameStreaming: Boolean = false,
    val jitterMs: Long = 0L
)

data class PartyAudioSettings(
    val chatDuckingEnabled: Boolean = false,
    val duckingReductionPercent: Int = 35
)

data class UserSettings(
    val nickname: String = "Party Guest",
    val avatarColorHex: String = "#8B5CF6",
    val calibrationOffsetMs: Int = 0,
    val autoReconnect: Boolean = true,
    val driftSensitivityMs: Int = 22,
    val chatDuckingEnabled: Boolean = false,
    val chatDuckingReductionPercent: Int = 35
)

data class RoomConnectionInfo(
    val roomCode: String = "",
    val roomName: String = "SYNCBEAT Party",
    val hostIp: String = "",
    val wsPort: Int = 8766,
    val httpPort: Int = 8765,
    val sessionStartedAtMs: Long = System.currentTimeMillis(),
    val totalListenersJoined: Int = 1,
    val isServerRunning: Boolean = false,
    val qrPayload: String = ""
)
