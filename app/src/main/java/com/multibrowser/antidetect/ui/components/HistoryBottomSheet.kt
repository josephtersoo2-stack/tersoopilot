package com.multibrowser.antidetect.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.multibrowser.antidetect.ui.theme.*
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HistoryItem(
    val title: String,
    val url: String,
    val timestamp: Long
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryBottomSheet(
    historyJson: String,
    onNavigate: (String) -> Unit,
    onClearHistory: () -> Unit,
    onDismiss: () -> Unit
) {
    val items = rememberHistoryItems(historyJson)
    var showClearConfirm by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = OctoSurfaceElevated,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header
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
                        Icons.Default.History,
                        contentDescription = null,
                        tint = OctoPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        "Browsing History",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = OctoTextPrimary
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (items.isNotEmpty()) {
                        IconButton(onClick = { showClearConfirm = true }) {
                            Icon(Icons.Default.ClearAll, contentDescription = "Clear All", tint = OctoDanger)
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = OctoTextMuted)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (items.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.History,
                            contentDescription = null,
                            tint = OctoTextMuted,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "No browsing history yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = OctoTextMuted
                        )
                    }
                }
            } else {
                val dateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(items) { item ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = OctoSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onNavigate(item.url)
                                    onDismiss()
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(OctoSurfaceElevated),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Language,
                                        contentDescription = null,
                                        tint = OctoTextSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        item.title.ifBlank { item.url },
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = OctoTextPrimary,
                                        maxLines = 1
                                    )
                                    Text(
                                        item.url,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = OctoTextMuted,
                                        maxLines = 1
                                    )
                                    Text(
                                        dateFormat.format(Date(item.timestamp)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = OctoPrimary.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Confirmation: Clear Browsing History
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            containerColor = OctoSurfaceElevated,
            title = {
                Text("Clear Browsing History?", fontWeight = FontWeight.Bold, color = OctoTextPrimary)
            },
            text = {
                Text(
                    "Are you sure you want to permanently clear all browsing history for this profile? This action cannot be undone.",
                    color = OctoTextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearConfirm = false
                        onClearHistory()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = OctoDanger)
                ) {
                    Text("Clear All", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Cancel", color = OctoTextSecondary)
                }
            }
        )
    }
}

private fun rememberHistoryItems(historyJson: String): List<HistoryItem> {
    if (historyJson.isBlank() || historyJson == "[]") return emptyList()
    val list = mutableListOf<HistoryItem>()
    try {
        val array = JSONArray(historyJson)
        for (i in (array.length() - 1) downTo 0) {
            val obj = array.optJSONObject(i)
            if (obj != null) {
                list.add(
                    HistoryItem(
                        title = obj.optString("title", "Page"),
                        url = obj.optString("url", ""),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    )
                )
            }
        }
    } catch (_: Exception) {}
    return list
}
