package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.ChatMessage
import com.example.ui.components.ParticipantAvatar
import com.example.ui.theme.ElectricPurple
import com.example.ui.theme.GraphiteDeep
import com.example.ui.theme.GraphiteSurface
import com.example.ui.theme.NeonBlue
import com.example.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    isAudioDucked: Boolean = false,
    onTypingStateChanged: (Boolean) -> Unit = {},
    onSendMessage: (String) -> Unit,
    onSendReaction: (String) -> Unit
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(input.isNotBlank()) {
        onTypingStateChanged(input.isNotBlank())
    }

    DisposableEffect(Unit) {
        onDispose {
            onTypingStateChanged(false)
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxSize()
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
                        imageVector = Icons.Rounded.Forum,
                        contentDescription = null,
                        tint = NeonBlue
                    )
                    Text(
                        text = "Party Chat",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    if (isAudioDucked) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = NeonBlue.copy(alpha = 0.14f),
                            border = BorderStroke(0.75.dp, NeonBlue.copy(alpha = 0.45f))
                        ) {
                            Text(
                                text = "Ducked",
                                style = MaterialTheme.typography.labelSmall,
                                color = NeonBlue,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("🔥", "❤️", "😂", "🎉").forEach { emoji ->
                        Surface(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .clickable { onSendReaction(emoji) },
                            shape = CircleShape,
                            color = GraphiteDeep,
                            border = BorderStroke(1.dp, ElectricPurple.copy(alpha = 0.35f))
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(text = emoji, fontSize = 16.sp)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    if (msg.isSystem) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = GraphiteDeep.copy(alpha = 0.85f),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                            ) {
                                Text(
                                    text = msg.text,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            ParticipantAvatar(
                                name = msg.senderName,
                                colorHex = msg.senderColorHex,
                                size = 36.dp
                            )
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = GraphiteSurface.copy(alpha = 0.85f),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = msg.senderName,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = NeonBlue
                                        )
                                        Text(
                                            text = formatChatTime(msg.timestampMs),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextSecondary
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = msg.text,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Drop a message to the party...") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("chat_message_input"),
                    shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ElectricPurple,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.18f)
                    )
                )
                Surface(
                    shape = CircleShape,
                    color = ElectricPurple,
                    modifier = Modifier.size(50.dp)
                ) {
                    IconButton(
                        onClick = {
                            if (input.isNotBlank()) {
                                onSendMessage(input)
                                input = ""
                            }
                        },
                        modifier = Modifier.testTag("send_chat_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Send,
                            contentDescription = stringResource(R.string.cd_send_message),
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}

private fun formatChatTime(epochMs: Long): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))
}
