package com.example.network

import com.example.model.AudioTrack
import com.example.model.ChatMessage
import com.example.model.DeviceStatus
import com.example.model.Participant
import com.example.model.PartyAudioSettings
import com.example.model.PlaybackState
import com.example.model.RepeatMode
import com.example.model.SongRequest
import com.example.model.SongRequestStatus
import com.example.model.TransportMode
import org.json.JSONArray
import org.json.JSONObject

object ProtocolMessages {

    const val TYPE_JOIN = "JOIN"
    const val TYPE_JOIN_ACK = "JOIN_ACK"
    const val TYPE_KICKED = "KICKED"
    const val TYPE_ERROR = "ERROR"
    const val TYPE_SYNC_PING = "SYNC_PING"
    const val TYPE_SYNC_PONG = "SYNC_PONG"
    const val TYPE_ROOM_SNAPSHOT = "ROOM_SNAPSHOT"
    const val TYPE_PLAYBACK_STATE = "PLAYBACK_STATE"
    const val TYPE_QUEUE_UPDATE = "QUEUE_UPDATE"
    const val TYPE_PARTICIPANTS_UPDATE = "PARTICIPANTS_UPDATE"
    const val TYPE_PARTICIPANT_TELEMETRY = "PARTICIPANT_TELEMETRY"
    const val TYPE_CHAT_MESSAGE = "CHAT_MESSAGE"
    const val TYPE_REACTION = "REACTION"
    const val TYPE_SONG_REQUEST = "SONG_REQUEST"
    const val TYPE_SONG_REQUEST_UPDATE = "SONG_REQUEST_UPDATE"
    const val TYPE_WEBRTC_OFFER = "WEBRTC_OFFER"
    const val TYPE_WEBRTC_ANSWER = "WEBRTC_ANSWER"
    const val TYPE_WEBRTC_ICE = "WEBRTC_ICE"
    const val TYPE_P2P_AUDIO_REQUEST = "P2P_AUDIO_REQUEST"
    const val TYPE_P2P_AUDIO_META = "P2P_AUDIO_META"
    const val TYPE_P2P_AUDIO_COMPLETE = "P2P_AUDIO_COMPLETE"
    const val TYPE_AUDIO_SETTINGS = "AUDIO_SETTINGS"

    fun encodeJoin(roomCode: String, participantId: String, name: String, avatarColorHex: String): String =
        JSONObject().apply {
            put("type", TYPE_JOIN)
            put("roomCode", roomCode)
            put("participantId", participantId)
            put("name", name)
            put("avatarColorHex", avatarColorHex)
        }.toString()

    fun encodeJoinAck(
        accepted: Boolean,
        roomCode: String,
        roomName: String,
        hostIp: String,
        httpPort: Int,
        sessionStartedAtMs: Long,
        totalListenersJoined: Int,
        reason: String = ""
    ): String = JSONObject().apply {
        put("type", TYPE_JOIN_ACK)
        put("accepted", accepted)
        put("roomCode", roomCode)
        put("roomName", roomName)
        put("hostIp", hostIp)
        put("httpPort", httpPort)
        put("sessionStartedAtMs", sessionStartedAtMs)
        put("totalListenersJoined", totalListenersJoined)
        put("reason", reason)
    }.toString()

    fun encodeKicked(reason: String): String = JSONObject().apply {
        put("type", TYPE_KICKED)
        put("reason", reason)
    }.toString()

    fun encodeSyncPing(seq: Long, clientSendMs: Long): String = JSONObject().apply {
        put("type", TYPE_SYNC_PING)
        put("seq", seq)
        put("clientSendMs", clientSendMs)
    }.toString()

    fun encodeSyncPong(seq: Long, clientSendMs: Long, hostReceiveMs: Long, hostSendMs: Long): String =
        JSONObject().apply {
            put("type", TYPE_SYNC_PONG)
            put("seq", seq)
            put("clientSendMs", clientSendMs)
            put("hostReceiveMs", hostReceiveMs)
            put("hostSendMs", hostSendMs)
        }.toString()

    fun encodePlaybackState(state: PlaybackState): String = JSONObject().apply {
        put("type", TYPE_PLAYBACK_STATE)
        put("state", playbackStateToJson(state))
    }.toString()

