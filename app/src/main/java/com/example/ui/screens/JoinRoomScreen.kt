package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.DiscoveredRoom
import com.example.network.SyncBeatHostServer
import com.example.qr.QrScannerDialog
import com.example.ui.components.GlassCard
import com.example.ui.components.SyncBeatBackground
import com.example.ui.theme.ElectricPurple
import com.example.ui.theme.GraphiteDeep
import com.example.ui.theme.NeonBlue
import com.example.ui.theme.SyncCoral
import com.example.ui.theme.SyncEmerald
import com.example.ui.theme.TextSecondary

@Composable
fun JoinRoomScreen(
    discoveredRooms: List<DiscoveredRoom>,
    defaultNickname: String,
    isJoining: Boolean,
    statusBanner: String?,
    onStartDiscovery: () -> Unit,
    onStopDiscovery: () -> Unit,
    onUpdateNickname: (String) -> Unit,
    onJoinByCode: (roomCode: String, manualHostIp: String?) -> Unit,
    onJoinDirect: (hostIp: String, wsPort: Int, httpPort: Int, roomCode: String) -> Unit,
    onJoinDiscovered: (DiscoveredRoom) -> Unit,
    onClearBanner: () -> Unit,
    onBack: () -> Unit
) {
    var roomCode by remember { mutableStateOf("") }
    var guestName by remember(defaultNickname) { mutableStateOf(defaultNickname) }
    var manualHostIp by remember { mutableStateOf("") }
    var showManualIp by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onStartDiscovery()
        onDispose { onStopDiscovery() }
    }

    if (showQrScanner) {
        QrScannerDialog(
            onQrScanned = { parsed ->
                showQrScanner = false
                onUpdateNickname(guestName)
                onJoinDirect(parsed.hostIp, parsed.wsPort, parsed.httpPort, parsed.roomCode)
            },
            onDismiss = { showQrScanner = false }
        )
    }

    SyncBeatBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(modifier = Modifier.widthIn(max = 600.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("join_room_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.join_room_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (!statusBanner.isNullOrBlank()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 14.dp)
                            .clickable { onClearBanner() },
                        shape = RoundedCornerShape(16.dp),
                        color = SyncCoral.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, SyncCoral.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = statusBanner,
                            style = MaterialTheme.typography.bodyMedium,
                            color = SyncCoral,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }

                // Join with QR or 6-digit Code
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    borderColor = NeonBlue.copy(alpha = 0.45f)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        OutlinedTextField(
                            value = guestName,
                            onValueChange = {
                                guestName = it
                                onUpdateNickname(it)
                            },
                            label = { Text("Your Party Nickname") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("guest_name_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonBlue,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.2f)
                            )
                        )

                        OutlinedTextField(
                            value = roomCode,
                            onValueChange = { input ->
                                roomCode = input.filter { it.isDigit() }.take(6)
                            },
                            label = { Text("6-Digit Room Code") },
                            placeholder = { Text("e.g. 482910") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("room_code_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ElectricPurple,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.2f)
                            )
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showQrScanner = true },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp)
                                    .testTag("scan_qr_button"),
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, NeonBlue.copy(alpha = 0.6f))
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.QrCodeScanner,
                                    contentDescription = null,
                                    tint = NeonBlue,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = stringResource(R.string.scan_qr), color = NeonBlue)
                            }

                            Button(
                                onClick = {
                                    onUpdateNickname(guestName)
                                    onJoinByCode(roomCode, manualHostIp.ifBlank { null })
                                },
                                enabled = roomCode.length == 6 && !isJoining,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp)
                                    .testTag("connect_room_button"),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ElectricPurple)
                            ) {
                                if (isJoining) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = Color.White,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text("Join Beat", fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // Collapsible Manual IP Fallback for isolated hotspots
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showManualIp = !showManualIp }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Manual Host IP (if UDP discovery blocked)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                            Icon(
                                imageVector = if (showManualIp) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = null,
                                tint = TextSecondary
                            )
                        }

                        AnimatedVisibility(visible = showManualIp) {
                            OutlinedTextField(
                                value = manualHostIp,
                                onValueChange = { manualHostIp = it },
                                label = { Text("Host Wi-Fi IP Address") },
                                placeholder = { Text("192.168.43.1") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("manual_host_ip_input"),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = NeonBlue,
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.2f)
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Auto-Discovered Nearby Rooms on Wi-Fi / Hotspot
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Radar,
                        contentDescription = null,
                        tint = SyncEmerald,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Nearby Rooms on This Wi-Fi",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (discoveredRooms.isEmpty()) {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Listening for SYNCBEAT hosts on port ${SyncBeatHostServer.DISCOVERY_UDP_PORT}...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Make sure your phone is on the host's Wi-Fi or Mobile Hotspot.",
                                style = MaterialTheme.typography.labelSmall,
                                color = NeonBlue
                            )
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        discoveredRooms.forEach { room ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("discovered_room_${room.roomCode}")
                                    .clickable {
                                        onUpdateNickname(guestName)
                                        onJoinDiscovered(room)
                                    },
                                shape = RoundedCornerShape(18.dp),
                                color = GraphiteDeep,
                                border = BorderStroke(1.dp, ElectricPurple.copy(alpha = 0.45f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.MusicNote,
                                            contentDescription = null,
                                            tint = NeonBlue
                                        )
                                        Column {
                                            Text(
                                                text = room.roomName,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = "Host: ${room.hostName} • ${room.currentTrackTitle}",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = TextSecondary
                                            )
                                            Text(
                                                text = "${room.hostIp} • ${room.listenerCount} listening",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = SyncEmerald
                                            )
                                        }
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = ElectricPurple.copy(alpha = 0.22f)
                                    ) {
                                        Text(
                                            text = room.roomCode,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = Color.White,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
