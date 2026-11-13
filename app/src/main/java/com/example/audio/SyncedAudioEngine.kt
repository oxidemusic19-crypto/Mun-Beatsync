package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import com.example.model.AudioTrack
import com.example.model.PlaybackState
import com.example.model.SessionRole
import com.example.model.SyncHealth
import com.example.model.SyncTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sqrt

class SyncedAudioEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val estimatedHostNowMs: () -> Long,
    private val onHostTrackCompleted: () -> Unit,
    private val onClientTelemetrySample: (driftMs: Long, health: SyncHealth) -> Unit
) {
    private var mediaPlayer: MediaPlayer? = null
    private var preparedTrackId: String? = null
    private var isPrepared = false
    private var isPreparing = false

    private var currentRole = SessionRole.HOST
    private var currentTrack: AudioTrack? = null
    private var targetPlaybackState = PlaybackState()
    private var localVolume = 0.85f
    private var userCalibrationOffsetMs = 0
    private var driftToleranceMs = 22
    private var lastHardSeekTimeMs = 0L

    private var syncMonitorJob: Job? = null
    private var scheduledStartJob: Job? = null
    private val p2pCachedTrackPaths = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val recentDriftSamples = ArrayDeque<Long>()
    @Volatile private var externalP2pAudioActive: Boolean = false

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _telemetry = MutableStateFlow(SyncTelemetry())
    val telemetry: StateFlow<SyncTelemetry> = _telemetry.asStateFlow()

    fun setLocalVolume(volume: Float) {
        localVolume = volume.coerceIn(0f, 1f)
        runCatching { mediaPlayer?.setVolume(localVolume, localVolume) }
    }

    fun updateCalibration(calibrationOffsetMs: Int, driftSensitivityMs: Int) {
        userCalibrationOffsetMs = calibrationOffsetMs
        driftToleranceMs = driftSensitivityMs
    }

    fun updateClockStats(offsetMs: Long, rttMs: Long, sampleCount: Int) {
        val current = _telemetry.value
        _telemetry.value = current.copy(
            offsetMs = offsetMs,
            rttMs = rttMs,
            sampleCount = sampleCount
        )
    }

    fun updateWebRtcStatus(isP2pActive: Boolean, iceState: String, p2pBytes: Long) {
        _telemetry.value = _telemetry.value.copy(
            isWebRtcP2pActive = isP2pActive,
            iceState = iceState,
            p2pTransferredBytes = p2pBytes
        )
    }

    fun updateAudioBufferStats(stats: AudioBufferStats, isListenerRenderingP2p: Boolean, listenerPosMs: Long) {
        externalP2pAudioActive = isListenerRenderingP2p
        if (isListenerRenderingP2p) {
            // Mute MediaPlayer so AudioBufferManager's AudioTrack renders cleanly without echo
            runCatching { mediaPlayer?.setVolume(0f, 0f) }
            _positionMs.value = listenerPosMs
            val health = if (abs(stats.renderDriftMs) <= driftToleranceMs) SyncHealth.LOCKED else SyncHealth.CORRECTING
            val varianceMs = recordDriftVarianceMs(stats.renderDriftMs)
            _telemetry.value = _telemetry.value.copy(
                driftMs = stats.renderDriftMs,
                driftVarianceMs = varianceMs,
                syncHealth = health,
                bufferFillPercent = stats.fillPercent,
                bufferedFrames = stats.bufferedFrames,
                framesStreamed = stats.framesStreamed,
                isAudioFrameStreaming = stats.isP2pAudioStreaming,
                jitterMs = stats.estimatedJitterMs
            )
            onClientTelemetrySample(stats.renderDriftMs, health)
        } else {
            runCatching { mediaPlayer?.setVolume(localVolume, localVolume) }
            _telemetry.value = _telemetry.value.copy(
                bufferFillPercent = stats.fillPercent,
                bufferedFrames = stats.bufferedFrames,
                framesStreamed = stats.framesStreamed,
                isAudioFrameStreaming = stats.isP2pAudioStreaming,
                jitterMs = stats.estimatedJitterMs
            )
        }
    }

    fun registerP2pCachedTrack(trackId: String, localFilePath: String) {
        p2pCachedTrackPaths[trackId] = localFilePath
    }

    fun clearP2pCache() {
        p2pCachedTrackPaths.clear()
    }

    fun applyState(
        role: SessionRole,
        track: AudioTrack?,
        state: PlaybackState
    ) {
        currentRole = role
        targetPlaybackState = state

        if (track == null) {
            releasePlayer()
            currentTrack = null
            _positionMs.value = 0L
            _durationMs.value = 0L
            return
        }

        val trackChanged = currentTrack?.id != track.id || preparedTrackId != track.id
        currentTrack = track
        _durationMs.value = track.durationMs

        if (trackChanged) {
            prepareTrackAndApply(track, state)
        } else if (isPrepared) {
            applySynchronizedTransport(state)
        }

        ensureMonitorRunning()
    }

    private fun prepareTrackAndApply(track: AudioTrack, state: PlaybackState) {
        scheduledStartJob?.cancel()
        releasePlayer()
        isPrepared = false
        isPreparing = true
        preparedTrackId = track.id

        _telemetry.value = _telemetry.value.copy(
            syncHealth = if (currentRole == SessionRole.HOST) SyncHealth.HOST_CLOCK else SyncHealth.BUFFERING,
            bufferReady = false
        )

        val player = MediaPlayer()
        mediaPlayer = player
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        player.setVolume(
            if (externalP2pAudioActive) 0f else localVolume,
            if (externalP2pAudioActive) 0f else localVolume
        )

        player.setOnPreparedListener { mp ->
            if (mediaPlayer !== mp || preparedTrackId != track.id) return@setOnPreparedListener
            isPreparing = false
            isPrepared = true
            val realDur = mp.duration.toLong()
            if (realDur > 0) _durationMs.value = realDur
            _telemetry.value = _telemetry.value.copy(
                bufferReady = true,
                syncHealth = if (currentRole == SessionRole.HOST) SyncHealth.HOST_CLOCK else SyncHealth.LOCKED
            )
            applySynchronizedTransport(targetPlaybackState)
        }

        player.setOnCompletionListener {
            if (currentRole == SessionRole.HOST) {
                onHostTrackCompleted()
            }
        }

        player.setOnErrorListener { _, _, _ ->
            isPreparing = false
            isPrepared = false
            _telemetry.value = _telemetry.value.copy(
                syncHealth = SyncHealth.RECONNECTING,
                bufferReady = false
            )
            true
        }

        scope.launch(Dispatchers.IO) {
            try {
                val p2pCached = p2pCachedTrackPaths[track.id]
                when {
                    !p2pCached.isNullOrBlank() && java.io.File(p2pCached).exists() -> player.setDataSource(p2pCached)
                    !track.localPath.isNullOrBlank() -> player.setDataSource(track.localPath)
                    !track.contentUriString.isNullOrBlank() -> player.setDataSource(context, Uri.parse(track.contentUriString))
                    !track.streamUrl.isNullOrBlank() -> player.setDataSource(track.streamUrl)
                    else -> return@launch
                }
                player.prepareAsync()
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    isPreparing = false
                    isPrepared = false
                }
            }
        }
    }

    private fun applySynchronizedTransport(state: PlaybackState) {
        val player = mediaPlayer ?: return
        if (!isPrepared) return
        scheduledStartJob?.cancel()

        if (!state.isPlaying) {
            runCatching {
                if (player.isPlaying) player.pause()
                setSpeedSafe(player, 1.0f)
                seekSafe(player, state.anchorPositionMs.coerceAtLeast(0L))
            }
            _positionMs.value = state.anchorPositionMs
            clearDriftVarianceSamples()
            _telemetry.value = _telemetry.value.copy(
                driftMs = 0L,
                driftVarianceMs = 0L,
                playbackSpeed = 1.0f,
                syncHealth = if (currentRole == SessionRole.HOST) SyncHealth.HOST_CLOCK else SyncHealth.LOCKED
            )
            return
        }

        val nowHostMs = if (currentRole == SessionRole.HOST) {
            SystemClock.elapsedRealtime()
        } else {
            estimatedHostNowMs()
        }

        val delayUntilAnchor = state.anchorHostTimeMs - nowHostMs
        if (delayUntilAnchor > 15L) {
            // Scheduled synchronized launch! Pre-seek cleanly before anchorHostTimeMs arrives
            runCatching {
                if (player.isPlaying) player.pause()
                setSpeedSafe(player, 1.0f)
                val targetSeek = (state.anchorPositionMs + userCalibrationOffsetMs).coerceAtLeast(0L)
                seekSafe(player, targetSeek)
            }
            scheduledStartJob = scope.launch(Dispatchers.Main) {
                val waitMs = (state.anchorHostTimeMs - currentClockForRole()).coerceIn(0L, 2000L)
                if (waitMs > 0) delay(waitMs)
                if (isActive && mediaPlayer === player && isPrepared && targetPlaybackState.isPlaying) {
                    runCatching {
                        if (!player.isPlaying) player.start()
                    }
                }
            }
        } else {
            val expectedPos = computeExpectedPositionMs(state)
            runCatching {
                val currentPos = player.currentPosition.toLong()
                if (abs(currentPos - expectedPos) > 80L || !player.isPlaying) {
                    seekSafe(player, expectedPos)
                    lastHardSeekTimeMs = SystemClock.elapsedRealtime()
                }
                setSpeedSafe(player, 1.0f)
                if (!player.isPlaying) player.start()
            }
        }
    }

    private fun ensureMonitorRunning() {
        if (syncMonitorJob?.isActive == true) return
        syncMonitorJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                val player = mediaPlayer
                val state = targetPlaybackState
                if (externalP2pAudioActive) {
                    delay(180L)
                    continue
                }
                if (player != null && isPrepared) {
                    val actualPos = runCatching { player.currentPosition.toLong() }.getOrDefault(0L)
                    _positionMs.value = actualPos

                    if (state.isPlaying && currentRole == SessionRole.LISTENER) {
                        val nowHost = currentClockForRole()
                        if (nowHost >= state.anchorHostTimeMs + 150L) {
                            val expectedPos = computeExpectedPositionMs(state)
                            val dur = _durationMs.value.takeIf { it > 0 } ?: Long.MAX_VALUE
                            if (expectedPos < dur - 250L) {
                                val driftMs = actualPos - expectedPos
                                val absDrift = abs(driftMs)
                                val nowElapsed = SystemClock.elapsedRealtime()

                                val (newSpeed, health) = when {
                                    absDrift <= driftToleranceMs -> {
                                        1.0f to SyncHealth.LOCKED
                                    }
                                    absDrift <= 240L -> {
                                        // Smooth phase-locked playback rate correction
                                        val correction = if (driftMs > 0) {
                                            (1.0f - (absDrift / 1800f)).coerceAtLeast(0.94f)
                                        } else {
                                            (1.0f + (absDrift / 1800f)).coerceAtMost(1.06f)
                                        }
                                        correction to SyncHealth.CORRECTING
                                    }
                                    nowElapsed - lastHardSeekTimeMs > 1200L -> {
                                        runCatching { seekSafe(player, expectedPos) }
                                        lastHardSeekTimeMs = nowElapsed
                                        1.0f to SyncHealth.CORRECTING
                                    }
                                    else -> 1.0f to SyncHealth.CORRECTING
                                }

                                setSpeedSafe(player, newSpeed)
                                if (!player.isPlaying) {
                                    runCatching { player.start() }
                                }
                                val varianceMs = recordDriftVarianceMs(driftMs)
                                _telemetry.value = _telemetry.value.copy(
                                    driftMs = driftMs,
                                    driftVarianceMs = varianceMs,
                                    playbackSpeed = newSpeed,
                                    syncHealth = health
                                )
                                onClientTelemetrySample(driftMs, health)
                            }
                        }
                    } else if (currentRole == SessionRole.HOST) {
                        clearDriftVarianceSamples()
                        _telemetry.value = _telemetry.value.copy(
                            driftMs = 0L,
                            driftVarianceMs = 0L,
                            playbackSpeed = 1.0f,
                            syncHealth = SyncHealth.HOST_CLOCK
                        )
                    }
                }
                delay(180L)
            }
        }
    }

    private fun currentClockForRole(): Long {
        return if (currentRole == SessionRole.HOST) SystemClock.elapsedRealtime() else estimatedHostNowMs()
    }

    private fun computeExpectedPositionMs(state: PlaybackState): Long {
        if (!state.isPlaying) return state.anchorPositionMs.coerceAtLeast(0L)
        val elapsedSinceAnchor = (currentClockForRole() - state.anchorHostTimeMs).coerceAtLeast(0L)
        val cal = if (currentRole == SessionRole.LISTENER) userCalibrationOffsetMs else 0
        val dur = _durationMs.value.takeIf { it > 0 } ?: Long.MAX_VALUE
        return (state.anchorPositionMs + elapsedSinceAnchor + cal).coerceIn(0L, dur)
    }

    private fun seekSafe(player: MediaPlayer, positionMs: Long) {
        val clamped = positionMs.coerceAtLeast(0L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            player.seekTo(clamped, MediaPlayer.SEEK_CLOSEST)
        } else {
            player.seekTo(clamped.toInt())
        }
    }

    private fun setSpeedSafe(player: MediaPlayer, speed: Float) {
        runCatching {
            val currentParams = player.playbackParams
            if (abs(currentParams.speed - speed) > 0.004f) {
                player.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1.0f)
            }
        }
    }

    fun currentPositionSnapshotMs(): Long {
        val player = mediaPlayer
        return if (player != null && isPrepared) {
            runCatching { player.currentPosition.toLong() }.getOrDefault(_positionMs.value)
        } else {
            _positionMs.value
        }
    }

    fun markReconnecting() {
        _telemetry.value = _telemetry.value.copy(syncHealth = SyncHealth.RECONNECTING)
    }

    private fun recordDriftVarianceMs(driftMs: Long): Long {
        synchronized(recentDriftSamples) {
            recentDriftSamples.addLast(driftMs)
            while (recentDriftSamples.size > 12) {
                recentDriftSamples.removeFirst()
            }
            if (recentDriftSamples.size < 2) return abs(driftMs)
            val mean = recentDriftSamples.average()
            var sumSq = 0.0
            for (sample in recentDriftSamples) {
                val diff = sample - mean
                sumSq += diff * diff
            }
            return sqrt(sumSq / recentDriftSamples.size).roundToLong()
        }
    }

    private fun clearDriftVarianceSamples() {
        synchronized(recentDriftSamples) {
            recentDriftSamples.clear()
        }
    }

    fun releasePlayer() {
        scheduledStartJob?.cancel()
        isPrepared = false
        isPreparing = false
        preparedTrackId = null
        clearDriftVarianceSamples()
        runCatching {
            mediaPlayer?.stop()
        }
        runCatching {
            mediaPlayer?.release()
        }
        mediaPlayer = null
    }

    fun shutdown() {
        syncMonitorJob?.cancel()
        releasePlayer()
    }
}
