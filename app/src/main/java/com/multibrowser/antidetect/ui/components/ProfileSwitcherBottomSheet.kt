package com.multibrowser.antidetect.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multibrowser.antidetect.data.model.ProfileEntity
import com.multibrowser.antidetect.ui.theme.*

data class ActiveProfileState(
    val profile: ProfileEntity,
    var tabs: List<BrowserTab>,
    var activeTabId: String,
    var liveIp: String = "Direct IP",
    var location: String = "Detecting..."
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSwitcherBottomSheet(
    runningProfiles: Map<String, ActiveProfileState>,
    currentForegroundProfileId: String?,
    allProfiles: List<ProfileEntity>,
    sessionMuteStates: Map<String, Boolean> = emptyMap(),
    onToggleAudio: (String) -> Unit = {},
    onSelectRunningProfile: (String) -> Unit,
    onStartProfile: (ProfileEntity) -> Unit,
    onStopProfile: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
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
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header Row
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
                        Icons.Default.Devices,
                        contentDescription = null,
                        tint = OctoPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        "Switch Profile",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = OctoTextPrimary
                    )
                    Surface(
                        color = OctoSuccess.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            "${runningProfiles.size} Active",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = OctoSuccess
                        )
                    }
                }

                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = OctoTextSecondary)
                }
            }

            HorizontalDivider(color = OctoBorder, thickness = 0.8.dp)

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                // Section 1: Running Profiles
                item {
                    Text(
                        "RUNNING SESSIONS (${runningProfiles.size})",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = OctoTextMuted,
                        letterSpacing = 1.sp
                    )
                }

                items(runningProfiles.values.toList(), key = { it.profile.id }) { state ->
                    val isForeground = state.profile.id == currentForegroundProfileId
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelectRunningProfile(state.profile.id)
                                onDismiss()
                            },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isForeground) OctoSurfaceElevated else OctoSurfaceElevated.copy(alpha = 0.6f)
                        ),
                        border = BorderStroke(
                            width = if (isForeground) 1.5.dp else 1.dp,
                            color = if (isForeground) OctoPrimary else OctoBorder
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(OctoSuccess, CircleShape)
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            state.profile.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = OctoTextPrimary
                                        )
                                        if (isForeground) {
                                            Surface(
                                                color = OctoPrimary.copy(alpha = 0.2f),
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    "Current",
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = OctoPrimary,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        "${state.profile.brand} ${state.profile.modelName} • ${state.tabs.size} tab(s)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = OctoTextSecondary
                                    )
                                    Text(
                                        "${state.liveIp} • ${state.location}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = OctoSuccess
                                    )
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val isMuted = sessionMuteStates[state.profile.id] ?: true
                                IconButton(
                                    onClick = { onToggleAudio(state.profile.id) },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                        contentDescription = if (isMuted) "Unmute Audio" else "Mute Audio",
                                        tint = if (isMuted) OctoTextSecondary else OctoSuccess,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                if (!isForeground) {
                                    Button(
                                        onClick = {
                                            onSelectRunningProfile(state.profile.id)
                                            onDismiss()
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = OctoPrimary),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text("Switch", fontSize = 12.sp)
                                    }
                                }

                                IconButton(
                                    onClick = { onStopProfile(state.profile.id) },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Stop,
                                        contentDescription = "Stop Profile",
                                        tint = OctoDanger,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Section 2: Other Inactive Profiles
                val inactiveProfiles = allProfiles.filter { it.id !in runningProfiles.keys }
                if (inactiveProfiles.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            "OTHER PROFILES (${inactiveProfiles.size})",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = OctoTextMuted,
                            letterSpacing = 1.sp
                        )
                    }

                    items(inactiveProfiles, key = { it.id }) { profile ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = OctoSurfaceElevated.copy(alpha = 0.4f)),
                            border = BorderStroke(1.dp, OctoBorder)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        profile.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = OctoTextPrimary
                                    )
                                    Text(
                                        "${profile.brand} ${profile.modelName} • ${profile.modelCode}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = OctoTextSecondary
                                    )
                                }

                                Button(
                                    onClick = {
                                        onStartProfile(profile)
                                        onDismiss()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = OctoPrimary.copy(alpha = 0.2f)),
                                    border = BorderStroke(1.dp, OctoPrimary.copy(alpha = 0.5f)),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = OctoPrimary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Start & Open", fontSize = 12.sp, color = OctoPrimary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
