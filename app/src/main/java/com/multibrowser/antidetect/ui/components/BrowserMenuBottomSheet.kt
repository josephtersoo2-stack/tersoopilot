package com.multibrowser.antidetect.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.multibrowser.antidetect.automation.GhostPilotRunner
import com.multibrowser.antidetect.data.model.ProfileEntity
import com.multibrowser.antidetect.network.AuthManager
import com.multibrowser.antidetect.sync.SyncManager
import com.multibrowser.antidetect.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserMenuBottomSheet(
    activeProfile: ProfileEntity?,
    isAudioMuted: Boolean = true,
    ghostPilotRunner: GhostPilotRunner? = null,
    onToggleAudio: () -> Unit = {},
    onOpenAuth: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenProfileSpecs: () -> Unit,
    onNewTab: () -> Unit,
    onReload: () -> Unit,
    onSwitchProfile: () -> Unit,
    onStopSession: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val isLoggedIn by AuthManager.isLoggedIn.collectAsState()
    val username by AuthManager.currentUsername.collectAsState()

    var isSyncing by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var showStopConfirm by remember { mutableStateOf(false) }
    var showCookieDialog by remember { mutableStateOf(false) }

    val isAutomationRunning = ghostPilotRunner?.isRunning ?: false
    val currentStep = ghostPilotRunner?.currentStateId

    val scrollState = rememberScrollState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = OctoSurfaceElevated,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp)
                .padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Account & Cloud Sync Card (Firefox Mobile style top card)
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = OctoSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (!isLoggedIn) {
                            onDismiss()
                            onOpenAuth()
                        }
                    }
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(if (isLoggedIn) OctoPrimary else OctoSurfaceElevated),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (isLoggedIn) Icons.Default.CloudDone else Icons.Default.AccountCircle,
                                contentDescription = null,
                                tint = if (isLoggedIn) Color.White else OctoTextSecondary,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (isLoggedIn) "Signed in as $username" else "Sign in / Register",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = OctoTextPrimary
                            )
                            Text(
                                if (isLoggedIn) "Profiles, history & cookies sync automatically" else "Sync passwords, profiles & tabs across devices",
                                style = MaterialTheme.typography.bodySmall,
                                color = OctoTextSecondary
                            )
                        }

                        if (!isLoggedIn) {
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = "Sign in",
                                tint = OctoTextSecondary
                            )
                        }
                    }

                    if (isLoggedIn) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Push now
                            Button(
                                onClick = {
                                    if (activeProfile != null) {
                                        isSyncing = true
                                        coroutineScope.launch {
                                            val res = SyncManager.pushProfileToCloud(context, activeProfile)
                                            isSyncing = false
                                            Toast.makeText(
                                                context,
                                                if (res.isSuccess) "Profile synced to cloud" else "Sync failed: ${res.exceptionOrNull()?.message}",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                },
                                enabled = !isSyncing,
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = OctoPrimary),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Sync Now", style = MaterialTheme.typography.labelMedium)
                            }

                            // Pull / Restore from cloud
                            OutlinedButton(
                                onClick = {
                                    isSyncing = true
                                    coroutineScope.launch {
                                        val res = SyncManager.pullProfilesFromCloud(context)
                                        isSyncing = false
                                        Toast.makeText(
                                            context,
                                            if (res.isSuccess) "Restored ${res.getOrNull()} profiles from cloud" else "Pull failed: ${res.exceptionOrNull()?.message}",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                },
                                enabled = !isSyncing,
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = OctoTextPrimary),
                                border = androidx.compose.foundation.BorderStroke(1.dp, OctoBorder),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Restore", style = MaterialTheme.typography.labelMedium)
                            }

                            // Sign out with confirmation
                            IconButton(
                                onClick = {
                                    showLogoutConfirm = true
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Log out", tint = OctoDanger)
                            }
                        }
                    }
                }
            }

            // GhostPilot Autonomous Engine Card
            if (ghostPilotRunner != null) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = OctoSurface,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isAutomationRunning) OctoSuccess.copy(alpha = 0.5f) else OctoBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
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
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(if (isAutomationRunning) OctoSuccess.copy(alpha = 0.18f) else OctoSurfaceElevated),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.SmartToy,
                                    contentDescription = null,
                                    tint = if (isAutomationRunning) OctoSuccess else OctoPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .background(
                                                if (isAutomationRunning) OctoSuccess else OctoTextMuted,
                                                CircleShape
                                            )
                                    )
                                    Text(
                                        text = if (isAutomationRunning) {
                                            "GhostPilot Active" + (if (!currentStep.isNullOrBlank()) " ($currentStep)" else "")
                                        } else {
                                            "GhostPilot Automation"
                                        },
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = OctoTextPrimary
                                    )
                                }
                                Text(
                                    text = if (isAutomationRunning) "Running physical gesture actions" else "Start automated tasks & gestures",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = OctoTextSecondary
                                )
                            }
                        }

                        Button(
                            onClick = {
                                if (ghostPilotRunner.isRunning) {
                                    ghostPilotRunner.stop()
                                } else {
                                    ghostPilotRunner.start()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isAutomationRunning) OctoDanger else OctoPrimary
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = if (isAutomationRunning) "Pause Pilot" else "Start Pilot",
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            // 2. Horizontal Quick Tools Row (History, Bookmarks, Sync, Specs)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                QuickToolItem(
                    icon = Icons.Default.History,
                    label = "History",
                    onClick = {
                        onDismiss()
                        onOpenHistory()
                    }
                )

                QuickToolItem(
                    icon = Icons.Default.Bookmark,
                    label = "Bookmarks",
                    onClick = {
                        onDismiss()
                        onOpenBookmarks()
                    }
                )

                QuickToolItem(
                    icon = Icons.Default.CloudSync,
                    label = "Cloud Sync",
                    onClick = {
                        if (!isLoggedIn) {
                            onDismiss()
                            onOpenAuth()
                        } else {
                            coroutineScope.launch {
                                val res = SyncManager.pullProfilesFromCloud(context)
                                Toast.makeText(
                                    context,
                                    if (res.isSuccess) "Synced: ${res.getOrNull()} profiles active" else "Cloud sync failed",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                )

                QuickToolItem(
                    icon = Icons.Default.Fingerprint,
                    label = "Profile Specs",
                    onClick = {
                        onDismiss()
                        onOpenProfileSpecs()
                    }
                )
            }

            HorizontalDivider(color = OctoBorder, thickness = 0.8.dp)

            // 3. Vertical Menu Options
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MenuRowItem(
                    icon = Icons.Default.Add,
                    title = "New Tab",
                    subtitle = "Open another tab under this profile session",
                    onClick = {
                        onDismiss()
                        onNewTab()
                    }
                )

                MenuRowItem(
                    icon = if (isAudioMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                    title = if (isAudioMuted) "Unmute Profile Audio" else "Mute Profile Audio",
                    subtitle = if (isAudioMuted) "Enable audio for this session (mutes other profiles)" else "Silence audio output for this session",
                    tint = if (isAudioMuted) OctoTextSecondary else OctoSuccess,
                    onClick = {
                        onToggleAudio()
                    }
                )

                MenuRowItem(
                    icon = Icons.Default.Refresh,
                    title = "Reload Page",
                    subtitle = "Bypass cache and refresh page",
                    onClick = {
                        onDismiss()
                        onReload()
                    }
                )

                MenuRowItem(
                    icon = if (isAppInDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                    title = if (isAppInDarkTheme) "Light Theme" else "Dark Theme",
                    subtitle = if (isAppInDarkTheme) "Switch to clean light mode" else "Switch to sleek dark mode",
                    onClick = {
                        ThemeManager.toggleTheme(context)
                    }
                )

                MenuRowItem(
                    icon = Icons.Default.Cookie,
                    title = "Manage Cookies",
                    subtitle = "Export JSON or Import/Overwrite session cookies",
                    onClick = {
                        showCookieDialog = true
                    }
                )

                MenuRowItem(
                    icon = Icons.Default.Devices,
                    title = "Switch Profile",
                    subtitle = "Hot-swap to another running profile",
                    onClick = {
                        onDismiss()
                        onSwitchProfile()
                    }
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Prominent Stop Browser Profile Action Card
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = OctoDanger.copy(alpha = 0.12f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, OctoDanger.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showStopConfirm = true }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(OctoDanger.copy(alpha = 0.22f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.PowerSettingsNew,
                                contentDescription = "Stop Profile Session",
                                tint = OctoDanger,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Stop Browser Profile",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = OctoDanger
                            )
                            Text(
                                "Save session cookies, tabs & stop sandbox",
                                style = MaterialTheme.typography.bodySmall,
                                color = OctoTextSecondary
                            )
                        }
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = OctoDanger
                        )
                    }
                }
            }
        }
    }

    // Cookie Import / Export Dialog
    if (showCookieDialog && activeProfile != null) {
        CookieActionDialog(
            profileId = activeProfile.id,
            profileName = activeProfile.name,
            onDismiss = { showCookieDialog = false },
            onCookiesUpdated = { count ->
                Toast.makeText(context, "$count cookies active for ${activeProfile.name}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // Confirmation: Log Out
    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            containerColor = OctoSurfaceElevated,
            title = {
                Text("Log Out?", fontWeight = FontWeight.Bold, color = OctoTextPrimary)
            },
            text = {
                Text(
                    "Are you sure you want to log out of '$username'? Cloud profile synchronization will pause on this device.",
                    color = OctoTextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showLogoutConfirm = false
                        AuthManager.logout()
                        Toast.makeText(context, "Logged out successfully", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = OctoDanger)
                ) {
                    Text("Log Out", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) {
                    Text("Cancel", color = OctoTextSecondary)
                }
            }
        )
    }

    // Confirmation: Stop Profile Session
    if (showStopConfirm) {
        AlertDialog(
            onDismissRequest = { showStopConfirm = false },
            containerColor = OctoSurfaceElevated,
            title = {
                Text("Stop Profile Session?", fontWeight = FontWeight.Bold, color = OctoTextPrimary)
            },
            text = {
                Text(
                    "Are you sure you want to stop session '${activeProfile?.name ?: "this profile"}'? Open tabs, cookies, and browsing history will be automatically saved.",
                    color = OctoTextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showStopConfirm = false
                        onDismiss()
                        onStopSession()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = OctoDanger)
                ) {
                    Text("Stop Session", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirm = false }) {
                    Text("Cancel", color = OctoTextSecondary)
                }
            }
        )
    }
}

@Composable
private fun QuickToolItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(OctoSurface),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = OctoTextPrimary, modifier = Modifier.size(22.dp))
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = OctoTextSecondary
        )
    }
}

@Composable
private fun MenuRowItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tint: Color = OctoTextPrimary,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (tint == OctoDanger) OctoDanger.copy(alpha = 0.12f) else OctoSurface),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = title, tint = tint, modifier = Modifier.size(20.dp))
            }
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = tint
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = OctoTextSecondary
                )
            }
        }
    }
}
