package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.DeviceStatus
import com.example.model.Participant
import com.example.model.SessionRole
import com.example.model.SyncHealth
import com.example.model.SyncTelemetry
import com.example.ui.theme.GraphiteDeep
import com.example.ui.theme.SyncAmber
import com.example.ui.theme.SyncCoral
import com.example.ui.theme.SyncEmerald
import com.example.ui.theme.TextSecondary
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sqrt

enum class SyncHealthTier {
    GREEN,
    YELLOW,
    RED
}

data class SyncHealthAssessment(
    val tier: SyncHealthTier,
    val statusLabel: String,
    val detailLabel: String,
    val driftVarianceMs: Long,
    val currentDriftMs: Long,
    val stabilityFraction: Float
)

fun assessSyncHealth(
    role: SessionRole,
    telemetry: SyncTelemetry,
    participants: List<Participant>,
    driftToleranceMs: Int = 22,
    isPlaying: Boolean = true
): SyncHealthAssessment {
    val tolerance = driftToleranceMs.coerceIn(10, 50).toLong()
    val yellowCeiling = maxOf(60L, tolerance * 2L + 16L)

    if (role == SessionRole.HOST) {
        val listeners = participants.filterNot { it.isHost }
        if (listeners.isEmpty()) {
            return SyncHealthAssessment(
                tier = SyncHealthTier.GREEN,
                statusLabel = "Master Clock",
                detailLabel = "0ms ref • Waiting for clients",
                driftVarianceMs = 0L,
                currentDriftMs = 0L,
                stabilityFraction = 1.0f
            )
        }

        val connected = listeners.filter { it.status == DeviceStatus.CONNECTED }
        val reconnectingCount = listeners.count { it.status == DeviceStatus.RECONNECTING }
        val disconnectedCount = listeners.count { it.status == DeviceStatus.DISCONNECTED }

        if (connected.isEmpty()) {
            return SyncHealthAssessment(
                tier = SyncHealthTier.RED,
                statusLabel = if (reconnectingCount > 0) "Clients Reconnecting" else "Clients Offline",
                detailLabel = "0/${listeners.size} synced",
                driftVarianceMs = 0L,
                currentDriftMs = 0L,
                stabilityFraction = 0.18f
            )
        }

        val clientDrifts = connected.map { it.driftMs }
        val mean = clientDrifts.average()
        val varianceMs = if (clientDrifts.size >= 2) {
            sqrt(clientDrifts.sumOf { (it - mean) * (it - mean) } / clientDrifts.size).roundToLong()
        } else {
            abs(clientDrifts.first())
        }
        val peakDriftMs = connected.maxOf { abs(it.driftMs) }
        val effectiveSpread = maxOf(varianceMs, peakDriftMs)

        val tier = when {
            effectiveSpread > yellowCeiling -> SyncHealthTier.RED
            effectiveSpread > tolerance || reconnectingCount > 0 || disconnectedCount > 0 -> SyncHealthTier.YELLOW
            else -> SyncHealthTier.GREEN
        }

        val statusLabel = when (tier) {
            SyncHealthTier.GREEN -> "Tight Lock"
            SyncHealthTier.YELLOW -> if (reconnectingCount > 0) "Client Syncing" else "Slight Drift"
            SyncHealthTier.RED -> "High Variance"
        }

        val stability = (1f - (effectiveSpread.toFloat() / (yellowCeiling * 1.45f))).coerceIn(0.14f, 1.0f)
        return SyncHealthAssessment(
            tier = tier,
            statusLabel = statusLabel,
            detailLabel = "${connected.size}/${listeners.size} • ±${varianceMs}ms var • Peak ±${peakDriftMs}ms",
            driftVarianceMs = varianceMs,
            currentDriftMs = peakDriftMs,
            stabilityFraction = stability
        )
    }

    // Listener (Client) real-time host-to-client drift & variance assessment
    if (telemetry.syncHealth == SyncHealth.RECONNECTING) {
        return SyncHealthAssessment(
            tier = SyncHealthTier.RED,
            statusLabel = "Reconnecting",
            detailLabel = if (telemetry.rttMs > 0L) "Link lost • ${telemetry.rttMs}ms RTT" else "Link interrupted",
            driftVarianceMs = telemetry.driftVarianceMs,
            currentDriftMs = telemetry.driftMs,
            stabilityFraction = 0.14f
        )
    }

    val varianceMs = telemetry.driftVarianceMs.coerceAtLeast(0L)
    val absDrift = abs(telemetry.driftMs)
    val effectiveSpread = maxOf(varianceMs, absDrift)

    val tier = when {
        effectiveSpread > yellowCeiling -> SyncHealthTier.RED
        effectiveSpread > tolerance || telemetry.syncHealth == SyncHealth.CORRECTING -> SyncHealthTier.YELLOW
        else -> SyncHealthTier.GREEN
    }

    val statusLabel = when (tier) {
        SyncHealthTier.GREEN -> if (telemetry.syncHealth == SyncHealth.BUFFERING && telemetry.sampleCount == 0) {
            "Ready"
        } else {
            "Locked"
        }
        SyncHealthTier.YELLOW -> "Drifting"
        SyncHealthTier.RED -> "High Drift"
    }

    val signedDrift = "${if (telemetry.driftMs >= 0L) "+" else ""}${telemetry.driftMs}ms"
    val detailLabel = if (!isPlaying && varianceMs == 0L && absDrift == 0L) {
        "Standby • ±0ms var"
    } else {
        "±${varianceMs}ms var • $signedDrift"
    }

    val stability = (1f - (effectiveSpread.toFloat() / (yellowCeiling * 1.45f))).coerceIn(0.14f, 1.0f)
    return SyncHealthAssessment(
        tier = tier,
        statusLabel = statusLabel,
        detailLabel = detailLabel,
        driftVarianceMs = varianceMs,
        currentDriftMs = telemetry.driftMs,
        stabilityFraction = stability
    )
}

