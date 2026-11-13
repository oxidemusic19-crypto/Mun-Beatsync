package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.model.DeviceStatus
import com.example.model.Participant
import com.example.model.SessionRole
import com.example.model.SyncTelemetry
import com.example.model.TransportMode
import com.example.ui.theme.ElectricPurple
import com.example.ui.theme.GraphiteDeep
import com.example.ui.theme.NeonBlue
import com.example.ui.theme.SyncAmber
import com.example.ui.theme.SyncCoral
import com.example.ui.theme.SyncEmerald
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary
import kotlin.math.abs

enum class ConnectionQualityTier {
    EXCELLENT,
    GOOD,
    FAIR,
    POOR,
    RECONNECTING,
    OFFLINE
}

data class PeerConnectionAssessment(
    val tier: ConnectionQualityTier,
    val signalBars: Int,
    val qualityLabel: String,
    val transportLabel: String,
    val iceSummary: String,
    val latencyText: String,
    val driftText: String,
    val isWebRtcDirect: Boolean
)

fun assessParticipantConnection(participant: Participant): PeerConnectionAssessment {
    if (participant.isHost) {
        val activeP2p = participant.status == DeviceStatus.CONNECTED
        return PeerConnectionAssessment(
            tier = if (activeP2p) ConnectionQualityTier.EXCELLENT else ConnectionQualityTier.OFFLINE,
            signalBars = if (activeP2p) 4 else 0,
            qualityLabel = if (activeP2p) "Host Clock" else "Offline",
            transportLabel = "LAN Host",
            iceSummary = "Master Clock",
            latencyText = "0ms RTT",
            driftText = "0ms drift",
            isWebRtcDirect = false
        )
    }

    val normalizedIce = participant.iceState.uppercase()
    val isWebRtcDirect = participant.transportMode == TransportMode.WEBRTC_P2P ||
        normalizedIce == "CONNECTED" ||
        normalizedIce == "COMPLETED"

    if (participant.status == DeviceStatus.DISCONNECTED || normalizedIce == "FAILED" || normalizedIce == "CLOSED") {
        return PeerConnectionAssessment(
            tier = ConnectionQualityTier.OFFLINE,
            signalBars = 0,
            qualityLabel = "Disconnected",
            transportLabel = if (isWebRtcDirect) "WebRTC P2P" else "WS LAN",
            iceSummary = "ICE $normalizedIce",
            latencyText = "-- ms",
            driftText = "--",
            isWebRtcDirect = false
        )
    }

    if (participant.status == DeviceStatus.RECONNECTING || normalizedIce == "CHECKING") {
        return PeerConnectionAssessment(
            tier = ConnectionQualityTier.RECONNECTING,
            signalBars = 1,
            qualityLabel = if (normalizedIce == "CHECKING") "ICE Negotiating" else "Reconnecting",
            transportLabel = if (isWebRtcDirect) "WebRTC P2P" else "WS LAN",
            iceSummary = "ICE $normalizedIce",
            latencyText = if (participant.rttMs > 0L) "${participant.rttMs}ms RTT" else "Probing...",
            driftText = "${participant.driftMs}ms",
            isWebRtcDirect = isWebRtcDirect
        )
    }

    val rtt = participant.rttMs.coerceAtLeast(0L)
    val absDrift = abs(participant.driftMs)
    val (tier, bars, label) = when {
        rtt <= 25L && absDrift <= 20L -> Triple(ConnectionQualityTier.EXCELLENT, 4, "Ultra-Low")
        rtt <= 65L && absDrift <= 45L -> Triple(ConnectionQualityTier.GOOD, 3, "Healthy")
        rtt <= 140L && absDrift <= 95L -> Triple(ConnectionQualityTier.FAIR, 2, "Moderate")
        else -> Triple(ConnectionQualityTier.POOR, 1, "High Latency")
    }

    val iceDesc = when {
        normalizedIce == "IDLE" && isWebRtcDirect -> "ICE OPEN"
        normalizedIce == "IDLE" -> "WS Fallback"
        else -> "ICE $normalizedIce"
    }

    return PeerConnectionAssessment(
        tier = tier,
        signalBars = bars,
        qualityLabel = label,
        transportLabel = if (isWebRtcDirect) "WebRTC P2P" else "WS LAN",
        iceSummary = iceDesc,
        latencyText = "${rtt}ms RTT",
        driftText = "${if (participant.driftMs >= 0) "+" else ""}${participant.driftMs}ms",
        isWebRtcDirect = isWebRtcDirect
    )
}

