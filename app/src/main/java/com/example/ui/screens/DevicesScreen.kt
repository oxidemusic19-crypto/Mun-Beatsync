package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.DeviceStatus
import com.example.model.Participant
import com.example.model.SessionRole
import com.example.model.SyncTelemetry
import com.example.ui.components.ConnectionStatusIndicator
import com.example.ui.components.GlassCard
import com.example.ui.components.ParticipantAvatar
import com.example.ui.components.WebRtcMeshOverviewStrip
import com.example.ui.components.formatSessionTimer
import com.example.ui.theme.ElectricPurple
import com.example.ui.theme.NeonBlue
import com.example.ui.theme.SyncAmber
import com.example.ui.theme.SyncCoral
import com.example.ui.theme.SyncEmerald
import com.example.ui.theme.TextSecondary

@Composable
fun DevicesScreen(
    role: SessionRole,
    participants: List<Participant>,
    totalListenersJoined: Int,
    sessionDurationSeconds: Long,
    isPlaying: Boolean,
    telemetry: SyncTelemetry,
    onKickParticipant: (String) -> Unit
) {
    val activeCount = participants.count { it.status == DeviceStatus.CONNECTED }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Column(modifier = Modifier.widthIn(max = 600.dp)) {
                // Party Statistics Banner
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    borderColor = NeonBlue.copy(alpha = 0.4f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StatMetric(
                            label = "CONNECTED",
                            value = "$activeCount",
                            color = SyncEmerald
                        )
                        StatMetric(
                            label = "TOTAL JOINED",
                            value = "$totalListenersJoined",
                            color = NeonBlue
                        )
                        StatMetric(
                            label = "SESSION",
                            value = formatSessionTimer(sessionDurationSeconds),
                            color = ElectricPurple
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                WebRtcMeshOverviewStrip(
                    participants = participants,
                    telemetry = telemetry,
                    role = role
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Devices,
                        contentDescription = null,
                        tint = NeonBlue
                    )
                    Text(
                        text = "Live Party Devices",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        items(participants, key = { it.id }) { participant ->
            val statusColor = when (participant.status) {
                DeviceStatus.CONNECTED -> SyncEmerald
                DeviceStatus.RECONNECTING -> SyncAmber
                DeviceStatus.DISCONNECTED -> SyncCoral
            }

            GlassCard(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .testTag("participant_card_${participant.id}"),
                borderColor = if (participant.isHost) ElectricPurple.copy(alpha = 0.6f)
                else statusColor.copy(alpha = 0.35f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            ParticipantAvatar(
                                name = participant.name,
                                colorHex = participant.avatarColorHex,
                                size = 46.dp,
                                isPulsing = isPlaying && participant.status == DeviceStatus.CONNECTED
                            )
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = participant.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (participant.isHost) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = ElectricPurple.copy(alpha = 0.25f)
                                        ) {
                                            Text(
                                                text = "HOST",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = ElectricPurple,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = participant.status.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = statusColor,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            ConnectionStatusIndicator(
                                participant = participant,
                                compact = true
                            )
                            if (role == SessionRole.HOST && !participant.isHost) {
                                IconButton(
                                    onClick = { onKickParticipant(participant.id) },
                                    modifier = Modifier.testTag("kick_participant_${participant.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.PersonRemove,
                                        contentDescription = "Remove ${participant.name}",
                                        tint = SyncCoral
                                    )
                                }
                            }
                        }
                    }

                    ConnectionStatusIndicator(
                        participant = participant,
                        compact = false
                    )
                }
            }
        }
    }
}

@Composable
private fun StatMetric(
    label: String,
    value: String,
    color: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            color = color,
            fontWeight = FontWeight.Bold
        )
    }
}
