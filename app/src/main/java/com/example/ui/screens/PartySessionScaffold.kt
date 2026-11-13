package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.R
import com.example.model.DeviceStatus
import com.example.model.SessionRole
import com.example.model.SongRequestStatus
import com.example.model.SyncTelemetry
import com.example.qr.QrCodeUtils
import com.example.ui.components.SyncBeatBackground
import com.example.ui.components.SyncHealthBadge
import com.example.ui.theme.ElectricPurple
import com.example.ui.theme.GraphiteDeep
import com.example.ui.theme.GraphiteSurface
import com.example.ui.theme.NeonBlue
import com.example.ui.theme.ObsidianBlack
import com.example.ui.theme.SyncCoral
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary
import com.example.viewmodel.AudioLibraryUiState
import com.example.viewmodel.SyncBeatUiState

enum class PartyTab(val label: String, val icon: ImageVector) {
    PLAYER("Player", Icons.Rounded.MusicNote),
    DEVICES("Devices", Icons.Rounded.Devices),
    QUEUE("Queue", Icons.Rounded.QueueMusic),
    CHAT("Chat", Icons.Rounded.Forum),
    SETTINGS("Settings", Icons.Rounded.Tune)
}

@Composable
fun PartySessionScaffold(
    uiState: SyncBeatUiState,
    audioLibraryState: AudioLibraryUiState,
    positionMs: Long,
    durationMs: Long,
    telemetry: SyncTelemetry,
    frequencySpectrum: com.example.audio.FrequencySpectrumFrame,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onSendReaction: (String) -> Unit,
    onKickParticipant: (String) -> Unit,
    onSelectTrack: (com.example.model.AudioTrack) -> Unit,
    onImportAudioUri: (android.net.Uri) -> Unit,
    onReconvertLibraryTrack: (String) -> Unit,
    onRemoveLibraryItem: (String) -> Unit,
    onSubmitSongRequest: (String, String, String) -> Unit,
    onDecideSongRequest: (String, Boolean) -> Unit,
    onSendChatMessage: (String) -> Unit,
    onUpdateNickname: (String) -> Unit,
    onUpdateAvatarColor: (String) -> Unit,
    onUpdateCalibrationOffset: (Int) -> Unit,
    onUpdateAutoReconnect: (Boolean) -> Unit,
    onUpdateDriftSensitivity: (Int) -> Unit,
    onToggleChatDucking: (Boolean) -> Unit,
    onUpdateChatDuckingReduction: (Int) -> Unit,
    onChatTabActiveChanged: (Boolean) -> Unit,
    onChatTypingActiveChanged: (Boolean) -> Unit,
    onLeaveParty: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(PartyTab.PLAYER) }
    var showQrModal by remember { mutableStateOf(false) }
    var showLeaveConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(selectedTab) {
        onChatTabActiveChanged(selectedTab == PartyTab.CHAT)
    }

    BackHandler {
        if (selectedTab != PartyTab.PLAYER) {
            selectedTab = PartyTab.PLAYER
        } else {
            showLeaveConfirm = true
        }
    }

    if (showLeaveConfirm) {
        AlertDialog(
            onDismissRequest = { showLeaveConfirm = false },
            containerColor = GraphiteDeep,
            title = {
                Text(
                    text = if (uiState.role == SessionRole.HOST) "End Party Session?" else "Leave Party?",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = if (uiState.role == SessionRole.HOST) {
                        "This will stop the local audio server and disconnect all listening devices."
                    } else {
                        "Disconnect from ${uiState.roomInfo.roomName} and stop synchronized playback?"
                    },
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showLeaveConfirm = false
                    onLeaveParty()
                }) {
                    Text(
                        text = if (uiState.role == SessionRole.HOST) "End Party" else "Leave",
                        color = SyncCoral,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveConfirm = false }) {
                    Text("Stay", color = NeonBlue)
                }
            }
        )
    }

    if (showQrModal) {
        RoomQrShareDialog(
            roomName = uiState.roomInfo.roomName,
            roomCode = uiState.roomInfo.roomCode,
            hostIp = uiState.roomInfo.hostIp,
            qrPayload = uiState.roomInfo.qrPayload,
            onDismiss = { showQrModal = false }
        )
    }

    val pendingReqCount = uiState.songRequests.count { it.status == SongRequestStatus.PENDING }
    val activeDeviceCount = uiState.participants.count { it.status == DeviceStatus.CONNECTED }
    val currentTrack = uiState.queue.firstOrNull { it.id == uiState.playbackState.trackId }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 640.dp

        SyncBeatBackground {
            Scaffold(
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    PartyTopBar(
                        roomName = uiState.roomInfo.roomName,
                        roomCode = uiState.roomInfo.roomCode,
                        role = uiState.role,
                        telemetry = telemetry,
                        onOpenQr = { showQrModal = true }
                    )
                },
                bottomBar = {
                    if (!useRail) {
                        NavigationBar(
                            containerColor = GraphiteDeep.copy(alpha = 0.96f),
                            windowInsets = WindowInsets.navigationBars
                        ) {
                            PartyTab.entries.forEach { tab ->
                                val badgeNum = when (tab) {
                                    PartyTab.DEVICES -> activeDeviceCount
                                    PartyTab.QUEUE -> pendingReqCount
                                    else -> 0
                                }
                                NavigationBarItem(
                                    selected = selectedTab == tab,
                                    onClick = { selectedTab = tab },
                                    modifier = Modifier.testTag("nav_tab_${tab.name.lowercase()}"),
                                    icon = {
                                        TabIconWithBadge(tab.icon, tab.label, badgeNum)
                                    },
                                    label = {
                                        Text(
                                            text = tab.label,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = NeonBlue,
                                        selectedTextColor = NeonBlue,
                                        unselectedIconColor = TextMuted,
                                        unselectedTextColor = TextMuted,
                                        indicatorColor = ElectricPurple.copy(alpha = 0.24f)
                                    )
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (useRail) {
                        NavigationRail(
                            containerColor = GraphiteDeep.copy(alpha = 0.94f),
                            modifier = Modifier.fillMaxHeight()
                        ) {
                            Spacer(modifier = Modifier.height(12.dp))
                            PartyTab.entries.forEach { tab ->
                                val badgeNum = when (tab) {
                                    PartyTab.DEVICES -> activeDeviceCount
                                    PartyTab.QUEUE -> pendingReqCount
                                    else -> 0
                                }
                                NavigationRailItem(
                                    selected = selectedTab == tab,
                                    onClick = { selectedTab = tab },
                                    modifier = Modifier.testTag("rail_tab_${tab.name.lowercase()}"),
                                    icon = { TabIconWithBadge(tab.icon, tab.label, badgeNum) },
                                    label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
                                    colors = NavigationRailItemDefaults.colors(
                                        selectedIconColor = NeonBlue,
                                        selectedTextColor = NeonBlue,
                                        unselectedIconColor = TextMuted,
                                        unselectedTextColor = TextMuted,
                                        indicatorColor = ElectricPurple.copy(alpha = 0.24f)
                                    )
                                )
                            }
                        }
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        when (selectedTab) {
                            PartyTab.PLAYER -> PlayerScreen(
                                role = uiState.role,
                                currentTrack = currentTrack,
                                playbackState = uiState.playbackState,
                                positionMs = positionMs,
                                durationMs = durationMs,
                                localVolume = uiState.localVolume,
                                effectiveVolume = uiState.effectiveVolume,
                                partyAudioSettings = uiState.partyAudioSettings,
                                isChatActive = uiState.isPartyChatActive,
                                isAudioDucked = uiState.isAudioDucked,
                                telemetry = telemetry,
                                participants = uiState.participants,
                                driftToleranceMs = uiState.settings.driftSensitivityMs,
                                frequencySpectrum = frequencySpectrum,
                                floatingReactions = uiState.floatingReactions,
                                onTogglePlayPause = onTogglePlayPause,
                                onPrevious = onPrevious,
                                onNext = onNext,
                                onSeek = onSeek,
                                onToggleShuffle = onToggleShuffle,
                                onCycleRepeat = onCycleRepeat,
                                onVolumeChange = onVolumeChange,
                                onToggleChatDucking = onToggleChatDucking,
                                onDuckingReductionChange = onUpdateChatDuckingReduction,
                                onSendReaction = onSendReaction
                            )

                            PartyTab.DEVICES -> DevicesScreen(
                                role = uiState.role,
                                participants = uiState.participants,
                                totalListenersJoined = uiState.roomInfo.totalListenersJoined,
                                sessionDurationSeconds = uiState.sessionDurationSeconds,
                                isPlaying = uiState.playbackState.isPlaying,
                                telemetry = telemetry,
                                onKickParticipant = onKickParticipant
                            )

                            PartyTab.QUEUE -> QueueScreen(
                                role = uiState.role,
                                queue = uiState.queue,
                                playbackState = uiState.playbackState,
                                songRequests = uiState.songRequests,
                                audioLibraryState = audioLibraryState,
                                onSelectTrack = onSelectTrack,
                                onImportAudioUri = onImportAudioUri,
                                onReconvertLibraryTrack = onReconvertLibraryTrack,
                                onRemoveLibraryItem = onRemoveLibraryItem,
                                onToggleShuffle = onToggleShuffle,
                                onCycleRepeat = onCycleRepeat,
                                onSubmitSongRequest = onSubmitSongRequest,
                                onDecideSongRequest = onDecideSongRequest
                            )

                            PartyTab.CHAT -> ChatScreen(
                                messages = uiState.chatMessages,
                                isAudioDucked = uiState.isAudioDucked,
                                onTypingStateChanged = onChatTypingActiveChanged,
                                onSendMessage = onSendChatMessage,
                                onSendReaction = onSendReaction
                            )

                            PartyTab.SETTINGS -> SettingsScreen(
                                role = uiState.role,
                                settings = uiState.settings,
                                partyAudioSettings = uiState.partyAudioSettings,
                                baseVolume = uiState.localVolume,
                                effectiveVolume = uiState.effectiveVolume,
                                isChatActive = uiState.isPartyChatActive,
                                isAudioDucked = uiState.isAudioDucked,
                                roomInfo = uiState.roomInfo,
                                onUpdateNickname = onUpdateNickname,
                                onUpdateAvatarColor = onUpdateAvatarColor,
                                onUpdateCalibrationOffset = onUpdateCalibrationOffset,
                                onUpdateAutoReconnect = onUpdateAutoReconnect,
                                onUpdateDriftSensitivity = onUpdateDriftSensitivity,
                                onToggleChatDucking = onToggleChatDucking,
                                onUpdateChatDuckingReduction = onUpdateChatDuckingReduction,
                                onLeaveParty = { showLeaveConfirm = true }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabIconWithBadge(icon: ImageVector, label: String, badgeCount: Int) {
    if (badgeCount > 0) {
        BadgedBox(
            badge = {
                Badge(containerColor = ElectricPurple, contentColor = Color.White) {
                    Text(text = badgeCount.toString())
                }
            }
        ) {
            Icon(imageVector = icon, contentDescription = label)
        }
    } else {
        Icon(imageVector = icon, contentDescription = label)
    }
}

@Composable
private fun PartyTopBar(
    roomName: String,
    roomCode: String,
    role: SessionRole,
    telemetry: SyncTelemetry,
    onOpenQr: () -> Unit
) {
    val localAssessment = com.example.ui.components.assessLocalTelemetryConnection(role, telemetry)
    val signalColor = com.example.ui.components.tierColor(localAssessment.tier, role == SessionRole.HOST)

    Surface(
        color = ObsidianBlack.copy(alpha = 0.72f),
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = roomName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SyncHealthBadge(
                        health = telemetry.syncHealth,
                        driftMs = telemetry.driftMs,
                        rttMs = telemetry.rttMs
                    )
                    com.example.ui.components.LatencySignalBars(
                        activeBars = localAssessment.signalBars,
                        activeColor = signalColor,
                        pulsing = localAssessment.tier == com.example.ui.components.ConnectionQualityTier.RECONNECTING,
                        width = 18.dp,
                        height = 13.dp
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Surface(
                modifier = Modifier
                    .testTag("room_code_qr_button")
                    .clickable(onClick = onOpenQr),
                shape = RoundedCornerShape(14.dp),
                color = GraphiteSurface,
                border = BorderStroke(1.dp, ElectricPurple.copy(alpha = 0.55f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.QrCode2,
                        contentDescription = stringResource(R.string.cd_qr_code),
                        tint = NeonBlue,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = roomCode,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun RoomQrShareDialog(
    roomName: String,
    roomCode: String,
    hostIp: String,
    qrPayload: String,
    onDismiss: () -> Unit
) {
    val qrBitmap = remember(qrPayload) {
        QrCodeUtils.generateQrBitmap(qrPayload, 520)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = GraphiteDeep,
            border = BorderStroke(1.dp, ElectricPurple.copy(alpha = 0.5f))
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = roomName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.cd_close)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = roomCode.chunked(3).joinToString(" "),
                    style = MaterialTheme.typography.displayLarge.copy(letterSpacing = 4.sp),
                    color = NeonBlue,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (qrBitmap != null) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color.White
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.cd_qr_code),
                            modifier = Modifier
                                .size(220.dp)
                                .padding(12.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "Wi-Fi / Hotspot Host: $hostIp",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
            }
        }
    }
}
