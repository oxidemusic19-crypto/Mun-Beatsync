package com.example.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class PcmTranscodeResult(
    val pcmFile: File,
    val totalPcmBytes: Long,
    val totalFrames: Int,
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val sourceSampleRate: Int,
    val sourceChannels: Int
)

object PcmAudioTranscoder {

    const val TARGET_SAMPLE_RATE = AudioBufferManager.TARGET_SAMPLE_RATE
    const val TARGET_CHANNELS = AudioBufferManager.TARGET_CHANNELS
    const val TARGET_BITS_PER_SAMPLE = 16
    const val WEBRTC_FRAME_BYTES = AudioBufferManager.PCM_FRAME_BYTES // 7056 bytes = 40ms @ 44.1kHz stereo 16-bit

    /**
     * Converts a local audio Uri (WAV, MP3, M4A, AAC, OGG, FLAC) into a standardized
     * 44.1 kHz, 16-bit signed little-endian stereo raw PCM file suitable for low-latency
     * 40 ms frame streaming over WebRTC DataChannels.
     */
    suspend fun transcodeUriToPcmFile(
        context: Context,
        uri: Uri,
        outputFile: File,
        expectedDurationMs: Long = 0L,
        onProgress: (Float) -> Unit = {}
    ): PcmTranscodeResult = withContext(Dispatchers.IO) {
        outputFile.parentFile?.mkdirs()

        // Fast-path if source is an uncompressed RIFF/WAV file
        val wavHeader = inspectWavStream(context.contentResolver.openInputStream(uri))
        if (wavHeader != null) {
            return@withContext transcodeWavInputStream(
                inputProvider = { context.contentResolver.openInputStream(uri) },
                wavHeader = wavHeader,
                outputFile = outputFile,
                onProgress = onProgress
            )
        }

        // General MediaExtractor + MediaCodec decode -> resample/upmix -> raw PCM stream
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var audioTrackIdx = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIdx = i
                    format = trackFormat
                    break
                }
            }
            require(audioTrackIdx >= 0 && format != null) { "No audio track found in selected file" }

            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalStateException("Missing audio MIME type")
            val srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else {
                expectedDurationMs.coerceAtLeast(1000L) * 1000L
            }

            extractor.selectTrack(audioTrackIdx)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var totalBytesWritten = 0L

            BufferedOutputStream(FileOutputStream(outputFile, false), 32 * 1024).use { out ->
                while (!outputDone && currentCoroutineContext().isActive) {
                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(10_000L)
                        if (inIdx >= 0) {
                            val inBuf = codec.getInputBuffer(inIdx)
                            val sampleSize = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inIdx,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outIdx = codec.dequeueOutputBuffer(info, 10_000L)
                    if (outIdx >= 0) {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val rawChunk = ByteArray(info.size)
                            outBuf.get(rawChunk)

                            val normalized = normalizePcmTo44kStereo(rawChunk, srcRate, srcChannels)
                            if (normalized.isNotEmpty()) {
                                out.write(normalized)
                                totalBytesWritten += normalized.size
                            }

                            if (durationUs > 0L) {
                                val pct = (info.presentationTimeUs.toFloat() / durationUs.toFloat()).coerceIn(0f, 0.99f)
                                onProgress(pct)
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
                out.flush()
            }

            onProgress(1f)
            val totalFrames = ((totalBytesWritten + WEBRTC_FRAME_BYTES - 1L) / WEBRTC_FRAME_BYTES).toInt()
            PcmTranscodeResult(
                pcmFile = outputFile,
                totalPcmBytes = totalBytesWritten,
                totalFrames = totalFrames,
                sampleRate = TARGET_SAMPLE_RATE,
                channels = TARGET_CHANNELS,
                bitsPerSample = TARGET_BITS_PER_SAMPLE,
                sourceSampleRate = srcRate,
                sourceChannels = srcChannels
            )
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * Converts a File directly (used for local WAV/audio files or tests).
     */
    suspend fun transcodeWavFileToPcmFile(
        sourceWav: File,
        outputFile: File,
        onProgress: (Float) -> Unit = {}
    ): PcmTranscodeResult = withContext(Dispatchers.IO) {
        outputFile.parentFile?.mkdirs()
        val wavHeader = inspectWavStream(FileInputStream(sourceWav))
            ?: throw IllegalArgumentException("Source file is not a valid 16-bit PCM WAV file")
        transcodeWavInputStream(
            inputProvider = { FileInputStream(sourceWav) },
            wavHeader = wavHeader,
            outputFile = outputFile,
            onProgress = onProgress
        )
    }

    private suspend fun transcodeWavInputStream(
        inputProvider: () -> InputStream?,
        wavHeader: WavInfo,
        outputFile: File,
        onProgress: (Float) -> Unit
    ): PcmTranscodeResult {
        var totalWritten = 0L
        val stream = inputProvider() ?: throw IllegalStateException("Unable to open audio stream")
        BufferedInputStream(stream, 32 * 1024).use { bis ->
            var skipped = 0L
            while (skipped < wavHeader.dataOffset) {
                val n = bis.skip(wavHeader.dataOffset - skipped)
                if (n <= 0L) {
                    if (bis.read() < 0) break
                    skipped++
                } else {
                    skipped += n
                }
            }

            val bytesPerSampleAlign = (wavHeader.channels * (wavHeader.bitsPerSample / 8)).coerceAtLeast(2)
            val rawChunkSize = (16 * 1024 / bytesPerSampleAlign) * bytesPerSampleAlign
            val readBuf = ByteArray(rawChunkSize)
            var bytesReadTotal = 0L

            BufferedOutputStream(FileOutputStream(outputFile, false), 32 * 1024).use { out ->
                while (currentCoroutineContext().isActive) {
                    val read = bis.read(readBuf)
                    if (read <= 0) break
                    val alignedLen = (read / bytesPerSampleAlign) * bytesPerSampleAlign
                    if (alignedLen > 0) {
                        val slice = if (alignedLen == readBuf.size) readBuf else readBuf.copyOf(alignedLen)
                        val normalized = normalizePcmTo44kStereo(
                            input = slice,
                            srcRate = wavHeader.sampleRate,
                            srcChannels = wavHeader.channels
                        )
                        out.write(normalized)
                        totalWritten += normalized.size
                    }
                    bytesReadTotal += read
                    if (wavHeader.dataSize > 0L) {
                        onProgress((bytesReadTotal.toFloat() / wavHeader.dataSize.toFloat()).coerceIn(0f, 0.99f))
                    }
                }
                out.flush()
            }
        }
        onProgress(1f)
        val totalFrames = ((totalWritten + WEBRTC_FRAME_BYTES - 1L) / WEBRTC_FRAME_BYTES).toInt()
        return PcmTranscodeResult(
            pcmFile = outputFile,
            totalPcmBytes = totalWritten,
            totalFrames = totalFrames,
            sampleRate = TARGET_SAMPLE_RATE,
            channels = TARGET_CHANNELS,
            bitsPerSample = TARGET_BITS_PER_SAMPLE,
            sourceSampleRate = wavHeader.sampleRate,
            sourceChannels = wavHeader.channels
        )
    }

    /**
     * Normalizes 16-bit little-endian PCM from arbitrary sample rate & channel count into
     * 44.1 kHz 16-bit stereo PCM for uniform WebRTC DataChannel transmission.
     */
    fun normalizePcmTo44kStereo(input: ByteArray, srcRate: Int, srcChannels: Int): ByteArray {
        val safeChannels = srcChannels.coerceAtLeast(1)
        val safeRate = srcRate.coerceAtLeast(8000)
        if (safeRate == TARGET_SAMPLE_RATE && safeChannels == TARGET_CHANNELS) return input

        val bytesPerFrame = 2 * safeChannels
        val inSamples = input.size / bytesPerFrame
        if (inSamples <= 0) return ByteArray(0)

        val outSamples = ((inSamples.toLong() * TARGET_SAMPLE_RATE) / safeRate).toInt().coerceAtLeast(1)
        val out = ByteArray(outSamples * 4)
        val inBb = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN)
        val outBb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until outSamples) {
            val srcPos = (i.toDouble() * (inSamples - 1).coerceAtLeast(0)) / (outSamples - 1).coerceAtLeast(1)
            val idx0 = srcPos.toInt().coerceIn(0, inSamples - 1)
            val idx1 = (idx0 + 1).coerceIn(0, inSamples - 1)
            val frac = srcPos - idx0

            val bytePos0 = idx0 * bytesPerFrame
            val bytePos1 = idx1 * bytesPerFrame

            val l0 = inBb.getShort(bytePos0).toDouble()
            val l1 = inBb.getShort(bytePos1).toDouble()
            val left = (l0 + (l1 - l0) * frac).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

            val r0 = if (safeChannels >= 2 && bytePos0 + 3 < input.size) inBb.getShort(bytePos0 + 2).toDouble() else l0
            val r1 = if (safeChannels >= 2 && bytePos1 + 3 < input.size) inBb.getShort(bytePos1 + 2).toDouble() else l1
            val right = (r0 + (r1 - r0) * frac).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

            outBb.putShort(left)
            outBb.putShort(right)
        }
        return out
    }

    /**
     * Slices raw 44.1 kHz stereo 16-bit PCM bytes into fixed-size 40 ms (7056-byte) WebRTC DataChannel packets.
     */
    fun slicePcmIntoWebRtcPackets(
        rawPcm: ByteArray,
        trackHash: Int,
        playbackEpoch: Int,
        startPosMs: Long = 0L,
        anchorHostTimeMs: Long = 0L,
        padFinalFrame: Boolean = false
    ): List<ByteArray> {
        if (rawPcm.isEmpty()) return emptyList()
        val packets = ArrayList<ByteArray>((rawPcm.size / WEBRTC_FRAME_BYTES) + 1)
        var offset = 0
        var seq = 0L
        var framePosMs = startPosMs

        while (offset < rawPcm.size) {
            val remaining = rawPcm.size - offset
            val len = minOf(WEBRTC_FRAME_BYTES, remaining)
            val payload = if (len == WEBRTC_FRAME_BYTES) {
                rawPcm.copyOfRange(offset, offset + len)
            } else if (padFinalFrame) {
                ByteArray(WEBRTC_FRAME_BYTES).also { padded ->
                    System.arraycopy(rawPcm, offset, padded, 0, len)
                }
            } else {
                rawPcm.copyOfRange(offset, offset + len)
            }

            val targetHostTimeMs = anchorHostTimeMs + (framePosMs - startPosMs)
            packets.add(
                AudioBufferManager.encodeFrame(
                    AudioFramePacket(
                        trackHash = trackHash,
                        playbackEpoch = playbackEpoch,
                        sequence = seq++,
                        framePosMs = framePosMs,
                        targetHostTimeMs = targetHostTimeMs,
                        sampleRate = TARGET_SAMPLE_RATE,
                        channels = TARGET_CHANNELS,
                        pcmData = payload
                    )
                )
            )
            offset += len
            framePosMs += AudioBufferManager.FRAME_DURATION_MS
        }
        return packets
    }

    private data class WavInfo(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val dataOffset: Long,
        val dataSize: Long
    )

    private fun inspectWavStream(stream: InputStream?): WavInfo? {
        if (stream == null) return null
        return stream.use { s ->
            runCatching {
                val hdr = ByteArray(4096)
                val read = s.read(hdr)
                if (read < 44) return@runCatching null
                val riff = String(hdr, 0, 4, Charsets.US_ASCII)
                val wave = String(hdr, 8, 4, Charsets.US_ASCII)
                if (riff != "RIFF" || wave != "WAVE") return@runCatching null

                var pos = 12
                var audioFmt = 1
                var channels = 2
                var sampleRate = TARGET_SAMPLE_RATE
                var bits = 16
                var dataOffset = -1L
                var dataSize = 0L

                val bb = ByteBuffer.wrap(hdr, 0, read).order(ByteOrder.LITTLE_ENDIAN)
                while (pos + 8 <= read) {
                    val id = String(hdr, pos, 4, Charsets.US_ASCII)
                    val chunkSize = bb.getInt(pos + 4).toLong() and 0xFFFFFFFFL
                    val bodyStart = pos + 8
                    if (id == "fmt " && bodyStart + 16 <= read) {
                        audioFmt = bb.getShort(bodyStart).toInt()
                        channels = bb.getShort(bodyStart + 2).toInt()
                        sampleRate = bb.getInt(bodyStart + 4)
                        bits = bb.getShort(bodyStart + 14).toInt()
                    } else if (id == "data") {
                        dataOffset = bodyStart.toLong()
                        dataSize = chunkSize
                        break
                    }
                    val advance = 8 + chunkSize.toInt() + (if (chunkSize % 2L == 1L) 1 else 0)
                    if (advance <= 8) break
                    pos += advance
                }

                if (audioFmt == 1 && bits == 16 && dataOffset > 0L && channels > 0 && sampleRate > 0) {
                    WavInfo(sampleRate, channels, bits, dataOffset, dataSize)
                } else null
            }.getOrNull()
        }
    }
}
