package com.example.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

data class ImportedTrackMetadata(
    val title: String,
    val artist: String,
    val durationMs: Long,
    val mimeType: String,
    val fileSizeBytes: Long,
    val waveformBins: List<Float>
)

object AudioWaveformExtractor {

    private const val TARGET_BINS = 96

    suspend fun extractMetadataAndWaveform(
        context: Context,
        uri: Uri
    ): ImportedTrackMetadata = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var displayName = "Imported Track"
        var sizeBytes = 0L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) displayName = cursor.getString(nameIdx) ?: displayName
                    if (sizeIdx >= 0) sizeBytes = cursor.getLong(sizeIdx)
                }
            }

        val mimeType = resolver.getType(uri) ?: guessMimeType(displayName)
        var title = displayName.substringBeforeLast('.')
        var artist = "Local Library"
        var durationMs = 0L

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() }?.let { title = it }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() }?.let { artist = it }
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
        } finally {
            runCatching { retriever.release() }
        }

        val waveform = extractWaveformFromUri(context, uri, durationMs)
        ImportedTrackMetadata(
            title = title,
            artist = artist,
            durationMs = durationMs.coerceAtLeast(1000L),
            mimeType = mimeType,
            fileSizeBytes = sizeBytes,
            waveformBins = waveform
        )
    }

    fun extractWaveformFromWavFile(file: File): List<Float> {
        if (!file.exists() || file.length() < 64) return defaultWaveform()
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val dataOffset = 44L
                val dataLength = (file.length() - dataOffset).coerceAtLeast(2L)
                val binBytes = (dataLength / TARGET_BINS).coerceAtLeast(2L)
                val sampleWindow = minOf(binBytes, 4096L).toInt()
                val buf = ByteArray(sampleWindow)
                val bins = ArrayList<Float>(TARGET_BINS)
                var maxRms = 1.0
                val rawRms = DoubleArray(TARGET_BINS)
                for (i in 0 until TARGET_BINS) {
                    val pos = dataOffset + i * binBytes
                    if (pos + 2 < file.length()) {
                        raf.seek(pos)
                        val read = raf.read(buf, 0, minOf(sampleWindow, (file.length() - pos).toInt()))
                        var sumSq = 0.0
                        var count = 0
                        var idx = 0
                        while (idx + 1 < read) {
                            val lo = buf[idx].toInt() and 0xFF
                            val hi = buf[idx + 1].toInt()
                            val sample = ((hi shl 8) or lo).toDouble() / 32768.0
                            sumSq += sample * sample
                            count++
                            idx += 2
                        }
                        val rms = if (count > 0) sqrt(sumSq / count) else 0.0
                        rawRms[i] = rms
                        if (rms > maxRms) maxRms = rms
                    }
                }
                val norm = rawRms.maxOrNull()?.coerceAtLeast(0.05) ?: 1.0
                for (i in 0 until TARGET_BINS) {
                    val v = (rawRms[i] / norm).toFloat().coerceIn(0.14f, 1.0f)
                    bins.add(v)
                }
                bins
            }
        } catch (_: Exception) {
            defaultWaveform()
        }
    }

    private fun extractWaveformFromUri(context: Context, uri: Uri, durationMs: Long): List<Float> {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(context, uri, null)
            var audioTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = trackFormat
                    break
                }
            }
            if (audioTrackIndex < 0 || format == null) return defaultWaveform()

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return defaultWaveform()
            if (mime == "audio/raw" || mime == "audio/x-wav") {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val bytes = stream.readBytes()
                    if (bytes.size > 44) {
                        return samplePcmBytes(bytes, 44)
                    }
                }
            }

            extractor.selectTrack(audioTrackIndex)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val totalUs = if (durationMs > 0) durationMs * 1000L
            else if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION)
            else 60_000_000L

            val rawBins = DoubleArray(TARGET_BINS)
            val info = MediaCodec.BufferInfo()
            val samplePoints = 32
            for (p in 0 until samplePoints) {
                val seekUs = (totalUs * p) / samplePoints
                extractor.seekTo(seekUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                codec.flush()
                var gotOutput = false
                var attempts = 0
                while (!gotOutput && attempts < 12) {
                    attempts++
                    val inIdx = codec.dequeueInputBuffer(5000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        val sampleSize = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                    val outIdx = codec.dequeueOutputBuffer(info, 10000)
                    if (outIdx >= 0) {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0) {
                            val rms = computeBufferRms(outBuf, info.offset, info.size)
                            val binStart = (p * TARGET_BINS) / samplePoints
                            val binEnd = (((p + 1) * TARGET_BINS) / samplePoints).coerceAtLeast(binStart + 1)
                            for (b in binStart until binEnd.coerceAtMost(TARGET_BINS)) {
                                rawBins[b] = rms
                            }
                            gotOutput = true
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                    }
                }
            }

            val peak = rawBins.maxOrNull()?.coerceAtLeast(0.02) ?: 1.0
            rawBins.map { (it / peak).toFloat().coerceIn(0.16f, 1.0f) }
        } catch (_: Exception) {
            defaultWaveform()
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun samplePcmBytes(bytes: ByteArray, offset: Int): List<Float> {
        val len = bytes.size - offset
        if (len <= 2) return defaultWaveform()
        val binBytes = (len / TARGET_BINS).coerceAtLeast(2)
        val raw = DoubleArray(TARGET_BINS)
        for (i in 0 until TARGET_BINS) {
            val start = offset + i * binBytes
            val end = (start + minOf(binBytes, 2048)).coerceAtMost(bytes.size - 1)
            var sumSq = 0.0
            var count = 0
            var p = start
            while (p + 1 < end) {
                val sample = ((bytes[p + 1].toInt() shl 8) or (bytes[p].toInt() and 0xFF)).toDouble() / 32768.0
                sumSq += sample * sample
                count++
                p += 2
            }
            raw[i] = if (count > 0) sqrt(sumSq / count) else 0.0
        }
        val peak = raw.maxOrNull()?.coerceAtLeast(0.05) ?: 1.0
        return raw.map { (it / peak).toFloat().coerceIn(0.16f, 1.0f) }
    }

    private fun computeBufferRms(buffer: ByteBuffer, offset: Int, size: Int): Double {
        buffer.position(offset)
        buffer.limit(offset + size)
        val shortBuf = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val count = shortBuf.remaining().coerceAtMost(1024)
        if (count == 0) return 0.1
        var sumSq = 0.0
        for (i in 0 until count) {
            val s = shortBuf.get().toDouble() / 32768.0
            sumSq += abs(s * s)
        }
        return sqrt(sumSq / count)
    }

    private fun guessMimeType(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".wav") -> "audio/wav"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".m4a") || lower.endsWith(".aac") -> "audio/mp4"
            lower.endsWith(".ogg") -> "audio/ogg"
            lower.endsWith(".flac") -> "audio/flac"
            else -> "audio/mpeg"
        }
    }

    fun defaultWaveform(): List<Float> = List(TARGET_BINS) { i ->
        val phase = i * 0.35
        (0.45f + 0.35f * abs(kotlin.math.sin(phase).toFloat()) + 0.15f * ((i % 4) / 3f)).coerceIn(0.2f, 0.98f)
    }
}
