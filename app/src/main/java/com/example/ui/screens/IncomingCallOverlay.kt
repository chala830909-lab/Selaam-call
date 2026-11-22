package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.CallSession
import com.example.data.model.CallType
import com.example.ui.theme.CallCoral
import com.example.ui.theme.EmeraldSignal
import com.example.ui.theme.MidnightBg
import com.example.ui.theme.MidnightSurfaceElevated
import com.example.ui.theme.MonoHandleStyle
import com.example.ui.theme.TealElectric

@Composable
fun IncomingCallOverlay(
    call: CallSession,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    BackHandler {
        // Prevent accidental dismissal without explicit Decline/Accept
    }

    val isVideo = call.parsedType() == CallType.VIDEO
    val transition = rememberInfiniteTransition(label = "ring_pulse")
    val pulseScale by transition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )
    val outerScale by transition.animateFloat(
        initialValue = 1.1f,
        targetValue = 1.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "outer_scale"
    )

    val initials = call.callerDisplayName
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
                        Color(0xFF0A2429),
                        MidnightBg
                    )
                )
            )
            .safeDrawingPadding()
            .padding(horizontal = 24.dp, vertical = 32.dp)
            .testTag("incoming_call_overlay")
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top call type pill
            Surface(
                color = MidnightSurfaceElevated.copy(alpha = 0.9f),
                shape = RoundedCornerShape(999.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isVideo) Icons.Rounded.Videocam else Icons.Rounded.Call,
                        contentDescription = null,
                        tint = EmeraldSignal,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isVideo) "Incoming Video Call" else "Incoming Audio Call",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White
                    )
                }
            }

            // Center pulsing caller identity
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(190.dp)) {
                    Box(
                        modifier = Modifier
                            .size(128.dp)
                            .scale(outerScale)
                            .clip(CircleShape)
                            .background(TealElectric.copy(alpha = 0.10f))
                    )
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .scale(pulseScale)
                            .clip(CircleShape)
                            .background(EmeraldSignal.copy(alpha = 0.22f))
                    )
                    Box(
                        modifier = Modifier
                            .size(112.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(listOf(EmeraldSignal, TealElectric))
                            ),
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

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = call.callerDisplayName,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "@${call.callerUsername}",
                    style = MonoHandleStyle,
                    color = EmeraldSignal
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = call.callerPhone,
                    style = MonoHandleStyle,
                    color = Color(0xFF94B5B8)
                )
            }

            // Bottom Accept / Decline action buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledIconButton(
                        onClick = onDecline,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = CallCoral,
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .size(72.dp)
                            .testTag("decline_call_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CallEnd,
                            contentDescription = "Decline call",
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Decline",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFFFCA5A5)
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledIconButton(
                        onClick = onAccept,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = EmeraldSignal,
                            contentColor = Color(0xFF022C22)
                        ),
                        modifier = Modifier
                            .size(72.dp)
                            .testTag("accept_call_button")
                    ) {
                        Icon(
                            imageVector = if (isVideo) Icons.Rounded.Videocam else Icons.Rounded.Call,
                            contentDescription = "Accept call",
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Accept",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFFA7F3D0)
                    )
                }
            }
        }
    }
}
