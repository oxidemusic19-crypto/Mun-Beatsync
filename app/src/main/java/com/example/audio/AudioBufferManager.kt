package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import com.example.model.AudioTrack as PartyTrack
import com.example.model.PlaybackState
import com.example.model.SessionRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.roundToInt

data class AudioFramePacket(
    val trackHash: Int,
    val playbackEpoch: Int,
    val sequence: Long,
    val framePosMs: Long,
    val targetHostTimeMs: Long,
    val sampleRate: Int,
    val channels: Int,
    val pcmData: ByteArray
)

data class AudioBufferStats(
    val bufferedFrames: Int = 0,
    val bufferedMs: Long = 0L,
    val fillPercent: Int = 0,
    val framesStreamed: Long = 0L,
    val framesDroppedLate: Long = 0L,
    val isP2pAudioStreaming: Boolean = false,
    val renderDriftMs: Long = 0L,
    val estimatedJitterMs: Long = 0L,
    val adaptivePrebufferFrames: Int = AudioBufferManager.MIN_PREBUFFER_FRAMES
)

class AudioBufferManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val estimatedHostNowMs: () -> Long,
    private val getClockOffsetMs: () -> Long,
    private val sendBinaryFrame: (ByteArray) -> Int,
    private val hasOpenAudioPeer: () -> Boolean,
    private val elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() }
) {
    companion object {
        const val FRAME_MAGIC = 0x53424146 // "SBAF"
        const val HEADER_BYTES = 42
        const val TARGET_SAMPLE_RATE = 44100
        const val TARGET_CHANNELS = 2
        const val BYTES_PER_SAMPLE = 2 // 16-bit PCM
        const val FRAME_DURATION_MS = 40L
        const val PCM_FRAME_BYTES =
            (TARGET_SAMPLE_RATE * TARGET_CHANNELS * BYTES_PER_SAMPLE * FRAME_DURATION_MS / 1000L).toInt() // 7056 bytes
        private const val LOOKAHEAD_WINDOW_MS = 480L
        private const val TARGET_BUFFER_FRAMES = 16 // ~640 ms healthy jitter target
        const val MAX_BUFFER_FRAMES = 96
        const val MIN_PREBUFFER_FRAMES = 3
        const val MAX_PREBUFFER_FRAMES = 10
        private const val HARDWARE_TARGET_LEAD_MS = 35L
        private const val MAX_LATE_DRIFT_MS = 180L

        fun encodeFrame(packet: AudioFramePacket): ByteArray {
            val out = ByteArray(HEADER_BYTES + packet.pcmData.size)
            val bb = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
            bb.putInt(FRAME_MAGIC)
            bb.putInt(packet.trackHash)
            bb.putInt(packet.playbackEpoch)
            bb.putLong(packet.sequence)
            bb.putLong(packet.framePosMs)
            bb.putLong(packet.targetHostTimeMs)
            bb.putInt(packet.sampleRate)
            bb.putShort(packet.channels.toShort())
            bb.put(packet.pcmData)
            return out
        }

        fun decodeFrame(raw: ByteArray): AudioFramePacket? {
            if (raw.size <= HEADER_BYTES) return null
            val bb = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
            if (bb.int != FRAME_MAGIC) return null
            val trackHash = bb.int
            val playbackEpoch = bb.int
            val sequence = bb.long
            val framePosMs = bb.long
            val targetHostTimeMs = bb.long
            val sampleRate = bb.int
            val channels = bb.short.toInt()
            val pcmLen = bb.remaining()
            if (pcmLen <= 0 || sampleRate <= 0 || channels <= 0) return null
            val pcm = ByteArray(pcmLen)
            bb.get(pcm)
            return AudioFramePacket(
                trackHash = trackHash,
                playbackEpoch = playbackEpoch,
                sequence = sequence,
                framePosMs = framePosMs,
                targetHostTimeMs = targetHostTimeMs,
                sampleRate = sampleRate,
                channels = channels,
                pcmData = pcm
            )
        }

        internal fun computePrebufferFrames(jitterMs: Long): Int {
            return when {
                jitterMs <= 20L -> MIN_PREBUFFER_FRAMES
                jitterMs <= 45L -> 5
                jitterMs <= 80L -> 7
                else -> MAX_PREBUFFER_FRAMES
            }
        }
    }

    private val bufferLock = Any()
    private val jitterBuffer = TreeMap<Long, AudioFramePacket>()
    private val silenceFrame = ByteArray(PCM_FRAME_BYTES)

    private var hostProducerJob: Job? = null
    private var listenerConsumerJob: Job? = null
    private var audioTrack: AudioTrack? = null
    private var totalWrittenStereoFrames: Long = 0L

    @Volatile private var currentRole: SessionRole = SessionRole.HOST
    @Volatile private var activeTrackHash: Int = 0
    @Volatile private var activePlaybackEpoch: Int = 0
    @Volatile private var activePlaybackState: PlaybackState = PlaybackState()
    @Volatile private var localVolume: Float = 0.85f
    @Volatile private var userCalibrationOffsetMs: Int = 0
    @Volatile private var framesCounter: Long = 0L
    @Volatile private var droppedLateCounter: Long = 0L
    @Volatile private var lastReceivedAtElapsedMs: Long = 0L
    @Volatile private var lastArrivalFramePosMs: Long = -1L
    @Volatile private var lastArrivalElapsedMs: Long = 0L
    @Volatile private var ewmaJitterMs: Double = 0.0
    @Volatile private var lastRenderedFramePosMs: Long = Long.MIN_VALUE

    private val pendingHostSpectra = TreeMap<Long, FrequencySpectrumFrame>()

    private val _bufferStats = MutableStateFlow(AudioBufferStats())
    val bufferStats: StateFlow<AudioBufferStats> = _bufferStats.asStateFlow()

    private val _listenerPositionMs = MutableStateFlow(0L)
    val listenerPositionMs: StateFlow<Long> = _listenerPositionMs.asStateFlow()

    private val _frequencySpectrum = MutableStateFlow(FrequencySpectrumFrame())
    val frequencySpectrum: StateFlow<FrequencySpectrumFrame> = _frequencySpectrum.asStateFlow()

    fun setLocalVolume(volume: Float) {
        localVolume = volume.coerceIn(0f, 1f)
        runCatching { audioTrack?.setVolume(localVolume) }
    }

    fun setCalibrationOffsetMs(offsetMs: Int) {
        userCalibrationOffsetMs = offsetMs
    }

    fun isActivelyRenderingP2pAudio(): Boolean {
        return currentRole == SessionRole.LISTENER &&
            activePlaybackState.isPlaying &&
            _bufferStats.value.isP2pAudioStreaming
    }

    fun onTransportUpdated(
        role: SessionRole,
        track: PartyTrack?,
        state: PlaybackState
    ) {
        currentRole = role
        val newTrackHash = track?.id?.hashCode() ?: 0
        val newEpoch = state.sequenceNumber.toInt()
        val trackChanged = newTrackHash != activeTrackHash
        val epochChanged = newEpoch != activePlaybackEpoch ||
            abs(state.anchorPositionMs - activePlaybackState.anchorPositionMs) > 250L &&
            state.anchorHostTimeMs != activePlaybackState.anchorHostTimeMs

        activeTrackHash = newTrackHash
        activePlaybackEpoch = newEpoch
        activePlaybackState = state

        if (trackChanged || epochChanged) {
            lastRenderedFramePosMs = state.anchorPositionMs - FRAME_DURATION_MS
            clearBuffer()
            if (role == SessionRole.LISTENER) {
                pauseAndFlushAudioTrack()
            }
        }

        if (role == SessionRole.HOST) {
            stopListenerConsumer()
            if (track != null && state.isPlaying) {
                startHostProducer(track, state, newEpoch)
            } else {
                stopHostProducer()
            }
        } else {
            stopHostProducer()
            if (track != null && state.isPlaying) {
                startListenerConsumer()
            } else {
                pauseAndFlushAudioTrack()
                if (!state.isPlaying) {
                    clearBuffer()
                    _frequencySpectrum.value = FrequencySpectrumFrame(isLivePcmSignal = false)
                }
            }
        }
    }

    fun onIncomingBinaryAudioFrame(rawBytes: ByteArray): Boolean {
        val packet = decodeFrame(rawBytes) ?: return false
        if (currentRole != SessionRole.LISTENER) return true
        if (activeTrackHash != 0 && packet.trackHash != activeTrackHash) return true
        if (activePlaybackEpoch != 0 && packet.playbackEpoch != activePlaybackEpoch) return true
        if (packet.framePosMs <= lastRenderedFramePosMs) return true

        val now = elapsedRealtimeMs()
        updateJitterEstimate(packet.framePosMs, now)
        lastReceivedAtElapsedMs = now
        enqueueFrame(packet)
        return true
    }

    private fun updateJitterEstimate(framePosMs: Long, nowElapsedMs: Long) {
        val prevArrival = lastArrivalElapsedMs
        val prevFramePos = lastArrivalFramePosMs
        if (prevArrival > 0L && prevFramePos >= 0L && framePosMs > prevFramePos) {
            val expectedDelta = (framePosMs - prevFramePos).coerceAtMost(1000L)
            val actualDelta = (nowElapsedMs - prevArrival).coerceAtLeast(0L)
            val instantJitter = abs(actualDelta - expectedDelta).toDouble()
            // RFC 3550-style smoothed inter-arrival jitter estimator
            ewmaJitterMs = ewmaJitterMs + (instantJitter - ewmaJitterMs) / 16.0
        }
        lastArrivalElapsedMs = nowElapsedMs
        lastArrivalFramePosMs = framePosMs
    }

    fun enqueueFrame(packet: AudioFramePacket) {
        synchronized(bufferLock) {
            if (!jitterBuffer.containsKey(packet.framePosMs)) {
                jitterBuffer[packet.framePosMs] = packet
                while (jitterBuffer.size > MAX_BUFFER_FRAMES) {
                    jitterBuffer.pollFirstEntry()
                }
                framesCounter++
            }
        }
        publishStats(
            isStreaming = true,
            driftMs = _bufferStats.value.renderDriftMs
        )
    }

    fun pollNextFrame(): AudioFramePacket? {
        val entry = synchronized(bufferLock) {
            jitterBuffer.pollFirstEntry()
        } ?: return null
        publishStats(
            isStreaming = _bufferStats.value.isP2pAudioStreaming,
            driftMs = _bufferStats.value.renderDriftMs
        )
        return entry.value
    }

    fun clearBuffer() {
        synchronized(bufferLock) {
            jitterBuffer.clear()
        }
        lastArrivalElapsedMs = 0L
        lastArrivalFramePosMs = -1L
        ewmaJitterMs = 0.0
        publishStats(isStreaming = false, driftMs = 0L)
    }

    private fun startHostProducer(track: PartyTrack, state: PlaybackState, playbackEpoch: Int) {
        hostProducerJob?.cancel()
        hostProducerJob = scope.launch(Dispatchers.IO) {
            val trackHash = track.id.hashCode()
            val startPosMs = state.anchorPositionMs.coerceAtLeast(0L)
            val anchorHostMs = state.anchorHostTimeMs
            var seq = 0L

            val preConvertedPcm = track.pcmStreamPath?.let { File(it) }?.takeIf {
                it.exists() && it.length() > 0L
            }
            val wavFile = track.localPath?.let { File(it) }?.takeIf {
                it.exists() && it.name.endsWith(".wav", ignoreCase = true)
            }

            when {
                preConvertedPcm != null -> {
                    streamFromPcmFile(
                        pcmFile = preConvertedPcm,
                        dataStartOverride = 0L,
                        trackHash = trackHash,
                        playbackEpoch = playbackEpoch,
                        startPosMs = startPosMs,
                        anchorHostMs = anchorHostMs
                    ) { seq++ }
                }
                wavFile != null && isStandard44kStereoWav(wavFile) -> {
                    streamFromPcmFile(
                        pcmFile = wavFile,
                        dataStartOverride = null,
                        trackHash = trackHash,
                        playbackEpoch = playbackEpoch,
                        startPosMs = startPosMs,
                        anchorHostMs = anchorHostMs
                    ) { seq++ }
                }
                else -> {
                    streamViaMediaCodec(track, trackHash, playbackEpoch, startPosMs, anchorHostMs) { seq++ }
                }
            }
        }
    }

    private suspend fun streamFromPcmFile(
        pcmFile: File,
        dataStartOverride: Long?,
        trackHash: Int,
        playbackEpoch: Int,
        startPosMs: Long,
        anchorHostMs: Long,
        nextSeq: () -> Long
    ) {
        RandomAccessFile(pcmFile, "r").use { raf ->
            val dataStart = dataStartOverride ?: findWavDataOffset(raf)
            val bytesPerMs = (TARGET_SAMPLE_RATE * TARGET_CHANNELS * BYTES_PER_SAMPLE) / 1000.0
            val byteOffset = dataStart + ((startPosMs * bytesPerMs).toLong() and -4L)
            if (byteOffset >= raf.length()) return
            raf.seek(byteOffset)

            val pcmBuf = ByteArray(PCM_FRAME_BYTES)
            var framePosMs = startPosMs

            while (
                scope.isActive &&
                activePlaybackState.isPlaying &&
                activeTrackHash == trackHash &&
                activePlaybackEpoch == playbackEpoch
            ) {
                val targetHostTimeMs = anchorHostMs + (framePosMs - startPosMs)
                val nowHostMs = elapsedRealtimeMs()
                flushHostSpectraUpTo(nowHostMs)

                // Pace ahead by LOOKAHEAD_WINDOW_MS so listener buffers stay healthy without flooding
                val waitMs = targetHostTimeMs - (nowHostMs + LOOKAHEAD_WINDOW_MS)
                if (waitMs > 10L) {
                    delay(minOf(waitMs, 40L))
                    continue
                }

                val read = raf.read(pcmBuf)
                if (read <= 0) break
                val payload = if (read == pcmBuf.size) pcmBuf.copyOf() else pcmBuf.copyOf(read)
                enqueueHostSpectrum(targetHostTimeMs, payload)

                if (hasOpenAudioPeer()) {
                    val encoded = encodeFrame(
                        AudioFramePacket(
                            trackHash = trackHash,
                            playbackEpoch = playbackEpoch,
                            sequence = nextSeq(),
                            framePosMs = framePosMs,
                            targetHostTimeMs = targetHostTimeMs,
                            sampleRate = TARGET_SAMPLE_RATE,
                            channels = TARGET_CHANNELS,
                            pcmData = payload
                        )
                    )
                    val sentPeers = sendBinaryFrame(encoded)
                    if (sentPeers > 0) {
                        framesCounter++
                        publishStats(isStreaming = true, driftMs = 0L)
                    }
                }
                framePosMs += FRAME_DURATION_MS
            }
        }
    }

    private suspend fun streamViaMediaCodec(
        track: PartyTrack,
        trackHash: Int,
        playbackEpoch: Int,
        startPosMs: Long,
        anchorHostMs: Long,
        nextSeq: () -> Long
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            when {
                !track.localPath.isNullOrBlank() -> extractor.setDataSource(track.localPath)
                !track.contentUriString.isNullOrBlank() -> extractor.setDataSource(context, Uri.parse(track.contentUriString), null)
                else -> return
            }

            var audioIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioIndex = i
                    format = f
                    break
                }
            }
            if (audioIndex < 0 || format == null) return

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return
            extractor.selectTrack(audioIndex)
            if (startPosMs > 0L) {
                extractor.seekTo(startPosMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val info = MediaCodec.BufferInfo()
            val pcmAccumulator = java.io.ByteArrayOutputStream()
            var framePosMs = startPosMs
            var inputDone = false

            while (
                scope.isActive &&
                activePlaybackState.isPlaying &&
                activeTrackHash == trackHash &&
                activePlaybackEpoch == playbackEpoch
            ) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(5000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        val size = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 8000)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && info.size > 0) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        val rawPcm = ByteArray(info.size)
                        outBuf.get(rawPcm)
                        val normalized = normalizeTo44kStereo(rawPcm, srcRate, srcChannels)
                        pcmAccumulator.write(normalized)
                    }
                    codec.releaseOutputBuffer(outIdx, false)

                    while (
                        pcmAccumulator.size() >= PCM_FRAME_BYTES &&
                        scope.isActive &&
                        activePlaybackState.isPlaying &&
                        activeTrackHash == trackHash &&
                        activePlaybackEpoch == playbackEpoch
                    ) {
                        val allBytes = pcmAccumulator.toByteArray()
                        val framePayload = allBytes.copyOfRange(0, PCM_FRAME_BYTES)
                        pcmAccumulator.reset()
                        if (allBytes.size > PCM_FRAME_BYTES) {
                            pcmAccumulator.write(allBytes, PCM_FRAME_BYTES, allBytes.size - PCM_FRAME_BYTES)
                        }

                        val targetHostTimeMs = anchorHostMs + (framePosMs - startPosMs)
                        while (
                            targetHostTimeMs > elapsedRealtimeMs() + LOOKAHEAD_WINDOW_MS &&
                            scope.isActive &&
                            activePlaybackState.isPlaying &&
                            activeTrackHash == trackHash &&
                            activePlaybackEpoch == playbackEpoch
                        ) {
                            flushHostSpectraUpTo(elapsedRealtimeMs())
                            delay(25L)
                        }
                        enqueueHostSpectrum(targetHostTimeMs, framePayload)
                        flushHostSpectraUpTo(elapsedRealtimeMs())

                        if (hasOpenAudioPeer()) {
                            val encoded = encodeFrame(
                                AudioFramePacket(
                                    trackHash = trackHash,
                                    playbackEpoch = playbackEpoch,
                                    sequence = nextSeq(),
                                    framePosMs = framePosMs,
                                    targetHostTimeMs = targetHostTimeMs,
                                    sampleRate = TARGET_SAMPLE_RATE,
                                    channels = TARGET_CHANNELS,
                                    pcmData = framePayload
                                )
                            )
                            val sentPeers = sendBinaryFrame(encoded)
                            if (sentPeers > 0) {
                                framesCounter++
                                publishStats(isStreaming = true, driftMs = 0L)
                            }
                        }
                        framePosMs += FRAME_DURATION_MS
                    }

                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun startListenerConsumer() {
        if (listenerConsumerJob?.isActive == true) return
        listenerConsumerJob = scope.launch(Dispatchers.Default) {
            while (isActive && currentRole == SessionRole.LISTENER && activePlaybackState.isPlaying) {
                val nowElapsed = elapsedRealtimeMs()
                val streamRecentlyActive = (nowElapsed - lastReceivedAtElapsedMs) < 1500L

                val bufferedCount = synchronized(bufferLock) { jitterBuffer.size }
                if (!streamRecentlyActive || bufferedCount == 0) {
                    if (!streamRecentlyActive && _bufferStats.value.isP2pAudioStreaming) {
                        pauseAndFlushAudioTrack()
                        publishStats(isStreaming = false, driftMs = 0L)
                    }
                    delay(20L)
                    continue
                }

                // Adaptive jitter pre-buffering before starting or resuming AudioTrack
                val requiredPrebuffer = computePrebufferFrames(ewmaJitterMs.roundToInt().toLong())
                val firstTargetHostMs = synchronized(bufferLock) { jitterBuffer.firstEntry()?.value?.targetHostTimeMs }
                if (audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING && bufferedCount < requiredPrebuffer) {
                    val hostNow = estimatedHostNowMs()
                    if (firstTargetHostMs == null || firstTargetHostMs - hostNow > 80L) {
                        delay(15L)
                        continue
                    }
                }

                val frame = pollNextFrame() ?: continue
                if (frame.trackHash != activeTrackHash || frame.playbackEpoch != activePlaybackEpoch) {
                    continue
                }

                // Schedule playback smoothly until frame reaches hardware lead window
                while (
                    isActive &&
                    currentRole == SessionRole.LISTENER &&
                    activePlaybackState.isPlaying &&
                    frame.trackHash == activeTrackHash &&
                    frame.playbackEpoch == activePlaybackEpoch
                ) {
                    val hostOffset = getClockOffsetMs()
                    val scheduledLocalElapsedMs = frame.targetHostTimeMs - hostOffset + userCalibrationOffsetMs
                    val now = elapsedRealtimeMs()
                    val queuedHardwareMs = estimateQueuedHardwareMs()
                    val projectedRenderDriftMs = (now + queuedHardwareMs) - scheduledLocalElapsedMs

                    if (projectedRenderDriftMs < -HARDWARE_TARGET_LEAD_MS) {
                        val waitMs = (-projectedRenderDriftMs - 20L).coerceIn(5L, 40L)
                        delay(waitMs)
                        continue
                    }
                    break
                }

                if (!isActive ||
                    currentRole != SessionRole.LISTENER ||
                    !activePlaybackState.isPlaying ||
                    frame.trackHash != activeTrackHash ||
                    frame.playbackEpoch != activePlaybackEpoch
                ) {
                    continue
                }

                val hostOffset = getClockOffsetMs()
                val scheduledLocalElapsedMs = frame.targetHostTimeMs - hostOffset + userCalibrationOffsetMs
                val now = elapsedRealtimeMs()
                val queuedHardwareMs = estimateQueuedHardwareMs()
                val driftMs = (now + queuedHardwareMs) - scheduledLocalElapsedMs

                // Drop late frame so playback stays locked to real time
                if (driftMs > MAX_LATE_DRIFT_MS) {
                    droppedLateCounter++
                    lastRenderedFramePosMs = maxOf(lastRenderedFramePosMs, frame.framePosMs)
                    publishStats(isStreaming = true, driftMs = driftMs)
                    continue
                }

                val trackOut = ensureAudioTrack()
                if (trackOut != null) {
                    if (trackOut.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        runCatching { trackOut.play() }
                    }

                    // Conceal minor 1-frame packet loss when hardware buffer is nearly empty
                    if (
                        lastRenderedFramePosMs >= 0L &&
                        frame.framePosMs - lastRenderedFramePosMs == FRAME_DURATION_MS * 2 &&
                        queuedHardwareMs < 25L
                    ) {
                        val writtenSilence = trackOut.write(silenceFrame, 0, silenceFrame.size)
                        if (writtenSilence > 0) {
                            totalWrittenStereoFrames += writtenSilence / (TARGET_CHANNELS * BYTES_PER_SAMPLE)
                        }
                    }

                    // Gentle sample-rate micro-stretch for smooth drift correction
                    val targetRate = when {
                        abs(driftMs) <= 20L -> TARGET_SAMPLE_RATE
                        driftMs > 20L -> (TARGET_SAMPLE_RATE * 1.025).toInt()
                        else -> (TARGET_SAMPLE_RATE * 0.975).toInt()
                    }
                    runCatching {
                        if (abs(trackOut.playbackRate - targetRate) > 120) {
                            trackOut.playbackRate = targetRate
                        }
                    }

                    val writtenBytes = trackOut.write(frame.pcmData, 0, frame.pcmData.size)
                    if (writtenBytes > 0) {
                        totalWrittenStereoFrames += writtenBytes / (TARGET_CHANNELS * BYTES_PER_SAMPLE)
                    }
                    emitSpectrumFromPcm(frame.pcmData)
                    lastRenderedFramePosMs = frame.framePosMs
                    _listenerPositionMs.value = frame.framePosMs
                    publishStats(isStreaming = true, driftMs = driftMs)
                }
            }
        }
    }

    private fun estimateQueuedHardwareMs(): Long {
        val track = audioTrack ?: return 0L
        if (track.state != AudioTrack.STATE_INITIALIZED || track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            return 0L
        }
        return runCatching {
            val playedFrames = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            val queuedFrames = (totalWrittenStereoFrames - playedFrames).coerceAtLeast(0L)
            ((queuedFrames * 1000L) / TARGET_SAMPLE_RATE).coerceIn(0L, 400L)
        }.getOrDefault(0L)
    }

    private fun ensureAudioTrack(): AudioTrack? {
        audioTrack?.let { if (it.state == AudioTrack.STATE_INITIALIZED) return it }
        return runCatching {
            val minBuf = AudioTrack.getMinBufferSize(
                TARGET_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufBytes = maxOf(minBuf * 2, PCM_FRAME_BYTES * 6)
            totalWrittenStereoFrames = 0L
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(TARGET_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufBytes)
                .build()
                .apply {
                    setVolume(localVolume)
                    audioTrack = this
                }
        }.getOrNull()
    }

    private fun pauseAndFlushAudioTrack() {
        runCatching {
            audioTrack?.let { at ->
                if (at.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    at.pause()
                }
                at.flush()
            }
        }
        totalWrittenStereoFrames = 0L
    }

    private fun stopHostProducer() {
        hostProducerJob?.cancel()
        hostProducerJob = null
        synchronized(pendingHostSpectra) { pendingHostSpectra.clear() }
        _frequencySpectrum.value = FrequencySpectrumFrame(isLivePcmSignal = false)
    }

    private fun stopListenerConsumer() {
        listenerConsumerJob?.cancel()
        listenerConsumerJob = null
        pauseAndFlushAudioTrack()
        _frequencySpectrum.value = FrequencySpectrumFrame(isLivePcmSignal = false)
    }

    private fun enqueueHostSpectrum(targetHostMs: Long, pcmBytes: ByteArray) {
        val raw = AudioFrequencyAnalyzer.computeRawSpectrum(pcmBytes)
        synchronized(pendingHostSpectra) {
            pendingHostSpectra[targetHostMs] = raw
            while (pendingHostSpectra.size > 32) {
                pendingHostSpectra.pollFirstEntry()
            }
        }
    }

    private fun flushHostSpectraUpTo(nowMs: Long) {
        var latest: FrequencySpectrumFrame? = null
        synchronized(pendingHostSpectra) {
            while (pendingHostSpectra.isNotEmpty()) {
                val firstKey = pendingHostSpectra.firstKey()
                if (firstKey <= nowMs + 45L) {
                    latest = pendingHostSpectra.pollFirstEntry()?.value
                } else {
                    break
                }
            }
        }
        latest?.let { raw ->
            _frequencySpectrum.value = AudioFrequencyAnalyzer.smooth(_frequencySpectrum.value, raw)
        }
    }

    private fun emitSpectrumFromPcm(pcmBytes: ByteArray) {
        val raw = AudioFrequencyAnalyzer.computeRawSpectrum(pcmBytes)
        _frequencySpectrum.value = AudioFrequencyAnalyzer.smooth(_frequencySpectrum.value, raw)
    }

    private fun publishStats(isStreaming: Boolean, driftMs: Long) {
        val count = synchronized(bufferLock) { jitterBuffer.size }
        val bufferedMs = count * FRAME_DURATION_MS
        val jitterRounded = ewmaJitterMs.roundToInt().toLong().coerceAtLeast(0L)
        val prebufferTarget = computePrebufferFrames(jitterRounded)
        val fill = if (currentRole == SessionRole.HOST) {
            if (isStreaming) 100 else 0
        } else {
            ((count.toFloat() / TARGET_BUFFER_FRAMES.toFloat()) * 100f).toInt().coerceIn(0, 100)
        }
        _bufferStats.value = AudioBufferStats(
            bufferedFrames = count,
            bufferedMs = bufferedMs,
            fillPercent = fill,
            framesStreamed = framesCounter,
            framesDroppedLate = droppedLateCounter,
            isP2pAudioStreaming = isStreaming,
            renderDriftMs = driftMs,
            estimatedJitterMs = jitterRounded,
            adaptivePrebufferFrames = prebufferTarget
        )
    }

    private fun isStandard44kStereoWav(file: File): Boolean {
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 44L) return@use false
                val hdr = ByteArray(36)
                raf.readFully(hdr)
                val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
                val channels = bb.getShort(22).toInt()
                val rate = bb.getInt(24)
                val bits = bb.getShort(34).toInt()
                channels == TARGET_CHANNELS && rate == TARGET_SAMPLE_RATE && bits == 16
            }
        }.getOrDefault(false)
    }

    private fun findWavDataOffset(raf: RandomAccessFile): Long {
        return runCatching {
            if (raf.length() < 44L) return@runCatching 44L
            raf.seek(12L)
            val chunkHdr = ByteArray(8)
            while (raf.filePointer + 8L <= raf.length()) {
                raf.readFully(chunkHdr)
                val id = String(chunkHdr, 0, 4, Charsets.US_ASCII)
                val size = ByteBuffer.wrap(chunkHdr, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                if (id == "data") {
                    return@runCatching raf.filePointer
                }
                val skip = if (size % 2L == 1L) size + 1L else size
                raf.seek(raf.filePointer + skip)
            }
            44L
        }.getOrDefault(44L)
    }

    private fun normalizeTo44kStereo(input: ByteArray, srcRate: Int, srcChannels: Int): ByteArray {
        if (srcRate == TARGET_SAMPLE_RATE && srcChannels == TARGET_CHANNELS) return input
        val inSamples = input.size / (2 * srcChannels.coerceAtLeast(1))
        if (inSamples <= 0) return ByteArray(0)
        val outSamples = ((inSamples.toLong() * TARGET_SAMPLE_RATE) / srcRate.coerceAtLeast(8000)).toInt().coerceAtLeast(1)
        val out = ByteArray(outSamples * 4)
        val inBb = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN)
        val outBb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until outSamples) {
            val srcIdx = ((i.toLong() * inSamples) / outSamples).toInt().coerceIn(0, inSamples - 1)
            val bytePos = srcIdx * srcChannels * 2
            val left = inBb.getShort(bytePos)
            val right = if (srcChannels >= 2 && bytePos + 2 < input.size - 1) inBb.getShort(bytePos + 2) else left
            outBb.putShort(left)
            outBb.putShort(right)
        }
        return out
    }

    fun reset() {
        stopHostProducer()
        stopListenerConsumer()
        clearBuffer()
        activePlaybackEpoch = 0
        lastRenderedFramePosMs = Long.MIN_VALUE
        framesCounter = 0L
        droppedLateCounter = 0L
        lastReceivedAtElapsedMs = 0L
        totalWrittenStereoFrames = 0L
        runCatching { audioTrack?.release() }
        audioTrack = null
    }
}
