package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.model.CallSession
import com.example.data.model.CallStatus
import com.example.ui.theme.CallCoral
import com.example.ui.theme.EmeraldSignal
import com.example.ui.theme.MidnightBg
import com.example.ui.theme.MidnightSurfaceElevated
import com.example.ui.theme.MonoHandleStyle
import com.example.ui.theme.MonoTimerStyle
import com.example.ui.theme.TealElectric
import com.example.webrtc.WebRtcConnectionState
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import kotlin.math.roundToInt

@Composable
fun ActiveCallScreen(
    currentUserId: String,
    session: CallSession,
    eglBaseContext: EglBase.Context,
    localVideoTrack: VideoTrack?,
    remoteVideoTrack: VideoTrack?,
    isMuted: Boolean,
    isCameraEnabled: Boolean,
    isSpeakerOn: Boolean,
    isFrontCamera: Boolean,
    connectionState: WebRtcConnectionState,
    durationSeconds: Long,
    onToggleMute: () -> Unit,
    onToggleCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onEndCall: () -> Unit
) {
    BackHandler {
        // Keep active call visible; end explicitly via End Call button
    }

    val status = session.parsedStatus()
    val isRemoteVideoVisible = remoteVideoTrack != null && session.isRemoteVideoEnabled(currentUserId)
    val peerName = session.peerDisplayName(currentUserId)
    val peerHandle = "@${session.peerUsername(currentUserId)}"
    val peerPhone = session.peerPhone(currentUserId)

    val statusLabel = when {
        status == CallStatus.RINGING -> "Ringing..."
        connectionState == WebRtcConnectionState.CONNECTED -> "Connected • ${formatDuration(durationSeconds)}"
        status == CallStatus.ACCEPTED -> "Connecting... • ${formatDuration(durationSeconds)}"
        else -> "Connecting..."
    }

    var pipOffsetX by remember { mutableFloatStateOf(0f) }
    var pipOffsetY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MidnightBg)
            .testTag("active_call_screen")
    ) {
        // 1. Remote Video Stream or Audio-Mode Stage
        if (isRemoteVideoVisible) {
            WebRtcVideoRenderer(
                videoTrack = remoteVideoTrack,
                eglBaseContext = eglBaseContext,
                mirror = false,
                isOverlay = false,
                modifier = Modifier.fillMaxSize()
            )
            // Subtle top & bottom gradient scrims for legibility
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.55f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.7f)
                            )
                        )
                    )
            )
        } else {
            AudioCallStage(
                peerName = peerName,
                peerHandle = peerHandle,
                peerPhone = peerPhone,
                isConnected = connectionState == WebRtcConnectionState.CONNECTED || status == CallStatus.ACCEPTED
            )
        }

        // 2. Top Call Header + Live MM:SS Timer
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .safeDrawingPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .align(Alignment.TopCenter)
        ) {
            Surface(
                color = MidnightSurfaceElevated.copy(alpha = 0.82f),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = peerName,
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "$peerHandle • $peerPhone",
                        style = MonoHandleStyle,
                        color = TealElectric
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = statusLabel,
                        style = MonoTimerStyle,
                        color = if (status == CallStatus.RINGING) Color(0xFFFCD34D) else EmeraldSignal,
                        modifier = Modifier.testTag("active_call_timer")
                    )
                }
            }
        }

        // 3. Draggable Picture-in-Picture (PiP) Local Camera Preview
        if (isCameraEnabled && localVideoTrack != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .safeDrawingPadding()
                    .padding(top = 112.dp, end = 16.dp)
                    .offset { IntOffset(pipOffsetX.roundToInt(), pipOffsetY.roundToInt()) }
                    .size(width = 118.dp, height = 162.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .border(2.dp, EmeraldSignal.copy(alpha = 0.85f), RoundedCornerShape(18.dp))
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            pipOffsetX += dragAmount.x
                            pipOffsetY += dragAmount.y
                        }
                    }
                    .testTag("local_pip_preview")
            ) {
                WebRtcVideoRenderer(
                    videoTrack = localVideoTrack,
                    eglBaseContext = eglBaseContext,
                    mirror = isFrontCamera,
                    isOverlay = true,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // 4. Bottom Active Call Controls Dock
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .safeDrawingPadding()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = MidnightSurfaceElevated.copy(alpha = 0.90f),
                shape = RoundedCornerShape(28.dp),
                tonalElevation = 4.dp,
                modifier = Modifier.widthIn(max = 520.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CallControlButton(
                        icon = if (isMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                        label = if (isMuted) "Unmute" else "Mute",
                        active = isMuted,
                        testTag = "toggle_mute_button",
                        onClick = onToggleMute
                    )

                    CallControlButton(
                        icon = if (isCameraEnabled) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff,
                        label = if (isCameraEnabled) "Cam On" else "Cam Off",
                        active = isCameraEnabled,
                        testTag = "toggle_camera_button",
                        onClick = onToggleCamera
                    )

                    CallControlButton(
                        icon = Icons.Rounded.Cameraswitch,
                        label = "Flip",
                        active = false,
                        enabled = isCameraEnabled,
                        testTag = "switch_camera_button",
                        onClick = onSwitchCamera
                    )

                    CallControlButton(
                        icon = if (isSpeakerOn) Icons.AutoMirrored.Rounded.VolumeUp else Icons.Rounded.Hearing,
                        label = if (isSpeakerOn) "Speaker" else "Earpiece",
                        active = isSpeakerOn,
                        testTag = "toggle_speaker_button",
                        onClick = onToggleSpeaker
                    )

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledIconButton(
                            onClick = onEndCall,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = CallCoral,
                                contentColor = Color.White
                            ),
                            modifier = Modifier
                                .size(56.dp)
                                .testTag("end_call_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CallEnd,
                                contentDescription = "End Call",
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "End",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFFCA5A5)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CallControlButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    enabled: Boolean = true,
    testTag: String,
    onClick: () -> Unit
) {
    val container = when {
        !enabled -> Color.White.copy(alpha = 0.08f)
        active -> EmeraldSignal
        else -> Color.White.copy(alpha = 0.14f)
    }
    val content = when {
        !enabled -> Color.White.copy(alpha = 0.35f)
        active -> Color(0xFF022C22)
        else -> Color.White
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick,
            enabled = enabled,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = container,
                contentColor = content,
                disabledContainerColor = container,
                disabledContentColor = content
            ),
            modifier = Modifier
                .size(52.dp)
                .testTag(testTag)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.4f)
        )
    }
}

