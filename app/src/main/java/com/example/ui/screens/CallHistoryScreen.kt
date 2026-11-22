package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallMissed
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.CallHistoryFilter
import com.example.data.model.CallSession
import com.example.data.model.CallStatus
import com.example.data.model.CallType
import com.example.ui.theme.CallCoral
import com.example.ui.theme.EmeraldSignal
import com.example.ui.theme.MonoHandleStyle
import com.example.ui.theme.TealElectric
import com.example.ui.viewmodel.UiState
import com.google.firebase.Timestamp
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun CallHistoryScreen(
    currentUserId: String,
    historyState: UiState<List<CallSession>>,
    selectedFilter: CallHistoryFilter,
    onSelectFilter: (CallHistoryFilter) -> Unit,
    onRedial: (CallSession, CallType) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Recent Calls",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CallHistoryFilter.entries.forEach { filter ->
                FilterChip(
                    selected = selectedFilter == filter,
                    onClick = { onSelectFilter(filter) },
                    label = { Text(filter.label) },
                    modifier = Modifier.testTag("history_filter_${filter.name.lowercase()}")
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        when (historyState) {
            is UiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is UiState.Error -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = historyState.message,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
            is UiState.Success -> {
                val calls = historyState.data
                if (calls.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.History,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No ${selectedFilter.label.lowercase()} calls yet",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Your incoming, outgoing, and missed audio/video calls will appear here with one-tap redial.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 320.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(calls, key = { it.callId }) { session ->
                            CallHistoryCard(
                                currentUserId = currentUserId,
                                session = session,
                                onRedialAudio = { onRedial(session, CallType.AUDIO) },
                                onRedialVideo = { onRedial(session, CallType.VIDEO) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CallHistoryCard(
    currentUserId: String,
    session: CallSession,
    onRedialAudio: () -> Unit,
    onRedialVideo: () -> Unit
) {
    val isOutgoing = session.isOutgoingFor(currentUserId)
    val status = session.parsedStatus()
    val isMissed = !isOutgoing && (status == CallStatus.MISSED || status == CallStatus.DECLINED)

    val (directionIcon, directionColor, statusLabel) = when {
        isMissed -> Triple(Icons.AutoMirrored.Rounded.CallMissed, CallCoral, "Missed")
        isOutgoing && status == CallStatus.DECLINED -> Triple(Icons.AutoMirrored.Rounded.CallMade, CallCoral, "Declined")
        isOutgoing && status == CallStatus.MISSED -> Triple(Icons.AutoMirrored.Rounded.CallMade, MaterialTheme.colorScheme.onSurfaceVariant, "No answer")
        isOutgoing -> Triple(Icons.AutoMirrored.Rounded.CallMade, TealElectric, "Outgoing")
        else -> Triple(Icons.AutoMirrored.Rounded.CallReceived, EmeraldSignal, "Incoming")
    }

    val durationText = if (session.durationSeconds > 0L) {
        formatDuration(session.durationSeconds)
    } else {
        statusLabel
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("call_history_card_${session.callId}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(directionColor.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = directionIcon,
                        contentDescription = statusLabel,
                        tint = directionColor,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = session.peerDisplayName(currentUserId),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isMissed) CallCoral else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "@${session.peerUsername(currentUserId)} • ${session.peerPhone(currentUserId)}",
                        style = MonoHandleStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (session.parsedType() == CallType.VIDEO) {
                                Icons.Rounded.Videocam
                            } else {
                                Icons.Rounded.Call
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "${formatTimestamp(session.createdAt)} • $durationText",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalIconButton(
                    onClick = onRedialAudio,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("redial_audio_${session.callId}")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Call,
                        contentDescription = "Redial audio"
                    )
                }
                FilledTonalIconButton(
                    onClick = onRedialVideo,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("redial_video_${session.callId}")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Videocam,
                        contentDescription = "Redial video"
                    )
                }
            }
        }
    }
}

fun formatDuration(totalSeconds: Long): String {
    val mins = totalSeconds / 60L
    val secs = totalSeconds % 60L
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}

private fun formatTimestamp(ts: Timestamp?): String {
    if (ts == null) return "Just now"
    val fmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    return fmt.format(ts.toDate())
}
