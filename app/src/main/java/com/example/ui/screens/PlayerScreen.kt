package com.example.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.audio.FrequencySpectrumFrame
import com.example.model.AudioTrack
import com.example.model.FloatingReaction
import com.example.model.Participant
import com.example.model.PartyAudioSettings
import com.example.model.PlaybackState
import com.example.model.RepeatMode as SyncRepeatMode
import com.example.model.SessionRole
import com.example.model.SyncTelemetry
import com.example.ui.components.AudioSettingsDialog
import com.example.ui.components.GlassCard
import com.example.ui.components.SyncHealthBar
import com.example.ui.components.WebRtcFrequencyVisualizer
import com.example.ui.components.artworkDrawableForKey
import com.example.ui.components.formatDuration
import com.example.ui.theme.ElectricPurple
import com.example.ui.theme.GraphiteDeep
import com.example.ui.theme.NeonBlue
import com.example.ui.theme.SyncEmerald
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary
import kotlin.math.roundToInt

@Composable
fun PlayerScreen(
    role: SessionRole,
    currentTrack: AudioTrack?,
    playbackState: PlaybackState,
    positionMs: Long,
    durationMs: Long,
    localVolume: Float,
    effectiveVolume: Float,
    partyAudioSettings: PartyAudioSettings,
    isChatActive: Boolean,
    isAudioDucked: Boolean,
    telemetry: SyncTelemetry,
    participants: List<Participant>,
    driftToleranceMs: Int,
    frequencySpectrum: FrequencySpectrumFrame,
    floatingReactions: List<FloatingReaction>,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onToggleChatDucking: (Boolean) -> Unit,
    onDuckingReductionChange: (Int) -> Unit,
    onSendReaction: (String) -> Unit
) {
    val isHost = role == SessionRole.HOST
    val safeDuration = durationMs.coerceAtLeast(1L)
    var isDraggingSeek by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var showAudioSettingsDialog by remember { mutableStateOf(false) }
    val displayFraction = if (isDraggingSeek) dragFraction
    else (positionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)

    if (showAudioSettingsDialog) {
        AudioSettingsDialog(
            role = role,
            audioSettings = partyAudioSettings,
            baseVolume = localVolume,
            effectiveVolume = effectiveVolume,
            isChatActive = isChatActive,
            isAudioDucked = isAudioDucked,
            onToggleChatDucking = onToggleChatDucking,
            onDuckingReductionChange = onDuckingReductionChange,
            onDismiss = { showAudioSettingsDialog = false }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.widthIn(max = 560.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Album Art with Electric Purple/Blue Ambient Glow
                Box(
                    modifier = Modifier
                        .size(236.dp)
                        .drawBehind {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        ElectricPurple.copy(alpha = if (playbackState.isPlaying) 0.45f else 0.22f),
                                        NeonBlue.copy(alpha = 0.14f),
                                        Color.Transparent
                                    )
                                ),
                                radius = size.minDimension * 0.72f
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        modifier = Modifier.size(204.dp),
                        shape = RoundedCornerShape(28.dp),
                        border = BorderStroke(
                            width = 1.5.dp,
                            brush = Brush.linearGradient(listOf(ElectricPurple, NeonBlue))
                        )
                    ) {
                        Image(
                            painter = painterResource(
                                id = artworkDrawableForKey(currentTrack?.artworkKey ?: "pulse")
                            ),
                            contentDescription = stringResource(R.string.cd_album_art),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Song Title & Artist
                Text(
                    text = currentTrack?.title ?: "No Track Selected",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = currentTrack?.artist ?: "Import an MP3/WAV or select a track",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Real-Time WebRTC Canvas Frequency Visualizer
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 20.dp,
                    borderColor = if (playbackState.isPlaying) NeonBlue.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.14f)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        WebRtcFrequencyVisualizer(
                            spectrum = frequencySpectrum,
                            waveformBins = currentTrack?.waveformBins.orEmpty(),
                            progressFraction = displayFraction,
                            isPlaying = playbackState.isPlaying,
                            isWebRtcActive = telemetry.isWebRtcP2pActive || telemetry.isAudioFrameStreaming
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Subtle Real-Time Drift Variance Sync Health Bar (Green / Yellow / Red)
                SyncHealthBar(
                    role = role,
                    telemetry = telemetry,
                    participants = participants,
                    driftToleranceMs = driftToleranceMs,
                    isPlaying = playbackState.isPlaying
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Seek Bar + Timestamps
                Slider(
                    value = displayFraction,
                    onValueChange = { frac ->
                        if (isHost) {
                            isDraggingSeek = true
                            dragFraction = frac
                        }
                    },
                    onValueChangeFinished = {
                        if (isHost) {
                            onSeek((dragFraction * safeDuration).toLong())
                            isDraggingSeek = false
                        }
                    },
                    enabled = isHost,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("playback_seek_slider"),
                    colors = SliderDefaults.colors(
                        thumbColor = NeonBlue,
                        activeTrackColor = ElectricPurple,
                        inactiveTrackColor = Color.White.copy(alpha = 0.14f),
                        disabledThumbColor = NeonBlue.copy(alpha = 0.7f),
                        disabledActiveTrackColor = ElectricPurple.copy(alpha = 0.7f)
                    )
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val shownMs = if (isDraggingSeek) (dragFraction * safeDuration).toLong() else positionMs
                    Text(
                        text = formatDuration(shownMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Text(
                        text = formatDuration(durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Transport Controls (Host) or Synchronized Banner (Listener)
                if (isHost) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onToggleShuffle,
                            modifier = Modifier.testTag("shuffle_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Shuffle,
                                contentDescription = stringResource(R.string.cd_shuffle),
                                tint = if (playbackState.shuffle) NeonBlue else TextMuted
                            )
                        }
                        IconButton(
                            onClick = onPrevious,
                            modifier = Modifier
                                .size(52.dp)
                                .testTag("previous_track_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SkipPrevious,
                                contentDescription = stringResource(R.string.cd_previous_track),
                                modifier = Modifier.size(34.dp)
                            )
                        }
                        Surface(
                            modifier = Modifier
                                .size(70.dp)
                                .clip(CircleShape)
                                .testTag("play_pause_button")
                                .clickable(onClick = onTogglePlayPause),
                            shape = CircleShape,
                            color = Color.Transparent
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Brush.linearGradient(listOf(ElectricPurple, NeonBlue))),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (playbackState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = stringResource(R.string.cd_play_pause),
                                    tint = Color.White,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }
                        IconButton(
                            onClick = onNext,
                            modifier = Modifier
                                .size(52.dp)
                                .testTag("next_track_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SkipNext,
                                contentDescription = stringResource(R.string.cd_next_track),
                                modifier = Modifier.size(34.dp)
                            )
                        }
                        IconButton(
                            onClick = onCycleRepeat,
                            modifier = Modifier.testTag("repeat_button")
                        ) {
                            Icon(
                                imageVector = if (playbackState.repeatMode == SyncRepeatMode.ONE) {
                                    Icons.Rounded.RepeatOne
                                } else {
                                    Icons.Rounded.Repeat
                                },
                                contentDescription = stringResource(R.string.cd_repeat),
                                tint = if (playbackState.repeatMode != SyncRepeatMode.OFF) ElectricPurple else TextMuted
                            )
                        }
                    }
                } else {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        borderColor = NeonBlue.copy(alpha = 0.4f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Sync,
                                contentDescription = null,
                                tint = NeonBlue,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (playbackState.isPlaying) {
                                    "Playback controlled by Host • Locked to Beat"
                                } else {
                                    "Paused by Host • Ready on Standby"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Individual Device Volume Slider + Audio Settings Trigger
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
                                    contentDescription = "Local device volume",
                                    tint = NeonBlue,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "My Volume",
                                    style = MaterialTheme.typography.labelLarge
                                )
                                if (isAudioDucked) {
                                    Surface(
                                        modifier = Modifier.testTag("volume_ducked_indicator"),
                                        shape = RoundedCornerShape(50),
                                        color = NeonBlue.copy(alpha = 0.16f),
                                        border = BorderStroke(0.75.dp, NeonBlue.copy(alpha = 0.45f))
                                    ) {
                                        Text(
                                            text = "Chat Ducked • ${(effectiveVolume * 100).roundToInt()}%",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = NeonBlue,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                        )
                                    }
                                } else if (partyAudioSettings.chatDuckingEnabled) {
                                    Text(
                                        text = "• Ducking Ready",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextMuted
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "${(localVolume * 100).roundToInt()}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                                IconButton(
                                    onClick = { showAudioSettingsDialog = true },
                                    modifier = Modifier.testTag("player_audio_settings_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.GraphicEq,
                                        contentDescription = stringResource(R.string.cd_audio_settings),
                                        tint = if (partyAudioSettings.chatDuckingEnabled) NeonBlue else TextSecondary
                                    )
                                }
                            }
                        }

                        Slider(
                            value = localVolume,
                            onValueChange = onVolumeChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 8.dp)
                                .testTag("local_volume_slider"),
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = NeonBlue,
                                inactiveTrackColor = Color.White.copy(alpha = 0.14f)
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Real-Time Synchronization Telemetry + Emoji Reaction Bar
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Speed,
                                    contentDescription = null,
                                    tint = SyncEmerald,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = if (telemetry.isWebRtcP2pActive) "WEBRTC P2P" else "SYNC TELEMETRY",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (telemetry.isWebRtcP2pActive) NeonBlue else SyncEmerald
                                )
                            }
                            Text(
                                text = if (isHost) {
                                    if (telemetry.isAudioFrameStreaming) {
                                        "Master Clock • ${telemetry.framesStreamed} PCM frames streamed"
                                    } else if (telemetry.isWebRtcP2pActive) {
                                        "Master Clock • P2P UDP Active • ${telemetry.p2pTransferredBytes / 1024L}KB"
                                    } else {
                                        "Master Clock • 0ms Offset"
                                    }
                                } else {
                                    val bufInfo = if (telemetry.isAudioFrameStreaming) {
                                        " • Buf ${telemetry.bufferFillPercent}% (${telemetry.bufferedFrames}f, Jit ${telemetry.jitterMs}ms)"
                                    } else if (telemetry.p2pTransferredBytes > 0L) {
                                        " • ${telemetry.p2pTransferredBytes / 1024L}KB P2P"
                                    } else ""
                                    "Offset ${telemetry.offsetMs}ms • RTT ${telemetry.rttMs}ms • Drift ${telemetry.driftMs}ms$bufInfo"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Live Reactions",
                                style = MaterialTheme.typography.labelLarge,
                                color = TextSecondary
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("🔥", "❤️", "😂", "🎉").forEach { emoji ->
                                    Surface(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(CircleShape)
                                            .testTag("reaction_button_$emoji")
                                            .clickable { onSendReaction(emoji) },
                                        shape = CircleShape,
                                        color = GraphiteDeep,
                                        border = BorderStroke(1.dp, ElectricPurple.copy(alpha = 0.35f))
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(text = emoji, fontSize = 20.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Floating Emoji Reactions Overlay
        FloatingReactionsOverlay(
            reactions = floatingReactions,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun FloatingReactionsOverlay(
    reactions: List<FloatingReaction>,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()

        reactions.forEach { reaction ->
            val progress = remember(reaction.id) { Animatable(0f) }
            LaunchedEffect(reaction.id) {
                progress.animateTo(1f, animationSpec = tween(2400, easing = LinearEasing))
            }
            val p = progress.value
            val xPx = (reaction.xFraction * widthPx).roundToInt()
            val yPx = (heightPx * (0.82f - p * 0.62f)).roundToInt()
            val alpha = (1f - (p - 0.55f).coerceAtLeast(0f) / 0.45f).coerceIn(0f, 1f)

            Column(
                modifier = Modifier
                    .offset { IntOffset(xPx - 36, yPx) }
                    .alpha(alpha),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = reaction.emoji, fontSize = 30.sp)
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = GraphiteDeep.copy(alpha = 0.85f)
                ) {
                    Text(
                        text = reaction.senderName,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}