fun assessLocalTelemetryConnection(role: SessionRole, telemetry: SyncTelemetry): PeerConnectionAssessment {
    val isHost = role == SessionRole.HOST
    val synthetic = Participant(
        id = "local",
        name = "Local",
        avatarColorHex = "#8B5CF6",
        isHost = isHost,
        status = when {
            isHost -> DeviceStatus.CONNECTED
            telemetry.syncHealth.name == "RECONNECTING" -> DeviceStatus.RECONNECTING
            else -> DeviceStatus.CONNECTED
        },
        rttMs = telemetry.rttMs,
        driftMs = telemetry.driftMs,
        transportMode = if (telemetry.isWebRtcP2pActive) TransportMode.WEBRTC_P2P else TransportMode.WS_SIGNALING,
        iceState = telemetry.iceState,
        p2pBytesTransferred = telemetry.p2pTransferredBytes
    )
    return assessParticipantConnection(synthetic)
}

@Composable
fun tierColor(tier: ConnectionQualityTier, isHost: Boolean = false): Color {
    if (isHost && tier == ConnectionQualityTier.EXCELLENT) return ElectricPurple
    return when (tier) {
        ConnectionQualityTier.EXCELLENT -> SyncEmerald
        ConnectionQualityTier.GOOD -> NeonBlue
        ConnectionQualityTier.FAIR -> SyncAmber
        ConnectionQualityTier.POOR -> SyncCoral
        ConnectionQualityTier.RECONNECTING -> SyncAmber
        ConnectionQualityTier.OFFLINE -> SyncCoral
    }
}

