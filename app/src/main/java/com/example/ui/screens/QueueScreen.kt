package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.AudioTrack
import com.example.model.PlaybackState
import com.example.model.RepeatMode
import com.example.model.SessionRole
import com.example.model.SongRequest
import com.example.model.SongRequestStatus
import com.example.ui.components.GlassCard
import com.example.ui.components.artworkDrawableForKey
import com.example.ui.components.formatDuration
import com.example.ui.theme.ElectricPurple
import com.example.ui.theme.GraphiteDeep
import com.example.ui.theme.NeonBlue
import com.example.ui.theme.SyncAmber
import com.example.ui.theme.SyncCoral
import com.example.ui.theme.SyncEmerald
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary
import com.example.viewmodel.AudioLibraryUiState
import com.example.viewmodel.PcmConversionStatus
import kotlin.math.roundToInt

@Composable
fun QueueScreen(
    role: SessionRole,
    queue: List<AudioTrack>,
    playbackState: PlaybackState,
    songRequests: List<SongRequest>,
    audioLibraryState: AudioLibraryUiState,
    onSelectTrack: (AudioTrack) -> Unit,
    onImportAudioUri: (Uri) -> Unit,
    onReconvertLibraryTrack: (String) -> Unit,
    onRemoveLibraryItem: (String) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onSubmitSongRequest: (title: String, artist: String, note: String) -> Unit,
    onDecideSongRequest: (requestId: String, approve: Boolean) -> Unit
) {
    val isHost = role == SessionRole.HOST
    var reqTitle by remember { mutableStateOf("") }
    var reqArtist by remember { mutableStateOf("") }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { onImportAudioUri(it) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Column(modifier = Modifier.widthIn(max = 600.dp)) {
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
                            imageVector = Icons.Rounded.QueueMusic,
                            contentDescription = null,
                            tint = ElectricPurple
                        )
                        Text(
                            text = "Party Queue (${queue.size})",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (isHost) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(
                                onClick = onToggleShuffle,
                                modifier = Modifier.testTag("queue_shuffle_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Shuffle,
                                    contentDescription = stringResource(R.string.cd_shuffle),
                                    tint = if (playbackState.shuffle) NeonBlue else TextMuted
                                )
                            }
                            IconButton(
                                onClick = onCycleRepeat,
                                modifier = Modifier.testTag("queue_repeat_button")
                            ) {
                                Icon(
                                    imageVector = if (playbackState.repeatMode == RepeatMode.ONE) {
                                        Icons.Rounded.RepeatOne
                                    } else {
                                        Icons.Rounded.Repeat
                                    },
                                    contentDescription = stringResource(R.string.cd_repeat),
                                    tint = if (playbackState.repeatMode != RepeatMode.OFF) ElectricPurple else TextMuted
                                )
                            }
                        }
                    }
                }

                if (isHost) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = { audioPickerLauncher.launch(arrayOf("audio/*")) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("import_audio_button"),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricPurple)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.LibraryMusic,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.import_audio),
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    if (audioLibraryState.items.isNotEmpty() || audioLibraryState.isImporting) {
                        Spacer(modifier = Modifier.height(12.dp))
                        GlassCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("audio_library_pcm_card"),
                            borderColor = NeonBlue.copy(alpha = 0.38f)
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "Local Audio PCM Streams (${audioLibraryState.items.size})",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = NeonBlue
                                )
                                audioLibraryState.statusMessage?.let { msg ->
                                    Text(
                                        text = msg,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                }
                                audioLibraryState.items.forEach { libItem ->
                                    val statusColor = when (libItem.status) {
                                        PcmConversionStatus.READY -> SyncEmerald
                                        PcmConversionStatus.CONVERTING -> NeonBlue
                                        PcmConversionStatus.ERROR -> SyncCoral
                                        PcmConversionStatus.IDLE -> SyncAmber
                                    }
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = libItem.track.title,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = when (libItem.status) {
                                                        PcmConversionStatus.READY ->
                                                            "44.1kHz Stereo PCM • ${libItem.pcmFrameCount} WebRTC frames (${libItem.pcmBytesGenerated / 1024L}KB)"
                                                        PcmConversionStatus.CONVERTING ->
                                                            "Converting to raw PCM (${(libItem.conversionProgress * 100).roundToInt()}%)"
                                                        PcmConversionStatus.ERROR ->
                                                            libItem.errorMessage ?: "PCM conversion failed"
                                                        PcmConversionStatus.IDLE -> "Pending PCM conversion"
                                                    },
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = statusColor
                                                )
                                            }
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (libItem.status == PcmConversionStatus.ERROR || libItem.status == PcmConversionStatus.IDLE) {
                                                    IconButton(onClick = { onReconvertLibraryTrack(libItem.track.id) }) {
                                                        Icon(
                                                            imageVector = Icons.Rounded.Refresh,
                                                            contentDescription = "Convert ${libItem.track.title} to PCM",
                                                            tint = NeonBlue
                                                        )
                                                    }
                                                }
                                                IconButton(onClick = { onRemoveLibraryItem(libItem.track.id) }) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.DeleteOutline,
                                                        contentDescription = "Remove ${libItem.track.title}",
                                                        tint = TextMuted
                                                    )
                                                }
                                            }
                                        }
                                        if (libItem.status == PcmConversionStatus.CONVERTING) {
                                            LinearProgressIndicator(
                                                progress = { libItem.conversionProgress.coerceIn(0f, 1f) },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(4.dp)
                                                    .clip(RoundedCornerShape(2.dp)),
                                                color = NeonBlue,
                                                trackColor = Color.White.copy(alpha = 0.12f)
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

        items(queue, key = { it.id }) { track ->
            val isActive = track.id == playbackState.trackId
            GlassCard(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .testTag("queue_track_${track.id}")
                    .let { mod ->
                        if (isHost) mod.clickable { onSelectTrack(track) } else mod
                    },
                borderColor = if (isActive) NeonBlue.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.12f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Image(
                        painter = painterResource(id = artworkDrawableForKey(track.artworkKey)),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (isActive) NeonBlue else Color.White,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${track.artist} • ${formatDuration(track.durationMs)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (isActive) {
                        Icon(
                            imageVector = Icons.Rounded.GraphicEq,
                            contentDescription = "Now playing",
                            tint = NeonBlue
                        )
                    }
                }
            }
        }

        // Song Request Section
        item {
            Column(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .padding(top = 10.dp)
            ) {
                Text(
                    text = "Song Requests",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(10.dp))

                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = reqTitle,
                            onValueChange = { reqTitle = it },
                            label = { Text("Song Title") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("request_song_title_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonBlue,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.2f)
                            )
                        )
                        OutlinedTextField(
                            value = reqArtist,
                            onValueChange = { reqArtist = it },
                            label = { Text("Artist (optional)") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("request_song_artist_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ElectricPurple,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.2f)
                            )
                        )
                        OutlinedButton(
                            onClick = {
                                if (reqTitle.isNotBlank()) {
                                    onSubmitSongRequest(reqTitle, reqArtist, "")
                                    reqTitle = ""
                                    reqArtist = ""
                                }
                            },
                            enabled = reqTitle.isNotBlank(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("submit_song_request_button"),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, NeonBlue.copy(alpha = 0.6f))
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = null,
                                tint = NeonBlue
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Send Request to Host", color = NeonBlue)
                        }
                    }
                }
            }
        }

        items(songRequests, key = { it.id }) { req ->
            val statusColor = when (req.status) {
                SongRequestStatus.PENDING -> SyncAmber
                SongRequestStatus.APPROVED -> SyncEmerald
                SongRequestStatus.REJECTED -> SyncCoral
            }

            GlassCard(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = req.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${req.artist} • Requested by ${req.requesterName}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }

                    if (isHost && req.status == SongRequestStatus.PENDING) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(
                                onClick = { onDecideSongRequest(req.id, true) },
                                modifier = Modifier.testTag("approve_request_${req.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = "Approve request",
                                    tint = SyncEmerald
                                )
                            }
                            IconButton(
                                onClick = { onDecideSongRequest(req.id, false) },
                                modifier = Modifier.testTag("reject_request_${req.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Reject request",
                                    tint = SyncCoral
                                )
                            }
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = statusColor.copy(alpha = 0.16f)
                        ) {
                            Text(
                                text = req.status.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = statusColor,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
