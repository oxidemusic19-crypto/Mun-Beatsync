package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.SyncHealth
import com.example.ui.theme.ElectricIndigo
import com.example.ui.theme.ElectricPurple
import com.example.ui.theme.GlassBorder
import com.example.ui.theme.GraphiteDeep
import com.example.ui.theme.GraphiteSurface
import com.example.ui.theme.NeonBlue
import com.example.ui.theme.ObsidianBlack
import com.example.ui.theme.SyncAmber
import com.example.ui.theme.SyncCoral
import com.example.ui.theme.SyncEmerald
import com.example.ui.theme.VividViolet
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

@Composable
fun SyncBeatBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBlack)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            ElectricPurple.copy(alpha = 0.20f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.18f, size.height * 0.14f),
                        radius = size.maxDimension * 0.55f
                    )
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            NeonBlue.copy(alpha = 0.15f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.86f, size.height * 0.78f),
                        radius = size.maxDimension * 0.50f
                    )
                )
            },
        content = content
    )
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 22.dp,
    borderColor: Color = GlassBorder,
    containerColor: Color = GraphiteSurface.copy(alpha = 0.78f),
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(cornerRadius),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        tonalElevation = 4.dp
    ) {
        Column(content = content)
    }
}

@Composable
fun SyncBeatLogoMark(
    size: Dp = 76.dp,
    animated: Boolean = true
) {
    val transition = rememberInfiniteTransition(label = "logo_pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    val scale = if (animated) pulse else 1f
    val wavePhase = if (animated) phase else 0f

    Canvas(modifier = Modifier.size(size)) {
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val radius = this.size.minDimension / 2f

        // Outer concentric resonance rings
        drawCircle(
            brush = Brush.sweepGradient(
                listOf(ElectricPurple, NeonBlue, VividViolet, ElectricPurple)
            ),
            radius = radius * 0.92f * scale.coerceAtMost(1.02f),
            center = center,
            style = Stroke(width = radius * 0.08f)
        )
        drawCircle(
            color = GraphiteDeep,
            radius = radius * 0.78f,
            center = center
        )

        // 5 equalized soundwave bars forming the SYNCBEAT emblem
        val barCount = 5
        val barWidth = radius * 0.13f
        val gap = radius * 0.09f
        val totalWidth = barCount * barWidth + (barCount - 1) * gap
        val startX = center.x - totalWidth / 2f
        val baseHeights = floatArrayOf(0.42f, 0.78f, 1.0f, 0.72f, 0.46f)

        for (i in 0 until barCount) {
            val mod = if (animated) 0.82f + 0.18f * abs(sin(wavePhase + i * 0.9f)) else 1f
            val h = radius * 0.92f * baseHeights[i] * mod
            val x = startX + i * (barWidth + gap)
            val top = center.y - h / 2f
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(NeonBlue, ElectricPurple, VividViolet)
                ),
                topLeft = Offset(x, top),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

@Composable
fun SyncHealthBadge(
    health: SyncHealth,
    driftMs: Long,
    rttMs: Long,
    modifier: Modifier = Modifier
) {
    val (label, color) = when (health) {
        SyncHealth.HOST_CLOCK -> "HOST CLOCK" to ElectricPurple
        SyncHealth.LOCKED -> "LOCKED ${driftMs}ms" to SyncEmerald
        SyncHealth.CORRECTING -> "SYNCING ${driftMs}ms" to SyncAmber
        SyncHealth.BUFFERING -> "BUFFERING" to NeonBlue
        SyncHealth.RECONNECTING -> "RECONNECTING" to SyncCoral
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Text(
                text = if (health != SyncHealth.HOST_CLOCK && rttMs > 0) "$label • ${rttMs}ms RTT" else label,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun ParticipantAvatar(
    name: String,
    colorHex: String,
    size: Dp = 44.dp,
    isPulsing: Boolean = false
) {
    val parsedColor = runCatching {
        Color(android.graphics.Color.parseColor(colorHex))
    }.getOrDefault(ElectricPurple)

    val initials = name.trim()
        .split(" ")
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
        .ifEmpty { "S" }

    val transition = rememberInfiniteTransition(label = "avatar_pulse")
    val ringAlpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ringAlpha"
    )

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    colors = listOf(parsedColor.copy(alpha = 0.9f), ElectricIndigo.copy(alpha = 0.85f))
                )
            )
            .border(
                width = if (isPulsing) 2.dp else 1.dp,
                color = if (isPulsing) parsedColor.copy(alpha = ringAlpha) else Color.White.copy(alpha = 0.22f),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
    }
}

fun artworkDrawableForKey(key: String): Int = when (key) {
    "horizon" -> R.drawable.img_album_horizon
    "voltage" -> R.drawable.img_album_voltage
    else -> R.drawable.img_album_pulse
}

fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return "%d:%02d".format(mins, secs)
}

fun formatSessionTimer(seconds: Long): String {
    val hrs = seconds / 3600L
    val mins = (seconds % 3600L) / 60L
    val secs = seconds % 60L
    return if (hrs > 0) "%02d:%02d:%02d".format(hrs, mins, secs)
    else "%02d:%02d".format(mins, secs)
}