/**
 * Displays real-time WebRTC peer connection health, ICE state, signal bars, and network latency
 * for a party participant. Supports both a full diagnostic strip for device cards and a compact pill.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectionStatusIndicator(
    participant: Participant,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val assessment = assessParticipantConnection(participant)
    val accentColor by animateColorAsState(
        targetValue = tierColor(assessment.tier, participant.isHost),
        animationSpec = tween(350),
        label = "conn_accent"
    )
    val isPulsing = assessment.tier == ConnectionQualityTier.RECONNECTING ||
        assessment.tier == ConnectionQualityTier.EXCELLENT ||
        assessment.tier == ConnectionQualityTier.GOOD

    val accessibilitySummary = "${participant.name}: ${assessment.qualityLabel}, " +
        "${assessment.transportLabel}, ${assessment.iceSummary}, ${assessment.latencyText}, drift ${assessment.driftText}"

    if (compact) {
        Surface(
            modifier = modifier
                .testTag("conn_status_compact_${participant.id}")
                .semantics { contentDescription = accessibilitySummary },
            shape = RoundedCornerShape(50),
            color = accentColor.copy(alpha = 0.14f),
            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.48f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AnimatedHealthBeacon(color = accentColor, pulsing = isPulsing, size = 7.dp)
                LatencySignalBars(
                    activeBars = assessment.signalBars,
                    activeColor = accentColor,
                    pulsing = assessment.tier == ConnectionQualityTier.RECONNECTING,
                    width = 16.dp,
                    height = 12.dp
                )
                Text(
                    text = if (participant.isHost) "HOST" else assessment.latencyText,
                    style = MaterialTheme.typography.labelSmall,
                    color = accentColor,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        return
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("conn_status_indicator_${participant.id}")
            .semantics { contentDescription = accessibilitySummary },
        shape = RoundedCornerShape(14.dp),
        color = GraphiteDeep.copy(alpha = 0.68f),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.34f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
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
                    AnimatedHealthBeacon(color = accentColor, pulsing = isPulsing, size = 8.dp)
                    LatencySignalBars(
                        activeBars = assessment.signalBars,
                        activeColor = accentColor,
                        pulsing = assessment.tier == ConnectionQualityTier.RECONNECTING,
                        width = 20.dp,
                        height = 14.dp
                    )
                    Text(
                        text = assessment.qualityLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = accentColor,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Transport / WebRTC Direct badge
                val transportColor = if (assessment.isWebRtcDirect) NeonBlue else if (participant.isHost) ElectricPurple else TextSecondary
                Surface(
                    shape = RoundedCornerShape(50),
                    color = transportColor.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, transportColor.copy(alpha = 0.45f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = when {
                                assessment.tier == ConnectionQualityTier.OFFLINE -> Icons.Rounded.CloudOff
                                assessment.tier == ConnectionQualityTier.RECONNECTING -> Icons.Rounded.Sync
                                assessment.isWebRtcDirect -> Icons.Rounded.Bolt
                                participant.isHost -> Icons.Rounded.CellTower
                                else -> Icons.Rounded.Lan
                            },
                            contentDescription = null,
                            tint = transportColor,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = assessment.transportLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = transportColor,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Latency, ICE, Drift & P2P transfer metric pills
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MetricMiniChip(
                    label = assessment.latencyText,
                    color = accentColor,
                    testTag = "conn_rtt_${participant.id}"
                )
                MetricMiniChip(
                    label = assessment.iceSummary,
                    color = if (assessment.isWebRtcDirect) NeonBlue else TextSecondary,
                    testTag = "conn_ice_${participant.id}"
                )
                if (!participant.isHost && participant.status == DeviceStatus.CONNECTED) {
                    val driftColor = if (abs(participant.driftMs) <= 22L) SyncEmerald else SyncAmber
                    MetricMiniChip(
                        label = "Drift ${assessment.driftText}",
                        color = driftColor,
                        testTag = "conn_drift_${participant.id}"
                    )
                }
                if (participant.p2pBytesTransferred > 0L) {
                    val kb = (participant.p2pBytesTransferred / 1024L).coerceAtLeast(1L)
                    MetricMiniChip(
                        label = "${kb}KB P2P",
                        color = ElectricPurple,
                        testTag = "conn_p2p_bytes_${participant.id}"
                    )
                }
            }
        }
    }
}

@Composable
fun WebRtcMeshOverviewStrip(
    participants: List<Participant>,
    telemetry: SyncTelemetry,
    role: SessionRole,
    modifier: Modifier = Modifier
) {
    val listeners = participants.filterNot { it.isHost }
    val p2pConnected = listeners.count {
        it.status == DeviceStatus.CONNECTED &&
            (it.transportMode == TransportMode.WEBRTC_P2P ||
                it.iceState.equals("CONNECTED", ignoreCase = true) ||
                it.iceState.equals("COMPLETED", ignoreCase = true))
    }
    val avgRtt = listeners
        .filter { it.status == DeviceStatus.CONNECTED && it.rttMs > 0L }
        .map { it.rttMs }
        .takeIf { it.isNotEmpty() }
        ?.average()
        ?.toLong() ?: telemetry.rttMs
    val localAssessment = assessLocalTelemetryConnection(role, telemetry)
    val stripColor = tierColor(localAssessment.tier, role == SessionRole.HOST)

    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("webrtc_mesh_overview"),
        cornerRadius = 18.dp,
        borderColor = stripColor.copy(alpha = 0.38f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                LatencySignalBars(
                    activeBars = if (role == SessionRole.HOST && listeners.isEmpty()) 4 else localAssessment.signalBars,
                    activeColor = stripColor,
                    pulsing = localAssessment.tier == ConnectionQualityTier.RECONNECTING,
                    width = 22.dp,
                    height = 16.dp
                )
                Column {
                    Text(
                        text = if (role == SessionRole.HOST) {
                            "WebRTC P2P Mesh • $p2pConnected/${listeners.size} Direct"
                        } else {
                            "Peer Link • ${localAssessment.transportLabel} (${localAssessment.iceSummary})"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (role == SessionRole.HOST) {
                            if (listeners.isEmpty()) "Waiting for listeners to join room"
                            else "Avg LAN RTT ${avgRtt}ms • ${telemetry.framesStreamed} PCM frames sent"
                        } else {
                            "RTT ${telemetry.rttMs}ms • Jitter ${telemetry.jitterMs}ms • Buffer ${telemetry.bufferFillPercent}%"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricMiniChip(
    label: String,
    color: Color,
    testTag: String
) {
    Surface(
        modifier = Modifier.testTag(testTag),
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.11f),
        border = BorderStroke(0.75.dp, color.copy(alpha = 0.30f))
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun AnimatedHealthBeacon(
    color: Color,
    pulsing: Boolean,
    size: Dp
) {
    val transition = rememberInfiniteTransition(label = "beacon_pulse")
    val haloAlpha by transition.animateFloat(
        initialValue = 0.18f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(950, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "haloAlpha"
    )
    Box(
        modifier = Modifier.size(size * 1.9f),
        contentAlignment = Alignment.Center
    ) {
        if (pulsing) {
            Box(
                modifier = Modifier
                    .size(size * 1.8f)
                    .clip(CircleShape)
                    .background(color.copy(alpha = haloAlpha))
            )
        }
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(color)
        )
    }
}

@Composable
fun LatencySignalBars(
    activeBars: Int,
    activeColor: Color,
    pulsing: Boolean = false,
    width: Dp = 20.dp,
    height: Dp = 14.dp
) {
    val transition = rememberInfiniteTransition(label = "signal_bars")
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(650, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    val effectiveColor = if (pulsing) activeColor.copy(alpha = pulseAlpha) else activeColor
    val inactiveColor = TextMuted.copy(alpha = 0.28f)

    Canvas(modifier = Modifier.size(width = width, height = height)) {
        val barCount = 4
        val gap = this.size.width * 0.12f
        val barW = (this.size.width - gap * (barCount - 1)) / barCount
        for (i in 0 until barCount) {
            val fraction = (i + 1) / barCount.toFloat()
            val barH = this.size.height * (0.28f + 0.72f * fraction)
            val left = i * (barW + gap)
            val top = this.size.height - barH
            drawRoundRect(
                color = if (i < activeBars) effectiveColor else inactiveColor,
                topLeft = Offset(left, top),
                size = Size(barW, barH),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f)
            )
        }
    }
}