/**
 * Subtle, low-profile Sync Health bar displaying color-coded Green / Yellow / Red indicators
 * and a smoothly animated drift-stability track on the Main Party Player screen.
 */
@Composable
fun SyncHealthBar(
    role: SessionRole,
    telemetry: SyncTelemetry,
    participants: List<Participant>,
    driftToleranceMs: Int,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val assessment = assessSyncHealth(
        role = role,
        telemetry = telemetry,
        participants = participants,
        driftToleranceMs = driftToleranceMs,
        isPlaying = isPlaying
    )

    val activeColor by animateColorAsState(
        targetValue = when (assessment.tier) {
            SyncHealthTier.GREEN -> SyncEmerald
            SyncHealthTier.YELLOW -> SyncAmber
            SyncHealthTier.RED -> SyncCoral
        },
        animationSpec = tween(durationMillis = 320),
        label = "sync_health_color"
    )
    val animatedFill by animateFloatAsState(
        targetValue = assessment.stabilityFraction,
        animationSpec = tween(durationMillis = 360),
        label = "sync_health_fill"
    )

    val titleText = stringResource(R.string.sync_health_label)
    val accessibilityText = "$titleText: ${assessment.statusLabel}, ${assessment.detailLabel}"

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("sync_health_bar")
            .semantics { contentDescription = accessibilityText },
        shape = RoundedCornerShape(14.dp),
        color = GraphiteDeep.copy(alpha = 0.56f),
        border = BorderStroke(0.75.dp, activeColor.copy(alpha = 0.30f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(activeColor)
                    )
                    Text(
                        text = titleText.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "• ${assessment.statusLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = activeColor,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = assessment.detailLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    // Subtle Green / Yellow / Red tri-dot indicator
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TriHealthDot(
                            color = SyncEmerald,
                            isActive = assessment.tier == SyncHealthTier.GREEN,
                            tag = "sync_health_dot_green"
                        )
                        TriHealthDot(
                            color = SyncAmber,
                            isActive = assessment.tier == SyncHealthTier.YELLOW,
                            tag = "sync_health_dot_yellow"
                        )
                        TriHealthDot(
                            color = SyncCoral,
                            isActive = assessment.tier == SyncHealthTier.RED,
                            tag = "sync_health_dot_red"
                        )
                    }
                }
            }

            // Sleek horizontal stability bar with subtle G / Y / R zone background
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .testTag("sync_health_progress_track")
            ) {
                val trackRadius = CornerRadius(size.height / 2f, size.height / 2f)
                val segGap = 3.dp.toPx()
                val segWidth = ((size.width - segGap * 2f) / 3f).coerceAtLeast(1f)

                // Subtle 3-zone backdrop (Red | Yellow | Green) from left (unstable) to right (tight lock)
                val zoneColors = listOf(
                    SyncCoral.copy(alpha = 0.15f),
                    SyncAmber.copy(alpha = 0.15f),
                    SyncEmerald.copy(alpha = 0.15f)
                )
                for (i in 0..2) {
                    drawRoundRect(
                        color = zoneColors[i],
                        topLeft = Offset(i * (segWidth + segGap), 0f),
                        size = Size(segWidth, size.height),
                        cornerRadius = trackRadius
                    )
                }

                // Active animated health fill
                val fillWidth = (size.width * animatedFill).coerceIn(size.height, size.width)
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            activeColor.copy(alpha = 0.55f),
                            activeColor
                        ),
                        startX = 0f,
                        endX = fillWidth
                    ),
                    topLeft = Offset.Zero,
                    size = Size(fillWidth, size.height),
                    cornerRadius = trackRadius
                )

                // Subtle glowing tip dot on leading edge
                drawCircle(
                    color = Color.White.copy(alpha = 0.82f),
                    radius = size.height * 0.42f,
                    center = Offset((fillWidth - size.height * 0.45f).coerceAtLeast(size.height * 0.5f), size.height / 2f)
                )
            }
        }
    }
}

@Composable
private fun TriHealthDot(
    color: Color,
    isActive: Boolean,
    tag: String
) {
    Box(
        modifier = Modifier
            .size(if (isActive) 8.dp else 6.dp)
            .clip(CircleShape)
            .testTag(tag)
            .background(if (isActive) color else color.copy(alpha = 0.22f))
    )
}
