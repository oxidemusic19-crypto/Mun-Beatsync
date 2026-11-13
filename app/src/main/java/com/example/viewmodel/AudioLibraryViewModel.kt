package com.example.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioBufferManager
import com.example.audio.AudioFramePacket
import com.example.audio.AudioWaveformExtractor
import com.example.audio.PcmAudioTranscoder
import com.example.model.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.UUID

enum class PcmConversionStatus {
    IDLE,
    CONVERTING,
    READY,
    ERROR
}

data class LocalAudioLibraryItem(
    val track: AudioTrack,
    val status: PcmConversionStatus = PcmConversionStatus.IDLE,
    val conversionProgress: Float = 0f,
    val pcmStreamPath: String? = null,
    val pcmBytesGenerated: Long = 0L,
    val pcmFrameCount: Int = 0,
    val sourceSampleRate: Int = PcmAudioTranscoder.TARGET_SAMPLE_RATE,
    val sourceChannels: Int = PcmAudioTranscoder.TARGET_CHANNELS,
    val errorMessage: String? = null
)

data class AudioLibraryUiState(
    val items: List<LocalAudioLibraryItem> = emptyList(),
    val isImporting: Boolean = false,
    val activeConversionTrackId: String? = null,
    val statusMessage: String? = null
)

class AudioLibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val pcmCacheDir = File(appContext.cacheDir, "webrtc_pcm_streams").apply { mkdirs() }
    private val artworkKeys = listOf("pulse", "horizon", "voltage")

    private val _uiState = MutableStateFlow(AudioLibraryUiState())
    val uiState: StateFlow<AudioLibraryUiState> = _uiState.asStateFlow()

    /**
     * Imports a user-picked SAF document Uri (zero broad storage permissions required),
     * extracts metadata & waveform, and converts the audio into a 44.1 kHz 16-bit stereo
     * raw PCM data stream ready for WebRTC DataChannel transmission.
     */
    fun importPickedAudio(
        uri: Uri,
        autoConvertToPcm: Boolean = true,
        onTrackReady: (AudioTrack) -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isImporting = true, statusMessage = "Reading audio file metadata...") }
            runCatching {
                appContext.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }

            try {
                val meta = AudioWaveformExtractor.extractMetadataAndWaveform(appContext, uri)
                val trackId = "lib_${UUID.randomUUID().toString().take(8)}"
                val track = AudioTrack(
                    id = trackId,
                    title = meta.title,
                    artist = meta.artist,
                    durationMs = meta.durationMs,
                    artworkKey = artworkKeys[_uiState.value.items.size % artworkKeys.size],
                    mimeType = meta.mimeType,
                    fileSizeBytes = meta.fileSizeBytes,
                    contentUriString = uri.toString(),
                    waveformBins = meta.waveformBins
                )

                val item = LocalAudioLibraryItem(
                    track = track,
                    status = if (autoConvertToPcm) PcmConversionStatus.CONVERTING else PcmConversionStatus.IDLE,
                    conversionProgress = 0f
                )
                _uiState.update { state ->
                    state.copy(
                        items = state.items + item,
                        isImporting = false,
                        activeConversionTrackId = if (autoConvertToPcm) trackId else state.activeConversionTrackId,
                        statusMessage = if (autoConvertToPcm) "Converting \"${track.title}\" to raw PCM stream..." else null
                    )
                }

                if (autoConvertToPcm) {
                    transcodeItemToPcm(track, onTrackReady)
                } else {
                    onTrackReady(track)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        statusMessage = "Import failed: ${e.localizedMessage ?: "Unsupported audio file"}"
                    )
                }
            }
        }
    }

    /**
     * Imports a local audio File directly and converts it into a raw PCM data stream.
     */
    fun importPickedFile(
        file: File,
        title: String? = null,
        artist: String = "Local Library",
        autoConvertToPcm: Boolean = true,
        onTrackReady: (AudioTrack) -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!file.exists() || !file.canRead()) {
                _uiState.update { it.copy(statusMessage = "Selected audio file is unreadable.") }
                return@launch
            }
            _uiState.update { it.copy(isImporting = true) }
            val trackId = "lib_${UUID.randomUUID().toString().take(8)}"
            val waveform = AudioWaveformExtractor.extractWaveformFromWavFile(file)
            val track = AudioTrack(
                id = trackId,
                title = title?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension,
                artist = artist,
                durationMs = estimateWavDurationMs(file),
                artworkKey = artworkKeys[_uiState.value.items.size % artworkKeys.size],
                mimeType = if (file.extension.equals("wav", ignoreCase = true)) "audio/wav" else "audio/mpeg",
                fileSizeBytes = file.length(),
                localPath = file.absolutePath,
                waveformBins = waveform
            )

            _uiState.update { state ->
                state.copy(
                    items = state.items + LocalAudioLibraryItem(
                        track = track,
                        status = if (autoConvertToPcm) PcmConversionStatus.CONVERTING else PcmConversionStatus.IDLE
                    ),
                    isImporting = false,
                    activeConversionTrackId = if (autoConvertToPcm) trackId else state.activeConversionTrackId
                )
            }

            if (autoConvertToPcm) {
                transcodeItemToPcm(track, onTrackReady)
            } else {
                onTrackReady(track)
            }
        }
    }

    fun convertTrackToPcmStream(
        trackId: String,
        onTrackReady: (AudioTrack) -> Unit = {}
    ) {
        val target = _uiState.value.items.firstOrNull { it.track.id == trackId } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            transcodeItemToPcm(target.track, onTrackReady)
        }
    }

    private suspend fun transcodeItemToPcm(
        track: AudioTrack,
        onTrackReady: (AudioTrack) -> Unit
    ) {
        updateItem(track.id) {
            it.copy(
                status = PcmConversionStatus.CONVERTING,
                conversionProgress = 0.02f,
                errorMessage = null
            )
        }
        _uiState.update {
            it.copy(
                activeConversionTrackId = track.id,
                statusMessage = "Transcoding \"${track.title}\" to 44.1 kHz stereo PCM..."
            )
        }

        val outFile = File(pcmCacheDir, "${track.id}.pcm")
        try {
            val result = when {
                !track.contentUriString.isNullOrBlank() -> {
                    PcmAudioTranscoder.transcodeUriToPcmFile(
                        context = appContext,
                        uri = Uri.parse(track.contentUriString),
                        outputFile = outFile,
                        expectedDurationMs = track.durationMs,
                        onProgress = { pct ->
                            updateItem(track.id) { it.copy(conversionProgress = pct) }
                        }
                    )
                }
                !track.localPath.isNullOrBlank() -> {
                    PcmAudioTranscoder.transcodeWavFileToPcmFile(
                        sourceWav = File(track.localPath),
                        outputFile = outFile,
                        onProgress = { pct ->
                            updateItem(track.id) { it.copy(conversionProgress = pct) }
                        }
                    )
                }
                else -> throw IllegalStateException("Track has no local audio source")
            }

            val updatedTrack = track.copy(pcmStreamPath = result.pcmFile.absolutePath)
            updateItem(track.id) {
                it.copy(
                    track = updatedTrack,
                    status = PcmConversionStatus.READY,
                    conversionProgress = 1f,
                    pcmStreamPath = result.pcmFile.absolutePath,
                    pcmBytesGenerated = result.totalPcmBytes,
                    pcmFrameCount = result.totalFrames,
                    sourceSampleRate = result.sourceSampleRate,
                    sourceChannels = result.sourceChannels,
                    errorMessage = null
                )
            }
            _uiState.update {
                it.copy(
                    activeConversionTrackId = null,
                    statusMessage = "\"${track.title}\" ready (${result.totalFrames} WebRTC PCM frames)"
                )
            }
            onTrackReady(updatedTrack)
        } catch (e: Exception) {
            runCatching { outFile.delete() }
            val err = e.localizedMessage ?: "PCM conversion failed"
            updateItem(track.id) {
                it.copy(
                    status = PcmConversionStatus.ERROR,
                    conversionProgress = 0f,
                    errorMessage = err
                )
            }
            _uiState.update {
                it.copy(
                    activeConversionTrackId = null,
                    statusMessage = "PCM conversion error: $err"
                )
            }
            // Still deliver metadata-extracted track so host can fall back to standard MediaCodec playback
            onTrackReady(track)
        }
    }

    /**
     * Streams raw PCM chunks from a converted library item wrapped as 40 ms SBAF binary packets
     * ready for transmission over the WebRTC DataChannel.
     */
    fun streamPcmFramesForWebRtc(
        trackId: String,
        playbackEpoch: Int,
        startPosMs: Long = 0L,
        anchorHostTimeMs: Long = 0L
    ): Flow<ByteArray> = flow {
        val item = _uiState.value.items.firstOrNull { it.track.id == trackId } ?: return@flow
        val pcmPath = item.pcmStreamPath ?: return@flow
        val file = File(pcmPath)
        if (!file.exists()) return@flow

        val trackHash = trackId.hashCode()
        val bytesPerMs = (AudioBufferManager.TARGET_SAMPLE_RATE *
            AudioBufferManager.TARGET_CHANNELS *
            AudioBufferManager.BYTES_PER_SAMPLE) / 1000.0
        val startOffset = ((startPosMs.coerceAtLeast(0L) * bytesPerMs).toLong() and -4L)

        RandomAccessFile(file, "r").use { raf ->
            if (startOffset >= raf.length()) return@use
            raf.seek(startOffset)
            val buf = ByteArray(AudioBufferManager.PCM_FRAME_BYTES)
            var seq = 0L
            var framePosMs = startPosMs.coerceAtLeast(0L)

            while (true) {
                val read = raf.read(buf)
                if (read <= 0) break
                val payload = if (read == buf.size) buf.copyOf() else buf.copyOf(read)
                val targetHostMs = anchorHostTimeMs + (framePosMs - startPosMs.coerceAtLeast(0L))
                emit(
                    AudioBufferManager.encodeFrame(
                        AudioFramePacket(
                            trackHash = trackHash,
                            playbackEpoch = playbackEpoch,
                            sequence = seq++,
                            framePosMs = framePosMs,
                            targetHostTimeMs = targetHostMs,
                            sampleRate = AudioBufferManager.TARGET_SAMPLE_RATE,
                            channels = AudioBufferManager.TARGET_CHANNELS,
                            pcmData = payload
                        )
                    )
                )
                framePosMs += AudioBufferManager.FRAME_DURATION_MS
            }
        }
    }.flowOn(Dispatchers.IO)

    fun openRawPcmStream(trackId: String): InputStream? {
        val path = _uiState.value.items.firstOrNull { it.track.id == trackId }?.pcmStreamPath ?: return null
        val file = File(path)
        return if (file.exists() && file.canRead()) FileInputStream(file) else null
    }

    fun removeLibraryItem(trackId: String) {
        val existing = _uiState.value.items.firstOrNull { it.track.id == trackId }
        existing?.pcmStreamPath?.let { runCatching { File(it).delete() } }
        _uiState.update { state ->
            state.copy(items = state.items.filterNot { it.track.id == trackId })
        }
    }

    fun clearStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    private fun updateItem(trackId: String, transform: (LocalAudioLibraryItem) -> LocalAudioLibraryItem) {
        _uiState.update { state ->
            state.copy(
                items = state.items.map { item ->
                    if (item.track.id == trackId) transform(item) else item
                }
            )
        }
    }

    private fun estimateWavDurationMs(file: File): Long {
        val dataBytes = (file.length() - 44L).coerceAtLeast(0L)
        val bytesPerSec = PcmAudioTranscoder.TARGET_SAMPLE_RATE * PcmAudioTranscoder.TARGET_CHANNELS * 2
        return ((dataBytes * 1000L) / bytesPerSec).coerceAtLeast(1000L)
    }

    override fun onCleared() {
        runCatching {
            pcmCacheDir.listFiles()?.forEach { it.delete() }
        }
        super.onCleared()
    }
}
