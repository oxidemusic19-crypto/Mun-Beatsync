package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.ui.screens.CreateRoomScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.JoinRoomScreen
import com.example.ui.screens.PartySessionScaffold
import com.example.ui.screens.SplashScreen
import com.example.ui.theme.SyncBeatTheme
import com.example.viewmodel.AudioLibraryViewModel
import com.example.viewmodel.SyncBeatViewModel

object SyncBeatRoutes {
    const val SPLASH = "splash"
    const val HOME = "home"
    const val CREATE_ROOM = "create_room"
    const val JOIN_ROOM = "join_room"
    const val PARTY_SESSION = "party_session"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SyncBeatTheme {
                SyncBeatApp()
            }
        }
    }
}

@Composable
fun SyncBeatApp(
    viewModel: SyncBeatViewModel = viewModel(),
    audioLibraryViewModel: AudioLibraryViewModel = viewModel()
) {
    val navController = rememberNavController()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val audioLibraryState by audioLibraryViewModel.uiState.collectAsStateWithLifecycle()
    val positionMs by viewModel.positionMs.collectAsStateWithLifecycle()
    val durationMs by viewModel.durationMs.collectAsStateWithLifecycle()
    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
    val frequencySpectrum by viewModel.frequencySpectrum.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val notificationPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    // Automatically enter PartySession when connected or hosting
    LaunchedEffect(uiState.isInParty) {
        val currentRoute = navController.currentBackStackEntry?.destination?.route
        if (uiState.isInParty && currentRoute != SyncBeatRoutes.PARTY_SESSION) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            navController.navigate(SyncBeatRoutes.PARTY_SESSION) {
                popUpTo(SyncBeatRoutes.HOME) { inclusive = false }
                launchSingleTop = true
            }
        } else if (!uiState.isInParty && currentRoute == SyncBeatRoutes.PARTY_SESSION) {
            navController.navigate(SyncBeatRoutes.HOME) {
                popUpTo(SyncBeatRoutes.HOME) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = SyncBeatRoutes.SPLASH
    ) {
        composable(SyncBeatRoutes.SPLASH) {
            SplashScreen(
                isAppReady = uiState.isReady,
                onFinished = {
                    navController.navigate(SyncBeatRoutes.HOME) {
                        popUpTo(SyncBeatRoutes.SPLASH) { inclusive = true }
                    }
                }
            )
        }

        composable(SyncBeatRoutes.HOME) {
            HomeScreen(
                localIp = uiState.localHostIp,
                nickname = uiState.settings.nickname,
                trackCount = uiState.queue.size,
                onHostPartyClick = {
                    navController.navigate(SyncBeatRoutes.CREATE_ROOM)
                },
                onJoinPartyClick = {
                    navController.navigate(SyncBeatRoutes.JOIN_ROOM)
                }
            )
        }

        composable(SyncBeatRoutes.CREATE_ROOM) {
            CreateRoomScreen(
                pendingRoomCode = uiState.pendingRoomCode,
                localHostIp = uiState.localHostIp,
                defaultNickname = uiState.settings.nickname,
                onRefreshNetwork = { viewModel.refreshLocalNetworkInfo() },
                onRegenerateCode = { viewModel.regeneratePendingRoomCode() },
                onUpdateNickname = { viewModel.updateNickname(it) },
                onLaunchParty = { roomName ->
                    viewModel.startHostingParty(roomName)
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(SyncBeatRoutes.JOIN_ROOM) {
            JoinRoomScreen(
                discoveredRooms = uiState.discoveredRooms,
                defaultNickname = uiState.settings.nickname,
                isJoining = uiState.isJoining,
                statusBanner = uiState.statusBanner,
                onStartDiscovery = { viewModel.startRoomDiscovery() },
                onStopDiscovery = { viewModel.stopRoomDiscovery() },
                onUpdateNickname = { viewModel.updateNickname(it) },
                onJoinByCode = { code, manualIp ->
                    viewModel.joinRoomByCode(code, manualIp)
                },
                onJoinDirect = { hostIp, wsPort, httpPort, code ->
                    viewModel.joinRoomDirect(hostIp, wsPort, httpPort, code)
                },
                onJoinDiscovered = { room ->
                    viewModel.joinRoomByDiscovered(room)
                },
                onClearBanner = { viewModel.clearStatusBanner() },
                onBack = { navController.popBackStack() }
            )
        }

        composable(SyncBeatRoutes.PARTY_SESSION) {
            PartySessionScaffold(
                uiState = uiState,
                audioLibraryState = audioLibraryState,
                positionMs = positionMs,
                durationMs = durationMs,
                telemetry = telemetry,
                frequencySpectrum = frequencySpectrum,
                onTogglePlayPause = { viewModel.togglePlayPause() },
                onPrevious = { viewModel.previousTrack() },
                onNext = { viewModel.nextTrack() },
                onSeek = { viewModel.seekTo(it) },
                onToggleShuffle = { viewModel.toggleShuffle() },
                onCycleRepeat = { viewModel.cycleRepeatMode() },
                onVolumeChange = { viewModel.setLocalVolume(it) },
                onSendReaction = { viewModel.sendReaction(it) },
                onKickParticipant = { viewModel.kickParticipant(it) },
                onSelectTrack = { viewModel.selectTrack(it, autoPlay = true) },
                onImportAudioUri = { uri ->
                    audioLibraryViewModel.importPickedAudio(uri) { readyTrack ->
                        viewModel.addImportedTrack(readyTrack)
                    }
                },
                onReconvertLibraryTrack = { trackId ->
                    audioLibraryViewModel.convertTrackToPcmStream(trackId) { readyTrack ->
                        viewModel.addImportedTrack(readyTrack)
                    }
                },
                onRemoveLibraryItem = { trackId ->
                    audioLibraryViewModel.removeLibraryItem(trackId)
                },
                onSubmitSongRequest = { title, artist, note ->
                    viewModel.submitSongRequest(title, artist, note)
                },
                onDecideSongRequest = { reqId, approve ->
                    viewModel.decideSongRequest(reqId, approve)
                },
                onSendChatMessage = { viewModel.sendChatMessage(it) },
                onUpdateNickname = { viewModel.updateNickname(it) },
                onUpdateAvatarColor = { viewModel.updateAvatarColor(it) },
                onUpdateCalibrationOffset = { viewModel.updateCalibrationOffset(it) },
                onUpdateAutoReconnect = { viewModel.updateAutoReconnect(it) },
                onUpdateDriftSensitivity = { viewModel.updateDriftSensitivity(it) },
                onToggleChatDucking = { viewModel.updateChatDuckingEnabled(it) },
                onUpdateChatDuckingReduction = { viewModel.updateChatDuckingReductionPercent(it) },
                onChatTabActiveChanged = { viewModel.setChatTabActive(it) },
                onChatTypingActiveChanged = { viewModel.setChatTypingActive(it) },
                onLeaveParty = { viewModel.leaveParty() }
            )
        }
    }
}
