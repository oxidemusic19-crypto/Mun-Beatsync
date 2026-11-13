package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.BroadcastOnPersonal
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.components.GlassCard
import com.example.ui.components.SyncBeatBackground
import com.example.ui.components.SyncBeatLogoMark
import com.example.ui.theme.ElectricIndigo
import com.example.ui.theme.ElectricPurple
import com.example.ui.theme.GraphiteDeep
import com.example.ui.theme.NeonBlue
import com.example.ui.theme.ObsidianBlack
import com.example.ui.theme.SyncEmerald
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VividViolet

@Composable
fun HomeScreen(
    localIp: String,
    nickname: String,
    trackCount: Int,
    onHostPartyClick: () -> Unit,
    onJoinPartyClick: () -> Unit
) {
    SyncBeatBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(modifier = Modifier.widthIn(max = 600.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SyncBeatLogoMark(size = 44.dp, animated = true)
                        Column {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleLarge,
                                letterSpacing = 1.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.tagline),
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(50),
                        color = SyncEmerald.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, SyncEmerald.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Wifi,
                                contentDescription = null,
                                tint = SyncEmerald,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = localIp,
                                style = MaterialTheme.typography.labelSmall,
                                color = SyncEmerald
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(22.dp))

                // Hero Card with generated artwork
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .clip(RoundedCornerShape(26.dp))
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_party_hero),
                        contentDescription = "SYNCBEAT Party Wave",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        ObsidianBlack.copy(alpha = 0.20f),
                                        ObsidianBlack.copy(alpha = 0.88f)
                                    )
                                )
                            )
                    )
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(20.dp)
                    ) {
                        Text(
                            text = "WELCOME, ${nickname.uppercase()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = NeonBlue
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Turn every phone into one wireless sound system.",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(22.dp))

                // Primary Actions
                ModeActionCard(
                    title = stringResource(R.string.host_party),
                    subtitle = "Create a room, stream MP3 or WAV over Wi-Fi hotspot, and control playback across all phones.",
                    badge = "LOCAL HTTP + WEBSOCKET SERVER",
                    icon = Icons.Rounded.BroadcastOnPersonal,
                    gradientColors = listOf(ElectricPurple, ElectricIndigo),
                    testTag = "host_party_button",
                    onClick = onHostPartyClick
                )

                Spacer(modifier = Modifier.height(16.dp))

                ModeActionCard(
                    title = stringResource(R.string.join_party),
                    subtitle = "Scan a host QR code, pick a nearby hotspot room, or enter the 6-digit code to lock onto the beat.",
                    badge = "NTP CLOCK SYNC + DRIFT LOCK",
                    icon = Icons.Rounded.GroupAdd,
                    gradientColors = listOf(NeonBlue, VividViolet),
                    testTag = "join_party_button",
                    onClick = onJoinPartyClick
                )

                Spacer(modifier = Modifier.height(22.dp))

                // Technical Architecture Transparency Card
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(ElectricPurple.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.GraphicEq,
                                contentDescription = null,
                                tint = ElectricPurple
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Hotspot-Ready Offline Sync",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "$trackCount studio party tracks loaded. Import any MP3/WAV from your device—zero internet needed.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeActionCard(
    title: String,
    subtitle: String,
    badge: String,
    icon: ImageVector,
    gradientColors: List<Color>,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .testTag(testTag)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = GraphiteDeep,
        border = BorderStroke(1.2.dp, gradientColors.first().copy(alpha = 0.45f))
    ) {
        Box(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            gradientColors.first().copy(alpha = 0.22f),
                            gradientColors.last().copy(alpha = 0.08f)
                        )
                    )
                )
                .padding(20.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Brush.linearGradient(gradientColors)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Color.White.copy(alpha = 0.08f)
                    ) {
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = gradientColors.first(),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                            contentDescription = null,
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}
