package com.multibrowser.antidetect.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.multibrowser.antidetect.sync.CookieEngine
import com.multibrowser.antidetect.sync.CookieSyncDispatcher
import com.multibrowser.antidetect.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun CookieActionDialog(
    profileId: String,
    profileName: String,
    onDismiss: () -> Unit,
    onCookiesUpdated: (Int) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var tabIndex by remember { mutableIntStateOf(0) } // 0 = Export, 1 = Import
    var cookieContent by remember { mutableStateOf("") }
    var isSyncingBackend by remember { mutableStateOf(false) }

    // Read stored cookies on launch
    LaunchedEffect(profileId) {
        cookieContent = CookieEngine.exportCookiesToJson(context, profileId)
    }

    // Native File Exporter (Saves cookies_<profile_name>.json to storage)
    val fileSaveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let { targetUri ->
            try {
                context.contentResolver.openOutputStream(targetUri)?.use { stream ->
                    stream.write(cookieContent.toByteArray(Charsets.UTF_8))
                    Toast.makeText(context, "Cookies exported to file!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Export error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Native File Importer (Loads .json or .txt cookies directly into buffer)
    val filePickLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { sourceUri ->
            try {
                context.contentResolver.openInputStream(sourceUri)?.use { stream ->
                    cookieContent = stream.bufferedReader().readText()
                    Toast.makeText(context, "File loaded into editor!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "File read error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OctoSurface,
        title = {
            Text("Cookies: $profileName", color = OctoTextPrimary, style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Dual Tabs
                TabRow(
                    selectedTabIndex = tabIndex,
                    containerColor = OctoSurfaceElevated,
                    contentColor = OctoPrimary
                ) {
                    Tab(
                        selected = tabIndex == 0,
                        onClick = { tabIndex = 0 },
                        text = { Text("Export", color = if (tabIndex == 0) OctoPrimary else OctoTextSecondary) }
                    )
                    Tab(
                        selected = tabIndex == 1,
                        onClick = { tabIndex = 1 },
                        text = { Text("Import", color = if (tabIndex == 1) OctoPrimary else OctoTextSecondary) }
                    )
                }

                // Interactive Content Area
                OutlinedTextField(
                    value = cookieContent,
                    onValueChange = { cookieContent = it },
                    readOnly = (tabIndex == 0),
                    placeholder = {
                        Text(
                            text = if (tabIndex == 0) "No cookies stored for this profile."
                            else "Paste JSON or Netscape cookies here...",
                            color = OctoTextMuted
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = OctoSurfaceElevated,
                        unfocusedContainerColor = OctoSurfaceElevated,
                        focusedBorderColor = OctoPrimary,
                        unfocusedBorderColor = OctoBorder,
                        focusedTextColor = OctoTextPrimary,
                        unfocusedTextColor = OctoTextPrimary
                    ),
                    textStyle = MaterialTheme.typography.bodySmall
                )

                if (tabIndex == 0) {
                    // Export Actions: Clipboard Copy OR Native Storage Save
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Cookies", cookieContent))
                                Toast.makeText(context, "Copied to clipboard!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = OctoPrimary)
                        ) {
                            Text("Copy Text")
                        }

                        Button(
                            onClick = {
                                val cleanFileName = profileName.replace("[^a-zA-Z0-9]".toRegex(), "_")
                                fileSaveLauncher.launch("cookies_${cleanFileName}.json")
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = OctoSurfaceElevated,
                                contentColor = OctoTextPrimary
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, tint = OctoTextPrimary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Save File")
                        }
                    }
                } else {
                    // Import Actions: Pick File from Storage OR Apply Buffer (Dual Local + Backend Sync)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { filePickLauncher.launch("*/*") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, OctoBorder)
                        ) {
                            Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Pick File", color = OctoTextPrimary)
                        }

                        Button(
                            onClick = {
                                if (cookieContent.isNotBlank()) {
                                    isSyncingBackend = true

                                    // 1. Local SQLite Ingestion
                                    val localCount = CookieEngine.importCookiesFromJson(context, profileId, cookieContent)
                                    onCookiesUpdated(localCount)

                                    // 2. Real-Time Backend Dual-Sync
                                    coroutineScope.launch {
                                        val result = CookieSyncDispatcher.syncCookiesToBackend(profileId, cookieContent)
                                        isSyncingBackend = false

                                        result.onSuccess { backendCount ->
                                            Toast.makeText(
                                                context,
                                                "Saved locally ($localCount) and synced to backend ($backendCount)!",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            onDismiss()
                                        }.onFailure { error ->
                                            Toast.makeText(
                                                context,
                                                "Saved locally, but backend sync failed: ${error.message}",
                                                Toast.LENGTH_LONG
                                            ).show()
                                            onDismiss()
                                        }
                                    }
                                } else {
                                    Toast.makeText(context, "Please enter or pick cookie data first", Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = !isSyncingBackend,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = OctoPrimary),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            if (isSyncingBackend) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Syncing...")
                            } else {
                                Text("Save & Apply")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = OctoTextSecondary)
            }
        }
    )
}
