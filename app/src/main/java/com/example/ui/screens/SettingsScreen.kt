package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.PartyAudioSettings
import com.example.model.RoomConnectionInfo
import com.example.model.SessionRole
import com.example.model.UserSettings
import com.example.ui.components.AudioSettingsPanel
import com.example.ui.components.GlassCard
import com.example.ui.theme.ElectricPurple
import com.example.ui.theme.NeonBlue
import com.example.ui.theme.SyncCoral
import com.example.ui.theme.SyncEmerald
import com.example.ui.theme.TextSecondary
import kotlin.math.roundToInt

private val avatarPalette = listOf("#8B5CF6", "#38BDF8", "#EC4899", "#10B981", "#F59E0B")

@Composable
fun SettingsScreen(
    role: SessionRole,
    settings: UserSettings,
    partyAudioSettings: PartyAudioSettings,
    baseVolume: Float,
    effectiveVolume: Float,
    isChatActive: Boolean,
    isAudioDucked: Boolean,
    roomInfo: RoomConnectionInfo,
    onUpdateNickname: (String) -> Unit,
    onUpdateAvatarColor: (String) -> Unit,
    onUpdateCalibrationOffset: (Int) -> Unit,
    onUpdateAutoReconnect: (Boolean) -> Unit,
    onUpdateDriftSensitivity: (Int) -> Unit,
    onToggleChatDucking: (Boolean) -> Unit,
    onUpdateChatDuckingReduction: (Int) -> Unit,
    onLeaveParty: () -> Unit
) {
    var nickDraft by remember(settings.nickname) { mutableStateOf(settings.nickname) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.widthIn(max = 600.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Tune,
                    contentDescription = null,
                    tint = ElectricPurple
                )
                Text(
                    text = "Audio & Sync Settings",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            // Profile Card
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Device Identity",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedTextField(
                        value = nickDraft,
                        onValueChange = {
                            nickDraft = it
                            onUpdateNickname(it)
                        },
                        label = { Text("Display Nickname") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_nickname_input"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ElectricPurple,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.2f)
                        )
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Avatar Glow:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                        avatarPalette.forEach { hex ->
                            val c = Color(android.graphics.Color.parseColor(hex))
                            val selected = settings.avatarColorHex.equals(hex, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(c)
                                    .border(
                                        width = if (selected) 2.5.dp else 0.dp,
                                        color = Color.White,
                                        shape = CircleShape
                                    )
                                    .clickable { onUpdateAvatarColor(hex) }
                            )
                        }
                    }
                }
            }

            // Host-Controlled Audio Settings Panel (Party Chat Ducking)
            AudioSettingsPanel(
                role = role,
                audioSettings = partyAudioSettings,
                baseVolume = baseVolume,
                effectiveVolume = effectiveVolume,
                isChatActive = isChatActive,
                isAudioDucked = isAudioDucked,
                onToggleChatDucking = onToggleChatDucking,
                onDuckingReductionChange = onUpdateChatDuckingReduction
            )

            // Sync Calibration Card
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Speaker / BT Latency Calibration",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${if (settings.calibrationOffsetMs >= 0) "+" else ""}${settings.calibrationOffsetMs} ms",
                            style = MaterialTheme.typography.labelMedium,
                            color = NeonBlue
                        )
                    }
                    Slider(
                        value = settings.calibrationOffsetMs.toFloat(),
                        onValueChange = { onUpdateCalibrationOffset(it.roundToInt()) },
                        valueRange = -150f..150f,
                        modifier = Modifier.testTag("calibration_offset_slider"),
                        colors = SliderDefaults.colors(
                            thumbColor = NeonBlue,
                            activeTrackColor = ElectricPurple
                        )
                    )
                    Text(
                        text = "Shift playback slightly ahead (+) or behind (-) to compensate for Bluetooth speakers or phone audio pipeline delay.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Drift Lock Tolerance",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "±${settings.driftSensitivityMs} ms",
                            style = MaterialTheme.typography.labelMedium,
                            color = SyncEmerald
                        )
                    }
                    Slider(
                        value = settings.driftSensitivityMs.toFloat(),
                        onValueChange = { onUpdateDriftSensitivity(it.roundToInt()) },
                        valueRange = 12f..55f,
                        modifier = Modifier.testTag("drift_tolerance_slider"),
                        colors = SliderDefaults.colors(
                            thumbColor = SyncEmerald,
                            activeTrackColor = SyncEmerald
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Auto-Reconnect on Wi-Fi Drop",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Automatically re-sync clock and resume on brief dropouts",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = settings.autoReconnect,
                            onCheckedChange = onUpdateAutoReconnect,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = ElectricPurple
                            )
                        )
                    }
                }
            }

            // Local Network Diagnostics Card
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                borderColor = NeonBlue.copy(alpha = 0.35f)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lan,
                            contentDescription = null,
                            tint = NeonBlue
                        )
                        Text(
                            text = "Local Network Diagnostics",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "Transport: WebRTC DataChannels (audio-stream PCM + sync-ctrl UDP + party-data) + HTTP/1.1 Range + WS Signaling",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Text(
                        text = "Host IP: ${roomInfo.hostIp} • WS Port: ${roomInfo.wsPort} • HTTP Audio Port: ${roomInfo.httpPort}",
                        style = MaterialTheme.typography.labelSmall,
                        color = NeonBlue
                    )
                }
            }

            // Leave / End Party Button
            Button(
                onClick = onLeaveParty,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("leave_party_button"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SyncCoral.copy(alpha = 0.2f),
                    contentColor = SyncCoral
                ),
                border = BorderStroke(1.dp, SyncCoral.copy(alpha = 0.6f))
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ExitToApp,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (role == SessionRole.HOST) "End Party & Stop Server" else "Leave Party",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
