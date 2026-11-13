package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import com.example.audio.AudioFrequencyAnalyzer
import com.example.audio.FrequencySpectrumFrame
import com.example.ui.theme.ElectricIndigo
import com.example.ui.theme.ElectricPurple
import com.example.ui.theme.MagentaPulse
import com.example.ui.theme.NeonBlue
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VividViolet
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Canvas-based real-time frequency visualizer reacting to WebRTC PCM audio frames
 * with pulsating neon-blue and purple geometric rings, polygons, harmonic ribbons,
 * and mirrored FFT spectrum bars with peak-hold diamonds.
 */
@Composable
fun WebRtcFrequencyVisualizer(
    spectrum: FrequencySpectrumFrame,
    waveformBins: List<Float>,
    progressFraction: Float,
    isPlaying: Boolean,
    isWebRtcActive: Boolean,
    modifier: Modifier = Modifier
) {
    val bandCount = AudioFrequencyAnalyzer.BAND_COUNT
    val peakHolds = remember {
        mutableStateListOf<Float>().apply { repeat(bandCount) { add(0.08f) } }
    }

    val transition = rememberInfiniteTransition(label = "webrtc_freq_viz")
    val orbitAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 7000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbitAngle"
    )
    val breathPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathPhase"
    )

    val bassPulse by animateFloatAsState(
        targetValue = if (isPlaying) spectrum.bassEnergy else 0.08f,
        animationSpec = tween(durationMillis = 80),
        label = "bassPulse"
    )
    val midPulse by animateFloatAsState(
        targetValue = if (isPlaying) spectrum.midEnergy else 0.08f,
        animationSpec = tween(durationMillis = 90),
        label = "midPulse"
    )
    val highPulse by animateFloatAsState(
        targetValue = if (isPlaying) spectrum.highEnergy else 0.06f,
        animationSpec = tween(durationMillis = 70),
        label = "highPulse"
    )

    // Update decaying peak-hold caps whenever live bands arrive
    LaunchedEffect(spectrum, isPlaying) {
        val src = spectrum.bands
        for (i in 0 until bandCount) {
            val energy = if (isPlaying) src.getOrElse(i) { 0.08f } else 0.06f
            val curPeak = peakHolds[i]
            peakHolds[i] = if (energy >= curPeak) {
                energy
            } else {
                (curPeak - 0.032f).coerceAtLeast(energy)
            }
        }
    }

    val signalLabel = when {
        !isPlaying -> "PAUSED"
        spectrum.isLivePcmSignal && isWebRtcActive -> "WEBRTC FFT • LIVE"
        spectrum.isLivePcmSignal -> "PCM FFT • LIVE"
        else -> "SYNC WAVE"
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("webrtc_frequency_visualizer")
            .semantics {
                contentDescription = "Real-time frequency visualizer: $signalLabel"
            },
        verticalArrangement = Arrangement.spacedBy(8.dp)
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
                        .background(
                            if (isPlaying && spectrum.isLivePcmSignal) NeonBlue
                            else if (isPlaying) ElectricPurple
                            else TextMuted
                        )
                )
                Text(
                    text = signalLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isPlaying && spectrum.isLivePcmSignal) NeonBlue else TextSecondary,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = "BASS ${(bassPulse * 100).toInt()}% • MID ${(midPulse * 100).toInt()}% • HI ${(highPulse * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(104.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val minDim = size.minDimension

                // 1. Pulsating Neon-Blue & Electric-Purple Radial Aura driven by Bass & Mid Energy
                val outerHaloRadius = minDim * (0.58f + bassPulse * 0.62f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            ElectricPurple.copy(alpha = (0.20f + bassPulse * 0.34f).coerceAtMost(0.55f)),
                            NeonBlue.copy(alpha = (0.12f + midPulse * 0.24f).coerceAtMost(0.38f)),
                            Color.Transparent
                        ),
                        center = center,
                        radius = outerHaloRadius.coerceAtLeast(10f)
                    ),
                    radius = outerHaloRadius,
                    center = center
                )

                // 2. Pulsating Concentric Neon Circles & Rotating Geometric Polygons
                val ringBaseRadius = minDim * (0.28f + bassPulse * 0.22f)
                drawCircle(
                    brush = Brush.sweepGradient(
                        colors = listOf(NeonBlue, ElectricPurple, VividViolet, NeonBlue),
                        center = center
                    ),
                    radius = ringBaseRadius,
                    center = center,
                    alpha = if (isPlaying) 0.42f + bassPulse * 0.42f else 0.18f,
                    style = Stroke(width = (1.6f + bassPulse * 2.4f).dp.toPx())
                )

                drawPulsatingPolygon(
                    center = center,
                    radius = minDim * (0.22f + midPulse * 0.24f),
                    sides = 6,
                    rotationDeg = orbitAngle,
                    color = NeonBlue.copy(alpha = if (isPlaying) 0.35f + midPulse * 0.45f else 0.16f),
                    strokeWidthPx = (1.2f + midPulse * 1.8f).dp.toPx()
                )

                drawPulsatingPolygon(
                    center = center,
                    radius = minDim * (0.18f + highPulse * 0.22f),
                    sides = 8,
                    rotationDeg = -orbitAngle * 1.35f,
                    color = VividViolet.copy(alpha = if (isPlaying) 0.30f + highPulse * 0.48f else 0.14f),
                    strokeWidthPx = (1.1f + highPulse * 1.5f).dp.toPx()
                )

                // 3. Symmetrical Frequency Spectrum Bars + Peak-Hold Neon Diamonds
                val gap = 4.dp.toPx()
                val barWidth = ((size.width - (bandCount - 1) * gap) / bandCount).coerceAtLeast(3f)
                val fallbackBins = if (waveformBins.isNotEmpty()) waveformBins else List(bandCount) { 0.35f }
                val activeCenter = (progressFraction * fallbackBins.size).toInt().coerceIn(0, fallbackBins.lastIndex)

                val splinePoints = ArrayList<Offset>(bandCount)

                for (i in 0 until bandCount) {
                    val liveBand = spectrum.bands.getOrElse(i) { 0.08f }
                    val energy = when {
                        isPlaying && spectrum.isLivePcmSignal -> liveBand
                        isPlaying -> {
                            val mapIdx = ((i.toFloat() / bandCount) * fallbackBins.size).toInt().coerceIn(0, fallbackBins.lastIndex)
                            val neighbor = fallbackBins[(activeCenter + (i % 5) - 2).coerceIn(0, fallbackBins.lastIndex)]
                            val waveMod = 0.65f + 0.35f * abs(sin(breathPhase + i * 0.45f))
                            ((fallbackBins[mapIdx] * 0.5f + neighbor * 0.5f) * waveMod).coerceIn(0.10f, 0.92f)
                        }
                        else -> {
                            val mapIdx = ((i.toFloat() / bandCount) * fallbackBins.size).toInt().coerceIn(0, fallbackBins.lastIndex)
                            (fallbackBins[mapIdx] * 0.28f).coerceIn(0.08f, 0.34f)
                        }
                    }

                    val x = i * (barWidth + gap)
                    val barHeight = (size.height * 0.86f * energy).coerceAtLeast(4.dp.toPx())
                    val topY = (size.height - barHeight) / 2f
                    val isPlayedProgress = (i.toFloat() / bandCount) <= progressFraction

                    val barBrush = if (isPlaying) {
                        Brush.verticalGradient(
                            colors = if (i % 2 == 0) {
                                listOf(NeonBlue, ElectricPurple, VividViolet)
                            } else {
                                listOf(NeonBlue.copy(alpha = 0.92f), ElectricIndigo, MagentaPulse.copy(alpha = 0.85f))
                            },
                            startY = topY,
                            endY = topY + barHeight
                        )
                    } else {
                        Brush.verticalGradient(
                            colors = listOf(
                                ElectricIndigo.copy(alpha = 0.45f),
                                ElectricPurple.copy(alpha = 0.30f)
                            )
                        )
                    }

                    drawRoundRect(
                        brush = barBrush,
                        topLeft = Offset(x, topY),
                        size = Size(barWidth, barHeight),
                        cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                        alpha = if (isPlayedProgress || spectrum.isLivePcmSignal) 0.96f else 0.72f
                    )

                    // Floating Neon-Blue / Purple Peak-Hold Diamond Caps
                    if (isPlaying) {
                        val peakVal = peakHolds.getOrElse(i) { energy }.coerceAtLeast(energy)
                        val peakH = size.height * 0.86f * peakVal
                        val peakTopY = ((size.height - peakH) / 2f - 4.dp.toPx()).coerceAtLeast(2f)
                        val cx = x + barWidth / 2f
                        val capSize = (barWidth * 0.42f).coerceIn(2.dp.toPx(), 4.5.dp.toPx())
                        val diamond = Path().apply {
                            moveTo(cx, peakTopY - capSize)
                            lineTo(cx + capSize, peakTopY)
                            lineTo(cx, peakTopY + capSize)
                            lineTo(cx - capSize, peakTopY)
                            close()
                        }
                        drawPath(
                            path = diamond,
                            color = if (i % 3 == 0) NeonBlue else VividViolet,
                            alpha = 0.92f
                        )
                    }

                    // Collect upper envelope points for harmonic wave spline
                    val waveY = topY + (barHeight * 0.18f) * sin(breathPhase + i * 0.55f)
                    splinePoints.add(Offset(x + barWidth / 2f, waveY.coerceIn(4f, size.height - 4f)))
                }

                // 4. Sweeping Harmonic Neon-Blue & Purple Spline across Spectrum Peaks
                if (splinePoints.size >= 2) {
                    val wavePath = Path().apply {
                        moveTo(splinePoints[0].x, splinePoints[0].y)
                        for (i in 1 until splinePoints.size) {
                            val prev = splinePoints[i - 1]
                            val curr = splinePoints[i]
                            val midX = (prev.x + curr.x) / 2f
                            cubicTo(midX, prev.y, midX, curr.y, curr.x, curr.y)
                        }
                    }
                    drawPath(
                        path = wavePath,
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                NeonBlue.copy(alpha = 0.85f),
                                ElectricPurple.copy(alpha = 0.95f),
                                NeonBlue.copy(alpha = 0.85f)
                            )
                        ),
                        alpha = if (isPlaying) 0.55f + highPulse * 0.40f else 0.25f,
                        style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawPulsatingPolygon(
    center: Offset,
    radius: Float,
    sides: Int,
    rotationDeg: Float,
    color: Color,
    strokeWidthPx: Float
) {
    if (sides < 3 || radius <= 1f) return
    val path = Path()
    val rotRad = Math.toRadians(rotationDeg.toDouble())
    for (i in 0 until sides) {
        val angle = rotRad + (2.0 * PI * i) / sides
        val px = center.x + (radius * cos(angle)).toFloat()
        val py = center.y + (radius * sin(angle)).toFloat()
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    path.close()
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = strokeWidthPx)
    )
}