    fun encodeQueueUpdate(tracks: List<AudioTrack>, hostIp: String, httpPort: Int): String =
        JSONObject().apply {
            put("type", TYPE_QUEUE_UPDATE)
            put("tracks", tracksToJsonArray(tracks, hostIp, httpPort))
        }.toString()

    fun encodeParticipantsUpdate(participants: List<Participant>, totalListenersJoined: Int): String =
        JSONObject().apply {
            put("type", TYPE_PARTICIPANTS_UPDATE)
            put("totalListenersJoined", totalListenersJoined)
            put("participants", participantsToJsonArray(participants))
        }.toString()

    fun encodeParticipantTelemetry(participantId: String, rttMs: Long, driftMs: Long): String =
        JSONObject().apply {
            put("type", TYPE_PARTICIPANT_TELEMETRY)
            put("participantId", participantId)
            put("rttMs", rttMs)
            put("driftMs", driftMs)
        }.toString()

    fun encodeChatMessage(message: ChatMessage): String = JSONObject().apply {
        put("type", TYPE_CHAT_MESSAGE)
        put("message", chatToJson(message))
    }.toString()

    fun encodeReaction(id: String, emoji: String, senderName: String, xFraction: Float): String =
        JSONObject().apply {
            put("type", TYPE_REACTION)
            put("id", id)
            put("emoji", emoji)
            put("senderName", senderName)
            put("xFraction", xFraction.toDouble())
        }.toString()

    fun encodeSongRequest(request: SongRequest): String = JSONObject().apply {
        put("type", TYPE_SONG_REQUEST)
        put("request", songRequestToJson(request))
    }.toString()

    fun encodeSongRequestUpdate(requests: List<SongRequest>): String = JSONObject().apply {
        put("type", TYPE_SONG_REQUEST_UPDATE)
        put("requests", JSONArray().apply { requests.forEach { put(songRequestToJson(it)) } })
    }.toString()

    fun encodeWebRtcOffer(targetId: String, senderId: String, sdp: String): String =
        JSONObject().apply {
            put("type", TYPE_WEBRTC_OFFER)
            put("targetId", targetId)
            put("senderId", senderId)
            put("sdp", sdp)
        }.toString()

    fun encodeWebRtcAnswer(targetId: String, senderId: String, sdp: String): String =
        JSONObject().apply {
            put("type", TYPE_WEBRTC_ANSWER)
            put("targetId", targetId)
            put("senderId", senderId)
            put("sdp", sdp)
        }.toString()

    fun encodeWebRtcIce(
        targetId: String,
        senderId: String,
        sdpMid: String,
        sdpMLineIndex: Int,
        candidate: String
    ): String = JSONObject().apply {
        put("type", TYPE_WEBRTC_ICE)
        put("targetId", targetId)
        put("senderId", senderId)
        put("sdpMid", sdpMid)
        put("sdpMLineIndex", sdpMLineIndex)
        put("candidate", candidate)
    }.toString()

    fun encodeP2pAudioRequest(trackId: String): String = JSONObject().apply {
        put("type", TYPE_P2P_AUDIO_REQUEST)
        put("trackId", trackId)
    }.toString()

    fun encodeP2pAudioMeta(trackId: String, fileSizeBytes: Long): String = JSONObject().apply {
        put("type", TYPE_P2P_AUDIO_META)
        put("trackId", trackId)
        put("fileSizeBytes", fileSizeBytes)
    }.toString()

    fun encodeP2pAudioComplete(trackId: String, totalBytes: Long): String = JSONObject().apply {
        put("type", TYPE_P2P_AUDIO_COMPLETE)
        put("trackId", trackId)
        put("totalBytes", totalBytes)
    }.toString()

    fun encodeAudioSettings(settings: PartyAudioSettings): String = JSONObject().apply {
        put("type", TYPE_AUDIO_SETTINGS)
        put("audioSettings", audioSettingsToJson(settings))
    }.toString()

    fun audioSettingsToJson(settings: PartyAudioSettings): JSONObject = JSONObject().apply {
        put("chatDuckingEnabled", settings.chatDuckingEnabled)
        put("duckingReductionPercent", settings.duckingReductionPercent.coerceIn(15, 65))
    }

