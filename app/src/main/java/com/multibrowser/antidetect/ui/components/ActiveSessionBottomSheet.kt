package com.multibrowser.antidetect.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.multibrowser.antidetect.data.model.ProfileEntity
import com.multibrowser.antidetect.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveSessionBottomSheet(
    profile: ProfileEntity,
    liveIp: String,
    location: String,
    onStopSession: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = OctoSurface,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .background(OctoBorder, RoundedCornerShape(2.dp))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header with Live Status & IP
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(OctoSuccess, CircleShape)
                        )
                        Text(
                            profile.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = OctoTextPrimary
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "$liveIp • $location",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = OctoSuccess
                    )
                }
                FilledTonalIconButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText(
                            "Profile Telemetry",
                            "Profile: ${profile.name}\nIP: $liveIp\nLocation: $location\nDevice: ${profile.brand} ${profile.modelName}\nGPU: ${profile.webGlRenderer}"
                        )
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Telemetry copied to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = OctoSurfaceElevated
                    )
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy details", tint = OctoPrimary)
                }
            }

            HorizontalDivider(color = OctoBorder, thickness = 0.8.dp)

            // Hardware & Isolation Telemetry Card
            Card(
                colors = CardDefaults.cardColors(containerColor = OctoSurfaceElevated),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, OctoBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "HARDWARE TELEMETRY",
                        style = MaterialTheme.typography.labelSmall,
                        color = OctoTextMuted,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Target Device", style = MaterialTheme.typography.bodyMedium, color = OctoTextSecondary)
                        Text("${profile.brand} ${profile.modelName}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = OctoTextPrimary)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Model Identifier", style = MaterialTheme.typography.bodyMedium, color = OctoTextSecondary)
                        Text(profile.modelCode, style = MaterialTheme.typography.bodyMedium, color = OctoTextPrimary)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("WebGL GPU", style = MaterialTheme.typography.bodyMedium, color = OctoTextSecondary)
                        Text(profile.webGlRenderer, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = OctoPrimary)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Viewport Resolution", style = MaterialTheme.typography.bodyMedium, color = OctoTextSecondary)
                        Text("${profile.screenWidth}x${profile.screenHeight} @ ${profile.dpr}x", style = MaterialTheme.typography.bodyMedium, color = OctoTextPrimary)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("RAM & Cores", style = MaterialTheme.typography.bodyMedium, color = OctoTextSecondary)
                        Text("${profile.ramGb}GB RAM • ${profile.cpuCores} Cores", style = MaterialTheme.typography.bodyMedium, color = OctoTextPrimary)
                    }
                }
            }

            // Security & Fingerprint Protection Card
            Card(
                colors = CardDefaults.cardColors(containerColor = OctoSurfaceElevated),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, OctoBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "ANTI-DETECT SECURITY STATUS",
                        style = MaterialTheme.typography.labelSmall,
                        color = OctoTextMuted,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Native C++ RFP", style = MaterialTheme.typography.bodyMedium, color = OctoTextSecondary)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = OctoSuccess, modifier = Modifier.size(16.dp))
                            Text("Active (Passed)", style = MaterialTheme.typography.bodySmall, color = OctoSuccess)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("WebRTC Leak Shield", style = MaterialTheme.typography.bodyMedium, color = OctoTextSecondary)
                        Text(profile.webRtcMode, style = MaterialTheme.typography.bodyMedium, color = OctoTextPrimary)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Network Route", style = MaterialTheme.typography.bodyMedium, color = OctoTextSecondary)
                        Text(
                            if (profile.proxyType == "DIRECT") "Direct IP" else "${profile.proxyType}: ${profile.proxyHost}:${profile.proxyPort}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (profile.proxyType == "DIRECT") OctoSuccess else OctoPrimary
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Camera Emulation", style = MaterialTheme.typography.bodyMedium, color = OctoTextSecondary)
                        Text(
                            if (profile.selectedCameraVideoPath == null) "Direct Hardware" else "Virtual Stream",
                            style = MaterialTheme.typography.bodyMedium,
                            color = OctoTextPrimary
                        )
                    }
                }
            }

            // Destructive Action: Stop Profile Button
            Button(
                onClick = onStopSession,
                colors = ButtonDefaults.buttonColors(containerColor = OctoDanger),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Stop, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Stop Profile Session", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

