package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.audio.AudioBufferManager
import com.example.audio.AudioDuckingHelper
import com.example.audio.AudioFramePacket
import com.example.audio.AudioFrequencyAnalyzer
import com.example.audio.BuiltInPartyTracks
import com.example.audio.PcmAudioTranscoder
import com.example.model.AudioTrack
import com.example.model.DeviceStatus
import com.example.model.Participant
import com.example.model.PartyAudioSettings
import com.example.model.PlaybackState
import com.example.model.RepeatMode
import com.example.model.SessionRole
import com.example.model.SyncHealth
import com.example.model.SyncTelemetry
import com.example.model.TransportMode
import com.example.network.ProtocolMessages
import com.example.network.SyncBeatHostServer
import com.example.qr.QrCodeUtils
import com.example.ui.components.ConnectionQualityTier
import com.example.ui.components.SyncHealthTier
import com.example.ui.components.assessParticipantConnection
import com.example.ui.components.assessSyncHealth
import com.example.viewmodel.AudioLibraryViewModel
import com.example.viewmodel.PcmConversionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SyncBeatCoreTest {

    @Test
    fun `qr payload round trip encodes and parses cleanly`() {
        val payload = QrCodeUtils.buildRoomPayload("192.168.43.1", 8766, 8765, "482910")
        val parsed = QrCodeUtils.parseRoomPayload(payload)
        assertNotNull(parsed)
        assertEquals("192.168.43.1", parsed!!.hostIp)
        assertEquals(8766, parsed.wsPort)
        assertEquals(8765, parsed.httpPort)
        assertEquals("482910", parsed.roomCode)
    }

    @Test
    fun `built in wav tracks synthesize real audio and extract waveforms`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tracks = BuiltInPartyTracks.ensureBuiltInTracks(context)
        assertEquals(3, tracks.size)
        tracks.forEach { track ->
            val file = File(track.localPath!!)
            assertTrue(file.exists())
            assertTrue(file.length() > 1_000_000L)
            assertEquals(96, track.waveformBins.size)
            assertTrue(track.waveformBins.all { it in 0.1f..1.0f })
        }
    }

    @Test
    fun `host server streams http range audio and completes websocket join and ntp ping`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tracks = BuiltInPartyTracks.ensureBuiltInTracks(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val joinLatch = CountDownLatch(1)
        var snapshotReceived = false
        var pongReceived = false

        val server = SyncBeatHostServer(
            context = context,
            scope = scope,
            onParticipantJoined = {},
            onParticipantDisconnected = {},
            onParticipantTelemetry = { _, _, _ -> },
            onChatMessageReceived = {},
            onReactionReceived = { _, _, _, _ -> },
            onSongRequestReceived = {}
        )

        val (httpPort, wsPort) = server.start(
            roomCode = "123456",
            roomName = "Test Party",
            hostName = "DJ Test",
            initialTracks = tracks,
            initialState = PlaybackState(
                trackId = tracks.first().id,
                isPlaying = true,
                anchorPositionMs = 1500L,
                anchorHostTimeMs = 50000L,
                shuffle = false,
                repeatMode = RepeatMode.ALL,
                sequenceNumber = 1L
            ),
            initialParticipants = listOf(
                Participant("host1", "DJ Test", "#8B5CF6", isHost = true, status = DeviceStatus.CONNECTED)
            )
        )

        try {
            val httpClient = OkHttpClient()
            // Verify HTTP 206 Partial Content Range streaming
            val audioReq = Request.Builder()
                .url("http://127.0.0.1:$httpPort/audio/${tracks.first().id}")
                .addHeader("Range", "bytes=0-1023")
                .build()
            httpClient.newCall(audioReq).execute().use { resp ->
                assertEquals(206, resp.code)
                assertEquals(1024, resp.body?.bytes()?.size)
            }

            // Verify WebSocket RFC 6455 handshake, JOIN, ROOM_SNAPSHOT, and SYNC_PING -> SYNC_PONG
            val wsReq = Request.Builder()
                .url("ws://127.0.0.1:$wsPort/ws")
                .build()
            val ws = httpClient.newWebSocket(wsReq, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(ProtocolMessages.encodeJoin("123456", "guest1", "Guest", "#38BDF8"))
                    webSocket.send(ProtocolMessages.encodeSyncPing(1L, 1000L))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        ProtocolMessages.TYPE_ROOM_SNAPSHOT -> snapshotReceived = true
                        ProtocolMessages.TYPE_SYNC_PONG -> pongReceived = true
                    }
                    if (snapshotReceived && pongReceived) {
                        joinLatch.countDown()
                    }
                }
            })

            assertTrue(joinLatch.await(5, TimeUnit.SECONDS))
            ws.close(1000, "done")
            Unit
        } finally {
            server.stop()
            scope.cancel()
        }
    }

    @Test
    fun `webrtc sdp and ice signaling relays over host websocket`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tracks = BuiltInPartyTracks.ensureBuiltInTracks(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val signalingLatch = CountDownLatch(2)
        var capturedAnswerSdp = ""
        var capturedIceCandidate = ""

        val server = SyncBeatHostServer(
            context = context,
            scope = scope,
            onParticipantJoined = {},
            onParticipantDisconnected = {},
            onParticipantTelemetry = { _, _, _ -> },
            onChatMessageReceived = {},
            onReactionReceived = { _, _, _, _ -> },
            onSongRequestReceived = {},
            onWebRtcAnswer = { peerId, sdp ->
                if (peerId == "guest_rtc") {
                    capturedAnswerSdp = sdp
                    signalingLatch.countDown()
                }
            },
            onWebRtcIceCandidate = { peerId, _, _, candidate ->
                if (peerId == "guest_rtc") {
                    capturedIceCandidate = candidate
                    signalingLatch.countDown()
                }
            }
        )

        val (_, wsPort) = server.start(
            roomCode = "654321",
            roomName = "WebRTC Test",
            hostName = "DJ Host",
            initialTracks = tracks,
            initialState = PlaybackState(trackId = tracks.first().id),
            initialParticipants = listOf(
                Participant("host1", "DJ Host", "#8B5CF6", isHost = true, status = DeviceStatus.CONNECTED)
            )
        )

        try {
            val httpClient = OkHttpClient()
            val offerLatch = CountDownLatch(1)
            val ws = httpClient.newWebSocket(
                Request.Builder().url("ws://127.0.0.1:$wsPort/ws").build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(ProtocolMessages.encodeJoin("654321", "guest_rtc", "Guest RTC", "#38BDF8"))
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val json = JSONObject(text)
                        if (json.optString("type") == ProtocolMessages.TYPE_ROOM_SNAPSHOT) {
                            server.sendToParticipant(
                                "guest_rtc",
                                ProtocolMessages.encodeWebRtcOffer("guest_rtc", "host", "v=0\r\no=- 123 2 IN IP4 127.0.0.1\r\n")
                            )
                        } else if (json.optString("type") == ProtocolMessages.TYPE_WEBRTC_OFFER) {
                            offerLatch.countDown()
                            webSocket.send(
                                ProtocolMessages.encodeWebRtcAnswer("host", "guest_rtc", "v=0\r\no=- 456 2 IN IP4 127.0.0.1\r\n")
                            )
                            webSocket.send(
                                ProtocolMessages.encodeWebRtcIce("host", "guest_rtc", "0", 0, "candidate:1 1 udp 2122260223 192.168.43.2 50000 typ host")
                            )
                        }
                    }
                }
            )

            assertTrue(offerLatch.await(5, TimeUnit.SECONDS))
            assertTrue(signalingLatch.await(5, TimeUnit.SECONDS))
            assertTrue(capturedAnswerSdp.contains("456"))
            assertTrue(capturedIceCandidate.contains("typ host"))
            ws.close(1000, "done")
            Unit
        } finally {
            server.stop()
            scope.cancel()
        }
    }

    @Test
    fun `audio buffer manager encodes and decodes binary pcm frames cleanly`() {
        val samplePcm = ByteArray(AudioBufferManager.PCM_FRAME_BYTES) { idx -> (idx % 127).toByte() }
        val packet = AudioFramePacket(
            trackHash = "track_pulse".hashCode(),
            playbackEpoch = 7,
            sequence = 42L,
            framePosMs = 1680L,
            targetHostTimeMs = 90500L,
            sampleRate = AudioBufferManager.TARGET_SAMPLE_RATE,
            channels = AudioBufferManager.TARGET_CHANNELS,
            pcmData = samplePcm
        )

        val encoded = AudioBufferManager.encodeFrame(packet)
        assertEquals(AudioBufferManager.HEADER_BYTES + samplePcm.size, encoded.size)

        val decoded = AudioBufferManager.decodeFrame(encoded)
        assertNotNull(decoded)
        assertEquals(packet.trackHash, decoded!!.trackHash)
        assertEquals(packet.playbackEpoch, decoded.playbackEpoch)
        assertEquals(packet.sequence, decoded.sequence)
        assertEquals(packet.framePosMs, decoded.framePosMs)
        assertEquals(packet.targetHostTimeMs, decoded.targetHostTimeMs)
        assertEquals(packet.sampleRate, decoded.sampleRate)
        assertEquals(packet.channels, decoded.channels)
        assertArrayEquals(packet.pcmData, decoded.pcmData)

        // Reject truncated or bad-magic frames
        assertNull(AudioBufferManager.decodeFrame(ByteArray(12)))
        val corrupted = encoded.copyOf().apply { this[0] = 0x00 }
        assertNull(AudioBufferManager.decodeFrame(corrupted))
    }

    @Test
    fun `audio buffer manager reorders frames filters stale tracks epochs duplicates and caps overflow`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var fakeClockMs = 10_000L
        val manager = AudioBufferManager(
            context = context,
            scope = scope,
            estimatedHostNowMs = { fakeClockMs },
            getClockOffsetMs = { 0L },
            sendBinaryFrame = { 0 },
            hasOpenAudioPeer = { false },
            elapsedRealtimeMs = { fakeClockMs }
        )

        try {
            val activeTrack = AudioTrack(
                id = "track_active",
                title = "Pulse",
                artist = "SyncBeat",
                durationMs = 120_000L,
                artworkKey = "pulse",
                mimeType = "audio/wav",
                fileSizeBytes = 1024L
            )
            // Keep isPlaying=false so listener consumer coroutine does not race AudioTrack creation on JVM
            manager.onTransportUpdated(
                role = SessionRole.LISTENER,
                track = activeTrack,
                state = PlaybackState(
                    trackId = activeTrack.id,
                    isPlaying = false,
                    anchorPositionMs = 0L,
                    sequenceNumber = 3L
                )
            )

            val pcm = ByteArray(128) { 7 }
            val activeHash = activeTrack.id.hashCode()
            val staleHash = "track_old".hashCode()

            // Stale track frame should be ignored without entering buffer
            assertTrue(
                manager.onIncomingBinaryAudioFrame(
                    AudioBufferManager.encodeFrame(
                        AudioFramePacket(staleHash, 3, 1L, 40L, 10_040L, 44100, 2, pcm)
                    )
                )
            )
            assertEquals(0, manager.bufferStats.value.bufferedFrames)

            // Stale playbackEpoch (pre-seek frame) should be ignored without entering buffer
            assertTrue(
                manager.onIncomingBinaryAudioFrame(
                    AudioBufferManager.encodeFrame(
                        AudioFramePacket(activeHash, 2, 1L, 40L, 10_040L, 44100, 2, pcm)
                    )
                )
            )
            assertEquals(0, manager.bufferStats.value.bufferedFrames)

            // Non-SBAF binary packet should return false
            assertFalse(manager.onIncomingBinaryAudioFrame(byteArrayOf(1, 2, 3, 4)))

            // Enqueue out-of-order + duplicate frames and verify TreeMap sorts ascending and deduplicates
            listOf(240L, 80L, 160L, 80L, 40L).forEachIndexed { idx, posMs ->
                val raw = AudioBufferManager.encodeFrame(
                    AudioFramePacket(activeHash, 3, idx.toLong(), posMs, 10_000L + posMs, 44100, 2, pcm)
                )
                assertTrue(manager.onIncomingBinaryAudioFrame(raw))
            }
            assertEquals(4, manager.bufferStats.value.bufferedFrames)
            assertEquals(40L, manager.pollNextFrame()?.framePosMs)
            assertEquals(80L, manager.pollNextFrame()?.framePosMs)
            assertEquals(160L, manager.pollNextFrame()?.framePosMs)
            assertEquals(240L, manager.pollNextFrame()?.framePosMs)
            assertNull(manager.pollNextFrame())

            // Simulate bursty arrival jitter so adaptive jitter estimator increases prebuffer target
            manager.clearBuffer()
            val arrivalGapsMs = listOf(0L, 140L, 10L, 180L, 15L, 200L, 20L, 220L)
            arrivalGapsMs.forEachIndexed { idx, gap ->
                fakeClockMs += gap
                val posMs = 1000L + idx * 40L
                val raw = AudioBufferManager.encodeFrame(
                    AudioFramePacket(activeHash, 3, idx.toLong(), posMs, 10_000L + posMs, 44100, 2, pcm)
                )
                assertTrue(manager.onIncomingBinaryAudioFrame(raw))
            }
            assertTrue(manager.bufferStats.value.estimatedJitterMs > 20L)
            assertTrue(manager.bufferStats.value.adaptivePrebufferFrames > AudioBufferManager.MIN_PREBUFFER_FRAMES)

            // Overflow beyond MAX_BUFFER_FRAMES (96) drops oldest frames cleanly
            manager.clearBuffer()
            for (i in 0 until 110) {
                val posMs = i * 40L
                manager.enqueueFrame(
                    AudioFramePacket(activeHash, 3, i.toLong(), posMs, 10_000L + posMs, 44100, 2, pcm)
                )
            }
            assertEquals(96, manager.bufferStats.value.bufferedFrames)
            assertEquals(14 * 40L, manager.pollNextFrame()?.framePosMs)
        } finally {
            manager.reset()
            scope.cancel()
        }
    }

    @Test
    fun `connection status indicator classifies webrtc ice states and latency tiers`() {
        val host = assessParticipantConnection(
            Participant("h1", "DJ Host", "#8B5CF6", isHost = true, status = DeviceStatus.CONNECTED)
        )
        assertEquals(ConnectionQualityTier.EXCELLENT, host.tier)
        assertEquals(4, host.signalBars)
        assertEquals("Host Clock", host.qualityLabel)

        val p2pLowLatency = assessParticipantConnection(
            Participant(
                id = "p1",
                name = "Alice",
                avatarColorHex = "#38BDF8",
                isHost = false,
                status = DeviceStatus.CONNECTED,
                rttMs = 14L,
                driftMs = -8L,
                transportMode = TransportMode.WEBRTC_P2P,
                iceState = "CONNECTED"
            )
        )
        assertEquals(ConnectionQualityTier.EXCELLENT, p2pLowLatency.tier)
        assertEquals(4, p2pLowLatency.signalBars)
        assertTrue(p2pLowLatency.isWebRtcDirect)
        assertEquals("WebRTC P2P", p2pLowLatency.transportLabel)
        assertEquals("ICE CONNECTED", p2pLowLatency.iceSummary)
        assertEquals("14ms RTT", p2pLowLatency.latencyText)

        val highLatency = assessParticipantConnection(
            Participant(
                id = "p2",
                name = "Bob",
                avatarColorHex = "#10B981",
                isHost = false,
                status = DeviceStatus.CONNECTED,
                rttMs = 185L,
                driftMs = 110L,
                transportMode = TransportMode.WS_SIGNALING,
                iceState = "IDLE"
            )
        )
        assertEquals(ConnectionQualityTier.POOR, highLatency.tier)
        assertEquals(1, highLatency.signalBars)
        assertFalse(highLatency.isWebRtcDirect)
        assertEquals("WS Fallback", highLatency.iceSummary)

        val negotiating = assessParticipantConnection(
            Participant(
                id = "p3",
                name = "Cara",
                avatarColorHex = "#F59E0B",
                isHost = false,
                status = DeviceStatus.CONNECTED,
                rttMs = 25L,
                driftMs = 0L,
                transportMode = TransportMode.WS_SIGNALING,
                iceState = "CHECKING"
            )
        )
        assertEquals(ConnectionQualityTier.RECONNECTING, negotiating.tier)
        assertEquals("ICE Negotiating", negotiating.qualityLabel)

        val offline = assessParticipantConnection(
            Participant(
                id = "p4",
                name = "Dan",
                avatarColorHex = "#F43F5E",
                isHost = false,
                status = DeviceStatus.DISCONNECTED,
                iceState = "FAILED"
            )
        )
        assertEquals(ConnectionQualityTier.OFFLINE, offline.tier)
        assertEquals(0, offline.signalBars)
    }

    @Test
    fun `pcm transcoder normalizes mono audio and slices webrtc datachannel packets`() {
        // 1000 mono samples at 22050 Hz -> ~2000 stereo samples (8000 bytes) at 44100 Hz
        val monoInput = ByteArray(1000 * 2)
        val bb = java.nio.ByteBuffer.wrap(monoInput).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 1000) {
            bb.putShort(((i * 30) % 12000).toShort())
        }

        val normalized = PcmAudioTranscoder.normalizePcmTo44kStereo(monoInput, srcRate = 22050, srcChannels = 1)
        assertEquals(8000, normalized.size)

        val packets = PcmAudioTranscoder.slicePcmIntoWebRtcPackets(
            rawPcm = normalized,
            trackHash = "norm_test".hashCode(),
            playbackEpoch = 5,
            startPosMs = 0L,
            anchorHostTimeMs = 50_000L,
            padFinalFrame = true
        )
        assertEquals(2, packets.size)

        val firstDecoded = AudioBufferManager.decodeFrame(packets[0])
        val secondDecoded = AudioBufferManager.decodeFrame(packets[1])
        assertNotNull(firstDecoded)
        assertNotNull(secondDecoded)
        assertEquals(5, firstDecoded!!.playbackEpoch)
        assertEquals(0L, firstDecoded.framePosMs)
        assertEquals(40L, secondDecoded!!.framePosMs)
        assertEquals(AudioBufferManager.PCM_FRAME_BYTES, firstDecoded.pcmData.size)
        assertEquals(AudioBufferManager.PCM_FRAME_BYTES, secondDecoded.pcmData.size)
    }

    @Test
    fun `audio library viewmodel imports local file and converts to raw pcm webrtc stream`(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val tracks = BuiltInPartyTracks.ensureBuiltInTracks(app)
        val sampleWav = File(tracks.first().localPath!!)
        val libraryVm = AudioLibraryViewModel(app)

        val readyLatch = CountDownLatch(1)
        var readyTrack: AudioTrack? = null
        libraryVm.importPickedFile(
            file = sampleWav,
            title = "Picked Party WAV",
            autoConvertToPcm = true
        ) { converted ->
            readyTrack = converted
            readyLatch.countDown()
        }

        assertTrue(readyLatch.await(15, TimeUnit.SECONDS))
        assertNotNull(readyTrack)
        assertNotNull(readyTrack!!.pcmStreamPath)

        val state = libraryVm.uiState.value
        assertEquals(1, state.items.size)
        val item = state.items.first()
        assertEquals(PcmConversionStatus.READY, item.status)
        assertTrue(item.pcmBytesGenerated > 1_000_000L)
        assertTrue(item.pcmFrameCount > 100)

        // Verify raw PCM stream opens and WebRTC frame Flow emits valid SBAF packets
        libraryVm.openRawPcmStream(item.track.id)!!.use { stream ->
            val headerProbe = ByteArray(AudioBufferManager.PCM_FRAME_BYTES)
            assertEquals(AudioBufferManager.PCM_FRAME_BYTES, stream.read(headerProbe))
        }

        val emittedPackets = libraryVm.streamPcmFramesForWebRtc(
            trackId = item.track.id,
            playbackEpoch = 2,
            startPosMs = 0L,
            anchorHostTimeMs = 100_000L
        ).take(3).toList()
        assertEquals(3, emittedPackets.size)
        assertEquals(80L, AudioBufferManager.decodeFrame(emittedPackets[2])!!.framePosMs)
    }

    @Test
    fun `frequency analyzer separates bass and high frequency pcm tones and smooths smoothly`() {
        fun generateStereoSinePcm(freqHz: Double): ByteArray {
            val frames = AudioBufferManager.PCM_FRAME_BYTES / 4
            val out = ByteArray(frames * 4)
            val bb = java.nio.ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until frames) {
                val sample = (kotlin.math.sin(2.0 * Math.PI * freqHz * i / 44100.0) * 18000.0).toInt().toShort()
                bb.putShort(sample)
                bb.putShort(sample)
            }
            return out
        }

        val bassFrame = AudioFrequencyAnalyzer.computeRawSpectrum(generateStereoSinePcm(350.0))
        assertTrue(bassFrame.isLivePcmSignal)
        assertEquals(AudioFrequencyAnalyzer.BAND_COUNT, bassFrame.bands.size)
        assertTrue("Expected bassEnergy (${bassFrame.bassEnergy}) > highEnergy (${bassFrame.highEnergy})", bassFrame.bassEnergy > bassFrame.highEnergy)

        val highFrame = AudioFrequencyAnalyzer.computeRawSpectrum(generateStereoSinePcm(9500.0))
        assertTrue(highFrame.isLivePcmSignal)
        assertTrue("Expected highEnergy (${highFrame.highEnergy}) > bassEnergy (${highFrame.bassEnergy})", highFrame.highEnergy > highFrame.bassEnergy)

        val smoothed = AudioFrequencyAnalyzer.smooth(bassFrame, highFrame)
        assertTrue(smoothed.bassEnergy in highFrame.bassEnergy..bassFrame.bassEnergy)
        assertTrue(smoothed.highEnergy in bassFrame.highEnergy..highFrame.highEnergy)
    }

    @Test
    fun `sync health bar maps client and host drift variance to green yellow and red tiers`() {
        // Client: tight drift and variance -> GREEN
        val tightClient = assessSyncHealth(
            role = SessionRole.LISTENER,
            telemetry = SyncTelemetry(driftMs = 6L, driftVarianceMs = 5L, syncHealth = SyncHealth.LOCKED, sampleCount = 6),
            participants = emptyList(),
            driftToleranceMs = 22
        )
        assertEquals(SyncHealthTier.GREEN, tightClient.tier)
        assertTrue(tightClient.stabilityFraction > 0.8f)

        // Client: moderate drift variance -> YELLOW
        val moderateClient = assessSyncHealth(
            role = SessionRole.LISTENER,
            telemetry = SyncTelemetry(driftMs = 18L, driftVarianceMs = 36L, syncHealth = SyncHealth.CORRECTING, sampleCount = 6),
            participants = emptyList(),
            driftToleranceMs = 22
        )
        assertEquals(SyncHealthTier.YELLOW, moderateClient.tier)

        // Client: excessive drift variance or reconnecting -> RED
        val highDriftClient = assessSyncHealth(
            role = SessionRole.LISTENER,
            telemetry = SyncTelemetry(driftMs = 82L, driftVarianceMs = 68L, syncHealth = SyncHealth.CORRECTING, sampleCount = 6),
            participants = emptyList(),
            driftToleranceMs = 22
        )
        assertEquals(SyncHealthTier.RED, highDriftClient.tier)

        val reconnectingClient = assessSyncHealth(
            role = SessionRole.LISTENER,
            telemetry = SyncTelemetry(driftMs = 4L, driftVarianceMs = 2L, syncHealth = SyncHealth.RECONNECTING),
            participants = emptyList(),
            driftToleranceMs = 22
        )
        assertEquals(SyncHealthTier.RED, reconnectingClient.tier)

        // Host: evaluates drift variance across connected clients
        val hostPart = Participant("h1", "Host", "#8B5CF6", isHost = true, status = DeviceStatus.CONNECTED)
        val clientGreen1 = Participant("c1", "Phone A", "#38BDF8", isHost = false, status = DeviceStatus.CONNECTED, driftMs = -8L)
        val clientGreen2 = Participant("c2", "Phone B", "#10B981", isHost = false, status = DeviceStatus.CONNECTED, driftMs = 10L)
        val hostLocked = assessSyncHealth(
            role = SessionRole.HOST,
            telemetry = SyncTelemetry(syncHealth = SyncHealth.HOST_CLOCK),
            participants = listOf(hostPart, clientGreen1, clientGreen2),
            driftToleranceMs = 22
        )
        assertEquals(SyncHealthTier.GREEN, hostLocked.tier)

        val clientYellow = Participant("c2", "Phone B", "#10B981", isHost = false, status = DeviceStatus.CONNECTED, driftMs = 42L)
        val hostDrifting = assessSyncHealth(
            role = SessionRole.HOST,
            telemetry = SyncTelemetry(syncHealth = SyncHealth.HOST_CLOCK),
            participants = listOf(hostPart, clientGreen1, clientYellow),
            driftToleranceMs = 22
        )
        assertEquals(SyncHealthTier.YELLOW, hostDrifting.tier)

        val clientRed = Participant("c2", "Phone B", "#10B981", isHost = false, status = DeviceStatus.CONNECTED, driftMs = -95L)
        val hostCritical = assessSyncHealth(
            role = SessionRole.HOST,
            telemetry = SyncTelemetry(syncHealth = SyncHealth.HOST_CLOCK),
            participants = listOf(hostPart, clientGreen1, clientRed),
            driftToleranceMs = 22
        )
        assertEquals(SyncHealthTier.RED, hostCritical.tier)
    }

    @Test
    fun `audio ducking lowers volume when chat is active and round-trips over protocol`() {
        val disabled = PartyAudioSettings(chatDuckingEnabled = false, duckingReductionPercent = 40)
        assertEquals(1.0f, AudioDuckingHelper.computeTargetDuckMultiplier(disabled, isPartyChatActive = true), 0.001f)

        val enabled = PartyAudioSettings(chatDuckingEnabled = true, duckingReductionPercent = 40)
        assertEquals(1.0f, AudioDuckingHelper.computeTargetDuckMultiplier(enabled, isPartyChatActive = false), 0.001f)
        val duckMult = AudioDuckingHelper.computeTargetDuckMultiplier(enabled, isPartyChatActive = true)
        assertEquals(0.60f, duckMult, 0.001f)
        assertEquals(0.48f, AudioDuckingHelper.computeEffectiveVolume(0.80f, duckMult), 0.001f)

        // Protocol encode/decode round-trip
        val encodedMsg = ProtocolMessages.encodeAudioSettings(enabled)
        val msgJson = JSONObject(encodedMsg)
        assertEquals(ProtocolMessages.TYPE_AUDIO_SETTINGS, msgJson.getString("type"))
        val decoded = ProtocolMessages.jsonToAudioSettings(msgJson.optJSONObject("audioSettings"))
        assertTrue(decoded.chatDuckingEnabled)
        assertEquals(40, decoded.duckingReductionPercent)

        // Snapshot includes audioSettings cleanly
        val snapshotJson = JSONObject(
            ProtocolMessages.encodeRoomSnapshot(
                roomName = "Chat Party",
                hostIp = "192.168.1.10",
                httpPort = 8765,
                sessionStartedAtMs = 1000L,
                totalListenersJoined = 2,
                tracks = emptyList(),
                playbackState = PlaybackState(),
                participants = emptyList(),
                chatHistory = emptyList(),
                songRequests = emptyList(),
                audioSettings = enabled
            )
        )
        val snapAudio = ProtocolMessages.jsonToAudioSettings(snapshotJson.optJSONObject("audioSettings"))
        assertTrue(snapAudio.chatDuckingEnabled)
        assertEquals(40, snapAudio.duckingReductionPercent)
    }
}