    fun jsonToAudioSettings(obj: JSONObject?): PartyAudioSettings {
        if (obj == null) return PartyAudioSettings()
        return PartyAudioSettings(
            chatDuckingEnabled = obj.optBoolean("chatDuckingEnabled", false),
            duckingReductionPercent = obj.optInt("duckingReductionPercent", 35).coerceIn(15, 65)
        )
    }

    fun encodeRoomSnapshot(
        roomName: String,
        hostIp: String,
        httpPort: Int,
        sessionStartedAtMs: Long,
        totalListenersJoined: Int,
        tracks: List<AudioTrack>,
        playbackState: PlaybackState,
        participants: List<Participant>,
        chatHistory: List<ChatMessage>,
        songRequests: List<SongRequest>,
        audioSettings: PartyAudioSettings = PartyAudioSettings()
    ): String = JSONObject().apply {
        put("type", TYPE_ROOM_SNAPSHOT)
        put("roomName", roomName)
        put("hostIp", hostIp)
        put("httpPort", httpPort)
        put("sessionStartedAtMs", sessionStartedAtMs)
        put("totalListenersJoined", totalListenersJoined)
        put("tracks", tracksToJsonArray(tracks, hostIp, httpPort))
        put("playbackState", playbackStateToJson(playbackState))
        put("participants", participantsToJsonArray(participants))
        put("chatHistory", JSONArray().apply { chatHistory.forEach { put(chatToJson(it)) } })
        put("songRequests", JSONArray().apply { songRequests.forEach { put(songRequestToJson(it)) } })
        put("audioSettings", audioSettingsToJson(audioSettings))
    }.toString()

    fun playbackStateToJson(state: PlaybackState): JSONObject = JSONObject().apply {
        put("trackId", state.trackId ?: "")
        put("isPlaying", state.isPlaying)
        put("anchorPositionMs", state.anchorPositionMs)
        put("anchorHostTimeMs", state.anchorHostTimeMs)
        put("shuffle", state.shuffle)
        put("repeatMode", state.repeatMode.name)
        put("sequenceNumber", state.sequenceNumber)
    }

    fun jsonToPlaybackState(obj: JSONObject): PlaybackState {
        val rawTrackId = obj.optString("trackId", "")
        val repeatStr = obj.optString("repeatMode", RepeatMode.ALL.name)
        val repeatMode = runCatching { RepeatMode.valueOf(repeatStr) }.getOrDefault(RepeatMode.ALL)
        return PlaybackState(
            trackId = rawTrackId.ifBlank { null },
            isPlaying = obj.optBoolean("isPlaying", false),
            anchorPositionMs = obj.optLong("anchorPositionMs", 0L),
            anchorHostTimeMs = obj.optLong("anchorHostTimeMs", 0L),
            shuffle = obj.optBoolean("shuffle", false),
            repeatMode = repeatMode,
            sequenceNumber = obj.optLong("sequenceNumber", 0L)
        )
    }

    fun tracksToJsonArray(tracks: List<AudioTrack>, hostIp: String, httpPort: Int): JSONArray =
        JSONArray().apply {
            tracks.forEach { t ->
                put(JSONObject().apply {
                    put("id", t.id)
                    put("title", t.title)
                    put("artist", t.artist)
                    put("durationMs", t.durationMs)
                    put("artworkKey", t.artworkKey)
                    put("mimeType", t.mimeType)
                    put("fileSizeBytes", t.fileSizeBytes)
                    put("streamUrl", "http://$hostIp:$httpPort/audio/${t.id}")
                    put("waveformBins", JSONArray().apply {
                        t.waveformBins.forEach { put(it.toDouble()) }
                    })
                })
            }
        }

