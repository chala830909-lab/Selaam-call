package com.example.ui.components

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun PermissionPromptDialog(
    needsCamera: Boolean,
    permanentlyDenied: Boolean,
    onRequestAgain: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val title = if (needsCamera) "Camera & Microphone Access" else "Microphone Access Needed"
    val body = if (needsCamera) {
        "SelamCall needs access to your microphone and camera to stream encrypted real-time audio and video during calls."
    } else {
        "SelamCall needs access to your microphone so the person you call can hear you clearly."
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = if (needsCamera) Icons.Rounded.Videocam else Icons.Rounded.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        },
        title = { Text(text = title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                Text(text = body, style = MaterialTheme.typography.bodyMedium)
                if (permanentlyDenied) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Permission was previously denied. Please enable it in Android App Settings to place or answer calls.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            if (permanentlyDenied) {
                Button(
                    onClick = {
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null)
                        )
                        context.startActivity(intent)
                        onDismiss()
                    },
                    modifier = Modifier.testTag("open_settings_permission_button")
                ) {
                    Text("Open Settings")
                }
            } else {
                Button(
                    onClick = onRequestAgain,
                    modifier = Modifier.testTag("grant_permission_button")
                ) {
                    Text("Grant Access")
                }
            }
        },
        dismissButton = {
            if (permanentlyDenied) {
                OutlinedButton(onClick = onDismiss) {
                    Text("Not Now")
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}
