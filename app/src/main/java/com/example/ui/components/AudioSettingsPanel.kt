package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.R
import com.example.audio.AudioDuckingHelper
import com.example.model.PartyAudioSettings
import com.example.model.SessionRole
import com.example.ui.theme.ElectricPurple
import com.example.ui.theme.GraphiteDeep
import com.example.ui.theme.NeonBlue
import com.example.ui.theme.SyncEmerald
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary
import kotlin.math.roundToInt

@Composable
fun AudioSettingsPanel(
    role: SessionRole,
    audioSettings: PartyAudioSettings,
    baseVolume: Float,
    effectiveVolume: Float,
    isChatActive: Boolean,
    isAudioDucked: Boolean,
    onToggleChatDucking: (Boolean) -> Unit,
    onDuckingReductionChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val isHost = role == SessionRole.HOST
    val targetMult = AudioDuckingHelper.computeTargetDuckMultiplier(
        audioSettings = audioSettings.copy(chatDuckingEnabled = true),
        isPartyChatActive = true
    )
    val duckedPreviewPercent = (AudioDuckingHelper.computeEffectiveVolume(baseVolume, targetMult) * 100).roundToInt()
    val basePercent = (baseVolume * 100).roundToInt()
    val effectivePercent = (effectiveVolume * 100).roundToInt()

    val borderColor = when {
        isAudioDucked -> NeonBlue.copy(alpha = 0.52f)
        audioSettings.chatDuckingEnabled -> ElectricPurple.copy(alpha = 0.44f)
        else -> Color.White.copy(alpha = 0.14f)
    }

    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("audio_settings_panel"),
        borderColor = borderColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
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
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        tint = if (isAudioDucked) NeonBlue else ElectricPurple
                    )
                    Text(
                        text = stringResource(R.string.audio_settings_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                val badgeColor = when {
                    isAudioDucked -> NeonBlue
                    audioSettings.chatDuckingEnabled -> SyncEmerald
                    else -> TextMuted
                }
                val badgeText = when {
                    isAudioDucked -> "DUCKED • $effectivePercent%"
                    audioSettings.chatDuckingEnabled -> "DUCKING ON"
                    !isHost -> "HOST CONTROL"
                    else -> "OFF"
                }

                Surface(
                    shape = RoundedCornerShape(50),
                    color = badgeColor.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.45f))
                ) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeColor,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                    )
                }
            }

            // Chat Ducking Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp)
                ) {
                    Text(
                        text = "Party Chat Audio Ducking",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Automatically lowers music volume slightly when party chat is active for clearer communication.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }

                Switch(
                    checked = audioSettings.chatDuckingEnabled,
                    onCheckedChange = { if (isHost) onToggleChatDucking(it) },
                    enabled = isHost,
                    modifier = Modifier.testTag("chat_ducking_switch"),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = ElectricPurple,
                        disabledCheckedThumbColor = Color.White.copy(alpha = 0.7f),
                        disabledCheckedTrackColor = ElectricPurple.copy(alpha = 0.45f)
                    )
                )
            }

            AnimatedVisibility(visible = audioSettings.chatDuckingEnabled) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Chat Volume Reduction",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White
                        )
                        Text(
                            text = "-${audioSettings.duckingReductionPercent}%",
                            style = MaterialTheme.typography.labelLarge,
                            color = NeonBlue,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Slider(
                        value = audioSettings.duckingReductionPercent.toFloat(),
                        onValueChange = { if (isHost) onDuckingReductionChange(it.roundToInt()) },
                        valueRange = 15f..65f,
                        enabled = isHost,
                        modifier = Modifier.testTag("ducking_reduction_slider"),
                        colors = SliderDefaults.colors(
                            thumbColor = NeonBlue,
                            activeTrackColor = ElectricPurple,
                            inactiveTrackColor = Color.White.copy(alpha = 0.14f),
                            disabledThumbColor = NeonBlue.copy(alpha = 0.55f),
                            disabledActiveTrackColor = ElectricPurple.copy(alpha = 0.55f)
                        )
                    )

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = GraphiteDeep.copy(alpha = 0.65f),
                        border = BorderStroke(
                            0.75.dp,
                            if (isAudioDucked) NeonBlue.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.10f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Forum,
                                    contentDescription = null,
                                    tint = if (isChatActive) NeonBlue else TextMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = if (isChatActive) "Chat Active • Music Ducked" else "Chat Standby",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isChatActive) NeonBlue else TextSecondary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            Text(
                                text = "Vol $basePercent% → $duckedPreviewPercent%",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }

            if (!isHost) {
                Text(
                    text = "Only the party host can change room ducking settings.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted
                )
            }
        }
    }
}

@Composable
fun AudioSettingsDialog(
    role: SessionRole,
    audioSettings: PartyAudioSettings,
    baseVolume: Float,
    effectiveVolume: Float,
    isChatActive: Boolean,
    isAudioDucked: Boolean,
    onToggleChatDucking: (Boolean) -> Unit,
    onDuckingReductionChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = GraphiteDeep,
            border = BorderStroke(1.dp, ElectricPurple.copy(alpha = 0.45f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.GraphicEq,
                            contentDescription = null,
                            tint = NeonBlue
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.audio_settings_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("close_audio_settings_dialog")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.cd_close)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                AudioSettingsPanel(
                    role = role,
                    audioSettings = audioSettings,
                    baseVolume = baseVolume,
                    effectiveVolume = effectiveVolume,
                    isChatActive = isChatActive,
                    isAudioDucked = isAudioDucked,
                    onToggleChatDucking = onToggleChatDucking,
                    onDuckingReductionChange = onDuckingReductionChange
                )
            }
        }
    }
}