    fun jsonArrayToTracks(arr: JSONArray): List<AudioTrack> {
        val list = ArrayList<AudioTrack>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val wfArr = obj.optJSONArray("waveformBins")
            val bins = if (wfArr != null) {
                List(wfArr.length()) { idx -> wfArr.optDouble(idx, 0.35).toFloat() }
            } else {
                emptyList()
            }
            list.add(
                AudioTrack(
                    id = obj.optString("id"),
                    title = obj.optString("title", "Untitled"),
                    artist = obj.optString("artist", "Unknown"),
                    durationMs = obj.optLong("durationMs", 0L),
                    artworkKey = obj.optString("artworkKey", "pulse"),
                    mimeType = obj.optString("mimeType", "audio/mpeg"),
                    fileSizeBytes = obj.optLong("fileSizeBytes", 0L),
                    streamUrl = obj.optString("streamUrl", ""),
                    waveformBins = bins
                )
            )
        }
        return list
    }

    fun participantsToJsonArray(list: List<Participant>): JSONArray = JSONArray().apply {
        list.forEach { p ->
            put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("avatarColorHex", p.avatarColorHex)
                put("isHost", p.isHost)
                put("status", p.status.name)
                put("rttMs", p.rttMs)
                put("driftMs", p.driftMs)
                put("transportMode", p.transportMode.name)
                put("iceState", p.iceState)
                put("p2pBytesTransferred", p.p2pBytesTransferred)
                put("joinedAtEpochMs", p.joinedAtEpochMs)
            })
        }
    }

    fun jsonArrayToParticipants(arr: JSONArray): List<Participant> {
        val list = ArrayList<Participant>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val status = runCatching {
                DeviceStatus.valueOf(obj.optString("status", DeviceStatus.CONNECTED.name))
            }.getOrDefault(DeviceStatus.CONNECTED)
            val transport = runCatching {
                TransportMode.valueOf(obj.optString("transportMode", TransportMode.WS_SIGNALING.name))
            }.getOrDefault(TransportMode.WS_SIGNALING)
            list.add(
                Participant(
                    id = obj.optString("id"),
                    name = obj.optString("name", "Guest"),
                    avatarColorHex = obj.optString("avatarColorHex", "#8B5CF6"),
                    isHost = obj.optBoolean("isHost", false),
                    status = status,
                    rttMs = obj.optLong("rttMs", 0L),
                    driftMs = obj.optLong("driftMs", 0L),
                    transportMode = transport,
                    iceState = obj.optString("iceState", "IDLE"),
                    p2pBytesTransferred = obj.optLong("p2pBytesTransferred", 0L),
                    joinedAtEpochMs = obj.optLong("joinedAtEpochMs", System.currentTimeMillis())
                )
            )
        }
        return list
    }

    fun chatToJson(msg: ChatMessage): JSONObject = JSONObject().apply {
        put("id", msg.id)
        put("senderId", msg.senderId)
        put("senderName", msg.senderName)
        put("senderColorHex", msg.senderColorHex)
        put("text", msg.text)
        put("timestampMs", msg.timestampMs)
        put("isSystem", msg.isSystem)
    }

    fun jsonToChat(obj: JSONObject): ChatMessage = ChatMessage(
        id = obj.optString("id"),
        senderId = obj.optString("senderId"),
        senderName = obj.optString("senderName", "Guest"),
        senderColorHex = obj.optString("senderColorHex", "#8B5CF6"),
        text = obj.optString("text", ""),
        timestampMs = obj.optLong("timestampMs", System.currentTimeMillis()),
        isSystem = obj.optBoolean("isSystem", false)
    )

    fun songRequestToJson(req: SongRequest): JSONObject = JSONObject().apply {
        put("id", req.id)
        put("requesterId", req.requesterId)
        put("requesterName", req.requesterName)
        put("title", req.title)
        put("artist", req.artist)
        put("note", req.note)
        put("timestampMs", req.timestampMs)
        put("status", req.status.name)
    }

    fun jsonToSongRequest(obj: JSONObject): SongRequest = SongRequest(
        id = obj.optString("id"),
        requesterId = obj.optString("requesterId"),
        requesterName = obj.optString("requesterName", "Guest"),
        title = obj.optString("title", ""),
        artist = obj.optString("artist", ""),
        note = obj.optString("note", ""),
        timestampMs = obj.optLong("timestampMs", System.currentTimeMillis()),
        status = runCatching {
            SongRequestStatus.valueOf(obj.optString("status", SongRequestStatus.PENDING.name))
        }.getOrDefault(SongRequestStatus.PENDING)
    )
}