@Composable
private fun AudioCallStage(
    peerName: String,
    peerHandle: String,
    peerPhone: String,
    isConnected: Boolean
) {
    val transition = rememberInfiniteTransition(label = "voice_wave")
    val scale by transition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "voice_scale"
    )
    val initials = peerName
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .let { parts ->
            when {
                parts.size >= 2 -> "${parts.first().first()}${parts.last().first()}".uppercase()
                parts.size == 1 -> parts.first().take(2).uppercase()
                else -> "SC"
            }
        }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MidnightBg,
                        Color(0xFF0C2A2F),
                        MidnightBg
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(180.dp)) {
                Box(
                    modifier = Modifier
                        .size(126.dp)
                        .scale(if (isConnected) scale else 1.08f)
                        .clip(CircleShape)
                        .background(EmeraldSignal.copy(alpha = 0.16f))
                )
                Box(
                    modifier = Modifier
                        .size(110.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(EmeraldSignal, TealElectric))),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = initials,
                        style = MaterialTheme.typography.displaySmall,
                        color = Color(0xFF022C22),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = if (isConnected) "Encrypted P2P Audio Active" else "Waiting for peer...",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF94B5B8)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "$peerHandle • $peerPhone",
                style = MonoHandleStyle,
                color = TealElectric.copy(alpha = 0.85f)
            )
        }
    }
}

@Composable
fun WebRtcVideoRenderer(
    videoTrack: VideoTrack?,
    eglBaseContext: EglBase.Context,
    mirror: Boolean,
    isOverlay: Boolean,
    modifier: Modifier = Modifier
) {
    val rendererRef = remember { mutableListOf<SurfaceViewRenderer>() }

    DisposableEffect(videoTrack, eglBaseContext) {
        onDispose {
            rendererRef.forEach { renderer ->
                try {
                    videoTrack?.removeSink(renderer)
                } catch (_: Exception) {
                }
                try {
                    renderer.release()
                } catch (_: Exception) {
                }
            }
            rendererRef.clear()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceViewRenderer(context).apply {
                init(eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                setMirror(mirror)
                setZOrderMediaOverlay(isOverlay)
                rendererRef.add(this)
                videoTrack?.addSink(this)
            }
        },
        update = { renderer ->
            renderer.setMirror(mirror)
        }
    )
}
