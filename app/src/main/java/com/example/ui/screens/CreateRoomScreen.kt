package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.network.SyncBeatHostServer
import com.example.qr.QrCodeUtils
import com.example.ui.components.GlassCard
import com.example.ui.components.SyncBeatBackground
import com.example.ui.theme.ElectricPurple
import com.example.ui.theme.GraphiteDeep
import com.example.ui.theme.NeonBlue
import com.example.ui.theme.TextSecondary

@Composable
fun CreateRoomScreen(
    pendingRoomCode: String,
    localHostIp: String,
    defaultNickname: String,
    onRefreshNetwork: () -> Unit,
    onRegenerateCode: () -> Unit,
    onUpdateNickname: (String) -> Unit,
    onLaunchParty: (roomName: String) -> Unit,
    onBack: () -> Unit
) {
    var roomName by remember { mutableStateOf("Midnight Bass Party") }
    var djName by remember(defaultNickname) { mutableStateOf(defaultNickname) }

    LaunchedEffect(Unit) {
        onRefreshNetwork()
    }

    val qrPayload = remember(localHostIp, pendingRoomCode) {
        QrCodeUtils.buildRoomPayload(
            hostIp = localHostIp,
            wsPort = SyncBeatHostServer.DEFAULT_WS_PORT,
            httpPort = SyncBeatHostServer.DEFAULT_HTTP_PORT,
            roomCode = pendingRoomCode
        )
    }
    val qrBitmap = remember(qrPayload) {
        QrCodeUtils.generateQrBitmap(qrPayload, 480)
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
                        modifier = Modifier.testTag("create_room_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.create_room_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Room Code & QR Code Preview Card
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    borderColor = ElectricPurple.copy(alpha = 0.5f)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "ROOM CODE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NeonBlue
                                )
                                Text(
                                    text = pendingRoomCode.chunked(3).joinToString(" "),
                                    style = MaterialTheme.typography.displayLarge.copy(
                                        letterSpacing = 4.sp
                                    ),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            IconButton(
                                onClick = onRegenerateCode,
                                modifier = Modifier.testTag("regenerate_code_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = "Generate new room code",
                                    tint = NeonBlue
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        if (qrBitmap != null) {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = Color.White,
                                border = BorderStroke(2.dp, ElectricPurple.copy(alpha = 0.4f))
                            ) {
                                Image(
                                    bitmap = qrBitmap.asImageBitmap(),
                                    contentDescription = stringResource(R.string.cd_qr_code),
                                    modifier = Modifier
                                        .size(196.dp)
                                        .padding(10.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = GraphiteDeep,
                            border = BorderStroke(1.dp, NeonBlue.copy(alpha = 0.35f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.WifiTethering,
                                    contentDescription = null,
                                    tint = NeonBlue,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Hotspot / Wi-Fi IP: $localHostIp",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = NeonBlue
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Room Setup Fields
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        OutlinedTextField(
                            value = roomName,
                            onValueChange = { roomName = it },
                            label = { Text("Party Room Name") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("room_name_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ElectricPurple,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.2f)
                            )
                        )
                        OutlinedTextField(
                            value = djName,
                            onValueChange = {
                                djName = it
                                onUpdateNickname(it)
                            },
                            label = { Text("Host DJ Name") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("host_name_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonBlue,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.2f)
                            )
                        )
                        Text(
                            text = "Connect all guest phones to the same Wi-Fi network or this phone's Mobile Hotspot. Guests can scan this QR or enter the 6-digit code.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(22.dp))

                Button(
                    onClick = {
                        onUpdateNickname(djName)
                        onLaunchParty(roomName)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("launch_party_button"),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(18.dp))
                            .background(Brush.linearGradient(listOf(ElectricPurple, NeonBlue))),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                tint = Color.White
                            )
                            Text(
                                text = "Launch Host Server & Party",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
