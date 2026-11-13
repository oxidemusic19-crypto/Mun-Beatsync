package com.example.network

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.example.model.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

class WebRtcPeerManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val localPeerId: String,
    private val onSendSignaling: (targetPeerId: String, jsonText: String) -> Unit,
    private val onDataTextMessage: (fromPeerId: String, text: String, lowLatencyChannel: Boolean) -> Unit,
    private val onPeerStateChanged: (peerId: String, iceState: String, isDataChannelOpen: Boolean) -> Unit,
    private val onP2pBytesProgress: (peerId: String, trackId: String, totalBytes: Long, complete: Boolean, localFilePath: String?) -> Unit,
    private val onP2pAudioRequested: (peerId: String, trackId: String) -> Unit,
    private val onAudioFrameReceived: (peerId: String, frameBytes: ByteArray) -> Unit = { _, _ -> }
) {
    private class PeerEntry(
        val peerId: String,
        val connection: PeerConnection,
        @Volatile var syncCtrlChannel: DataChannel? = null,
        @Volatile var partyDataChannel: DataChannel? = null,
        @Volatile var audioStreamChannel: DataChannel? = null,
        @Volatile var iceState: String = "NEW",
        @Volatile var isSyncChannelOpen: Boolean = false,
        @Volatile var isDataChannelOpen: Boolean = false,
        @Volatile var isAudioChannelOpen: Boolean = false,
        val pendingRemoteIce: MutableList<IceCandidate> = mutableListOf(),
        @Volatile var remoteDescriptionSet: Boolean = false
    )

    private class P2pReceiveBuffer(
        val trackId: String,
        val tempFile: File,
        val outputStream: FileOutputStream,
        var bytesWritten: Long = 0L
    )

    private var factory: PeerConnectionFactory? = null
    private var nativeAvailable = false
    private val peers = ConcurrentHashMap<String, PeerEntry>()
    private val activeReceivers = ConcurrentHashMap<String, P2pReceiveBuffer>()

    @Synchronized
    fun ensureInitialized(): Boolean {
        if (nativeAvailable && factory != null) return true
        return try {
            val initOptions = PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(initOptions)
            factory = PeerConnectionFactory.builder()
                .createPeerConnectionFactory()
            nativeAvailable = factory != null
            nativeAvailable
        } catch (_: UnsatisfiedLinkError) {
            nativeAvailable = false
            false
        } catch (_: Throwable) {
            nativeAvailable = false
            false
        }
    }

    fun isPeerP2pReady(peerId: String): Boolean {
        val entry = peers[peerId] ?: return false
        return entry.isSyncChannelOpen
    }

    fun hasAnyOpenP2pPeer(): Boolean = peers.values.any { it.isSyncChannelOpen }

    fun hasOpenAudioStreamChannel(): Boolean = peers.values.any {
        it.isAudioChannelOpen && it.audioStreamChannel?.state() == DataChannel.State.OPEN
    }

    fun getIceState(peerId: String): String = peers[peerId]?.iceState ?: "IDLE"

    /**
     * Host calls this when a listener joins via WebSocket signaling.
     */
    fun createOfferForPeer(peerId: String) {
        if (!ensureInitialized()) return
        closePeer(peerId)

        val pc = createPeerConnection(peerId) ?: return
        val entry = PeerEntry(peerId = peerId, connection = pc)
        peers[peerId] = entry

        // Channel 1: Ultra-low-latency unordered UDP-like SCTP channel for clock sync & play triggers
        val syncInit = DataChannel.Init().apply {
            ordered = false
            maxRetransmits = 0
            id = 1
        }
        val syncChannel = pc.createDataChannel("sync-ctrl", syncInit)
        entry.syncCtrlChannel = syncChannel
        attachDataChannelObserver(entry, syncChannel, ChannelRole.SYNC_CTRL)

        // Channel 2: Reliable ordered channel for chat, queue updates, and file pre-caching
        val dataInit = DataChannel.Init().apply {
            ordered = true
            id = 2
        }
        val dataChannel = pc.createDataChannel("party-data", dataInit)
        entry.partyDataChannel = dataChannel
        attachDataChannelObserver(entry, dataChannel, ChannelRole.PARTY_DATA)

        // Channel 3: Timed binary audio frame streaming channel (PCM frames for AudioBufferManager)
        val audioInit = DataChannel.Init().apply {
            ordered = false
            maxRetransmits = 1
            id = 3
        }
        val audioChannel = pc.createDataChannel("audio-stream", audioInit)
        entry.audioStreamChannel = audioChannel
        attachDataChannelObserver(entry, audioChannel, ChannelRole.AUDIO_STREAM)

        val constraints = MediaConstraints()
        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                val sdp = desc ?: return
                pc.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        onSendSignaling(
                            peerId,
                            ProtocolMessages.encodeWebRtcOffer(
                                targetId = peerId,
                                senderId = localPeerId,
                                sdp = sdp.description
                            )
                        )
                    }
                }, sdp)
            }
        }, constraints)
    }

    /**
     * Listener calls this when receiving WEBRTC_OFFER from the Host.
     */
    fun handleRemoteOffer(fromPeerId: String, sdpDescription: String) {
        if (!ensureInitialized()) return
        closePeer(fromPeerId)

        val pc = createPeerConnection(fromPeerId) ?: return
        val entry = PeerEntry(peerId = fromPeerId, connection = pc)
        peers[fromPeerId] = entry

        val remoteSdp = SessionDescription(SessionDescription.Type.OFFER, sdpDescription)
        pc.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                entry.remoteDescriptionSet = true
                flushPendingIce(entry)
                pc.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(answerDesc: SessionDescription?) {
                        val answer = answerDesc ?: return
                        pc.setLocalDescription(object : SimpleSdpObserver() {
                            override fun onSetSuccess() {
                                onSendSignaling(
                                    fromPeerId,
                                    ProtocolMessages.encodeWebRtcAnswer(
                                        targetId = fromPeerId,
                                        senderId = localPeerId,
                                        sdp = answer.description
                                    )
                                )
                            }
                        }, answer)
                    }
                }, MediaConstraints())
            }
        }, remoteSdp)
    }

    /**
     * Host calls this when receiving WEBRTC_ANSWER from a Listener.
     */
    fun handleRemoteAnswer(fromPeerId: String, sdpDescription: String) {
        val entry = peers[fromPeerId] ?: return
        val remoteSdp = SessionDescription(SessionDescription.Type.ANSWER, sdpDescription)
        entry.connection.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                entry.remoteDescriptionSet = true
                flushPendingIce(entry)
            }
        }, remoteSdp)
    }

    fun handleRemoteIceCandidate(
        fromPeerId: String,
        sdpMid: String,
        sdpMLineIndex: Int,
        candidate: String
    ) {
        val entry = peers[fromPeerId] ?: return
        val ice = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        synchronized(entry.pendingRemoteIce) {
            if (entry.remoteDescriptionSet) {
                entry.connection.addIceCandidate(ice)
            } else {
                entry.pendingRemoteIce.add(ice)
            }
        }
    }

    private fun flushPendingIce(entry: PeerEntry) {
        synchronized(entry.pendingRemoteIce) {
            entry.pendingRemoteIce.forEach { entry.connection.addIceCandidate(it) }
            entry.pendingRemoteIce.clear()
        }
    }

    fun sendLowLatencyText(peerId: String, text: String): Boolean {
        val entry = peers[peerId] ?: return false
        val dc = entry.syncCtrlChannel ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        return sendTextBuffer(dc, text)
    }

    fun broadcastLowLatencyText(text: String, excludePeerId: String? = null): Int {
        var sentCount = 0
        peers.values.forEach { entry ->
            if (entry.peerId != excludePeerId && entry.isSyncChannelOpen) {
                val dc = entry.syncCtrlChannel
                if (dc != null && dc.state() == DataChannel.State.OPEN && sendTextBuffer(dc, text)) {
                    sentCount++
                }
            }
        }
        return sentCount
    }

    fun sendReliableText(peerId: String, text: String): Boolean {
        val entry = peers[peerId] ?: return false
        val dc = entry.partyDataChannel ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        return sendTextBuffer(dc, text)
    }

    fun broadcastReliableText(text: String, excludePeerId: String? = null): Int {
        var sentCount = 0
        peers.values.forEach { entry ->
            if (entry.peerId != excludePeerId && entry.isDataChannelOpen) {
                val dc = entry.partyDataChannel
                if (dc != null && dc.state() == DataChannel.State.OPEN && sendTextBuffer(dc, text)) {
                    sentCount++
                }
            }
        }
        return sentCount
    }

    fun broadcastAudioFrame(frameBytes: ByteArray): Int {
        var sentCount = 0
        peers.values.forEach { entry ->
            val dc = entry.audioStreamChannel
            if (entry.isAudioChannelOpen && dc != null && dc.state() == DataChannel.State.OPEN) {
                if (dc.bufferedAmount() < 524_288L) {
                    val direct = ByteBuffer.allocateDirect(frameBytes.size)
                    direct.put(frameBytes)
                    direct.flip()
                    if (dc.send(DataChannel.Buffer(direct, true))) {
                        sentCount++
                    }
                }
            }
        }
        return sentCount
    }

    /**
     * Streams an audio track in 16 KB binary chunks directly over the peer's WebRTC party-data DataChannel.
     */
    fun streamTrackOverP2p(peerId: String, track: AudioTrack) {
        val entry = peers[peerId] ?: return
        val dc = entry.partyDataChannel ?: return
        if (dc.state() != DataChannel.State.OPEN) return

        scope.launch(Dispatchers.IO) {
            val inputStream = openTrackInputStream(track) ?: return@launch
            inputStream.use { stream ->
                val metaMsg = ProtocolMessages.encodeP2pAudioMeta(track.id, track.fileSizeBytes)
                sendTextBuffer(dc, metaMsg)

                val trackIdBytes = track.id.toByteArray(Charsets.UTF_8)
                val chunkBuf = ByteArray(16 * 1024)
                var offset = 0L
                var lastProgressMs = 0L

                while (isActive && dc.state() == DataChannel.State.OPEN) {
                    // Flow-control backpressure so SCTP queue never overflows
                    while (dc.bufferedAmount() > 1_572_864L && dc.state() == DataChannel.State.OPEN && isActive) {
                        delay(15L)
                    }
                    if (dc.state() != DataChannel.State.OPEN) break

                    val read = stream.read(chunkBuf)
                    if (read <= 0) break

                    val packetSize = 4 + trackIdBytes.size + 8 + read
                    val direct = ByteBuffer.allocateDirect(packetSize).order(ByteOrder.BIG_ENDIAN)
                    direct.putInt(trackIdBytes.size)
                    direct.put(trackIdBytes)
                    direct.putLong(offset)
                    direct.put(chunkBuf, 0, read)
                    direct.flip()

                    val ok = dc.send(DataChannel.Buffer(direct, true))
                    if (!ok) break
                    offset += read

                    val now = SystemClock.elapsedRealtime()
                    if (now - lastProgressMs >= 500L) {
                        lastProgressMs = now
                        onP2pBytesProgress(peerId, track.id, offset, false, null)
                    }
                }

                if (dc.state() == DataChannel.State.OPEN) {
                    sendTextBuffer(dc, ProtocolMessages.encodeP2pAudioComplete(track.id, offset))
                    onP2pBytesProgress(peerId, track.id, offset, true, null)
                }
            }
        }
    }

    private fun openTrackInputStream(track: AudioTrack) = runCatching {
        val local = track.localPath?.let { File(it) }?.takeIf { it.exists() && it.canRead() }
        if (local != null) {
            FileInputStream(local)
        } else if (!track.contentUriString.isNullOrBlank()) {
            context.contentResolver.openInputStream(Uri.parse(track.contentUriString))
        } else {
            null
        }
    }.getOrNull()

    private fun createPeerConnection(peerId: String): PeerConnection? {
        val f = factory ?: return null
        // Local Wi-Fi / Hotspot LAN operation uses host candidates without requiring internet STUN/TURN
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
        }

        return f.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                val stateStr = state?.name ?: "UNKNOWN"
                val entry = peers[peerId] ?: return
                entry.iceState = stateStr
                onPeerStateChanged(peerId, stateStr, entry.isSyncChannelOpen)
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}

            override fun onIceCandidate(candidate: IceCandidate?) {
                val c = candidate ?: return
                onSendSignaling(
                    peerId,
                    ProtocolMessages.encodeWebRtcIce(
                        targetId = peerId,
                        senderId = localPeerId,
                        sdpMid = c.sdpMid ?: "",
                        sdpMLineIndex = c.sdpMLineIndex,
                        candidate = c.sdp
                    )
                )
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

            override fun onAddStream(stream: MediaStream?) {}

            override fun onRemoveStream(stream: MediaStream?) {}

            override fun onDataChannel(channel: DataChannel?) {
                val dc = channel ?: return
                val entry = peers[peerId] ?: return
                val role = when (dc.label()) {
                    "sync-ctrl" -> {
                        entry.syncCtrlChannel = dc
                        ChannelRole.SYNC_CTRL
                    }
                    "audio-stream" -> {
                        entry.audioStreamChannel = dc
                        ChannelRole.AUDIO_STREAM
                    }
                    else -> {
                        entry.partyDataChannel = dc
                        ChannelRole.PARTY_DATA
                    }
                }
                attachDataChannelObserver(entry, dc, role)
            }

            override fun onRenegotiationNeeded() {}

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        })
    }

    private enum class ChannelRole {
        SYNC_CTRL,
        PARTY_DATA,
        AUDIO_STREAM
    }

    private fun attachDataChannelObserver(
        entry: PeerEntry,
        channel: DataChannel,
        role: ChannelRole
    ) {
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}

            override fun onStateChange() {
                val open = channel.state() == DataChannel.State.OPEN
                when (role) {
                    ChannelRole.SYNC_CTRL -> entry.isSyncChannelOpen = open
                    ChannelRole.PARTY_DATA -> entry.isDataChannelOpen = open
                    ChannelRole.AUDIO_STREAM -> entry.isAudioChannelOpen = open
                }
                onPeerStateChanged(entry.peerId, entry.iceState, entry.isSyncChannelOpen)
            }

            override fun onMessage(buffer: DataChannel.Buffer?) {
                val buf = buffer ?: return
                val data = ByteArray(buf.data.remaining())
                buf.data.get(data)

                if (!buf.binary) {
                    val text = String(data, Charsets.UTF_8)
                    handleIncomingText(entry.peerId, text, role == ChannelRole.SYNC_CTRL)
                } else if (role == ChannelRole.AUDIO_STREAM) {
                    onAudioFrameReceived(entry.peerId, data)
                } else {
                    handleIncomingBinaryChunk(entry.peerId, data)
                }
            }
        })
    }

    private fun handleIncomingText(peerId: String, text: String, isLowLatency: Boolean) {
        val json = runCatching { JSONObject(text) }.getOrNull()
        val type = json?.optString("type")
        when (type) {
            ProtocolMessages.TYPE_P2P_AUDIO_REQUEST -> {
                val trackId = json.optString("trackId")
                if (trackId.isNotBlank()) {
                    onP2pAudioRequested(peerId, trackId)
                }
            }
            ProtocolMessages.TYPE_P2P_AUDIO_META -> {
                val trackId = json.optString("trackId")
                if (trackId.isNotBlank()) {
                    prepareReceiveBuffer(trackId)
                }
            }
            ProtocolMessages.TYPE_P2P_AUDIO_COMPLETE -> {
                val trackId = json.optString("trackId")
                val total = json.optLong("totalBytes", 0L)
                finalizeReceiveBuffer(peerId, trackId, total)
            }
            else -> {
                onDataTextMessage(peerId, text, isLowLatency)
            }
        }
    }

    private fun prepareReceiveBuffer(trackId: String) {
        activeReceivers.remove(trackId)?.let { old ->
            runCatching { old.outputStream.close() }
        }
        val dir = File(context.cacheDir, "p2p_tracks").apply { mkdirs() }
        val tmp = File(dir, "$trackId.part")
        runCatching {
            val fos = FileOutputStream(tmp, false)
            activeReceivers[trackId] = P2pReceiveBuffer(trackId, tmp, fos)
        }
    }

    private fun handleIncomingBinaryChunk(peerId: String, data: ByteArray) {
        if (data.size < 13) return
        val bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val idLen = bb.int
        if (idLen <= 0 || idLen > 128 || data.size < 4 + idLen + 8) return
        val idBytes = ByteArray(idLen)
        bb.get(idBytes)
        val trackId = String(idBytes, Charsets.UTF_8)
        @Suppress("UNUSED_VARIABLE")
        val offset = bb.long
        val payloadLen = bb.remaining()
        if (payloadLen <= 0) return

        val receiver = activeReceivers[trackId] ?: run {
            prepareReceiveBuffer(trackId)
            activeReceivers[trackId]
        } ?: return

        runCatching {
            receiver.outputStream.write(data, 4 + idLen + 8, payloadLen)
            receiver.bytesWritten += payloadLen
            onP2pBytesProgress(peerId, trackId, receiver.bytesWritten, false, null)
        }
    }

    private fun finalizeReceiveBuffer(peerId: String, trackId: String, expectedTotal: Long) {
        val receiver = activeReceivers.remove(trackId) ?: return
        runCatching {
            receiver.outputStream.flush()
            receiver.outputStream.close()
        }
        val dir = File(context.cacheDir, "p2p_tracks").apply { mkdirs() }
        val finalFile = File(dir, "$trackId.audio")
        if (receiver.tempFile.exists() && receiver.bytesWritten > 0L) {
            if (finalFile.exists()) finalFile.delete()
            receiver.tempFile.renameTo(finalFile)
            val total = maxOf(expectedTotal, receiver.bytesWritten)
            onP2pBytesProgress(peerId, trackId, total, true, finalFile.absolutePath)
        }
    }

    private fun sendTextBuffer(channel: DataChannel, text: String): Boolean {
        return runCatching {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val direct = ByteBuffer.allocateDirect(bytes.size)
            direct.put(bytes)
            direct.flip()
            channel.send(DataChannel.Buffer(direct, false))
        }.getOrDefault(false)
    }

    fun closePeer(peerId: String) {
        peers.remove(peerId)?.let { entry ->
            runCatching { entry.syncCtrlChannel?.close() }
            runCatching { entry.partyDataChannel?.close() }
            runCatching { entry.audioStreamChannel?.close() }
            runCatching { entry.connection.close() }
            runCatching { entry.connection.dispose() }
        }
    }

    fun closeAll() {
        val ids = peers.keys.toList()
        ids.forEach { closePeer(it) }
        activeReceivers.values.forEach { rec ->
            runCatching { rec.outputStream.close() }
        }
        activeReceivers.clear()
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }
}
