package com.multibrowser.antidetect.ui.components

import android.content.Context
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multibrowser.antidetect.data.model.ProfileEntity
import com.multibrowser.antidetect.ui.theme.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class QuickAccessItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val brandColorHex: String = "#3B82F6",
    val iconType: String = "globe"
)

object QuickAccessRepository {
    private const val PREFS_NAME = "octo_quick_access_prefs"
    private const val KEY_SHORTCUTS = "shortcuts_json"

    fun getShortcuts(context: Context): List<QuickAccessItem> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_SHORTCUTS, null)
        if (json.isNullOrBlank()) {
            val defaults = getDefaultShortcuts()
            saveShortcuts(context, defaults)
            return defaults
        }
        return try {
            val arr = JSONArray(json)
            val list = mutableListOf<QuickAccessItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    QuickAccessItem(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        name = obj.optString("name", "Site"),
                        url = obj.optString("url", "https://google.com"),
                        brandColorHex = obj.optString("brandColorHex", "#3B82F6"),
                        iconType = obj.optString("iconType", "globe")
                    )
                )
            }
            if (list.isEmpty()) {
                val defaults = getDefaultShortcuts()
                saveShortcuts(context, defaults)
                defaults
            } else list
        } catch (_: Exception) {
            getDefaultShortcuts()
        }
    }

    fun saveShortcuts(context: Context, items: List<QuickAccessItem>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val arr = JSONArray()
        items.forEach { item ->
            val obj = JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("url", item.url)
                put("brandColorHex", item.brandColorHex)
                put("iconType", item.iconType)
            }
            arr.put(obj)
        }
        prefs.edit().putString(KEY_SHORTCUTS, arr.toString()).apply()
    }

    fun getDefaultShortcuts(): List<QuickAccessItem> = listOf(
        QuickAccessItem("default_google", "Google", "https://www.google.com", "#4285F4", "search"),
        QuickAccessItem("default_youtube", "YouTube", "https://www.youtube.com", "#FF0000", "play"),
        QuickAccessItem("default_x", "Twitter / X", "https://x.com", "#1DA1F2", "tag"),
        QuickAccessItem("default_reddit", "Reddit", "https://www.reddit.com", "#FF4500", "forum"),
        QuickAccessItem("default_github", "GitHub", "https://github.com", "#6E5494", "code"),
        QuickAccessItem("default_wikipedia", "Wikipedia", "https://www.wikipedia.org", "#006699", "book"),
        QuickAccessItem("default_iphey", "Iphey Test", "https://iphey.com", "#00C853", "fingerprint"),
        QuickAccessItem("default_creepjs", "CreepJS", "https://abrahamjuliot.github.io/creepjs/", "#FFAB00", "shield")
    )
}

fun resolveQuickAccessIcon(iconType: String): ImageVector = when (iconType.lowercase()) {
    "search" -> Icons.Default.Search
    "play" -> Icons.Default.PlayArrow
    "tag" -> Icons.Default.Tag
    "forum" -> Icons.Default.Forum
    "code" -> Icons.Default.Code
    "book" -> Icons.Default.MenuBook
    "fingerprint" -> Icons.Default.Fingerprint
    "shield" -> Icons.Default.Security
    else -> Icons.Default.Public
}

fun parseColorHex(hex: String, defaultColor: Color = Color(0xFF3B82F6)): Color {
    return try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (_: Exception) {
        defaultColor
    }
}

fun normalizeWebUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
        return trimmed
    }
    return "https://$trimmed"
}

private sealed class QuickAccessTile {
    data class Shortcut(val item: QuickAccessItem) : QuickAccessTile()
    object AddTile : QuickAccessTile()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrowserHomeScreen(
    activeProfile: ProfileEntity?,
    onNavigate: (String) -> Unit,
    onFocusAddressBar: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scrollState = rememberScrollState()

    var shortcuts by remember { mutableStateOf(QuickAccessRepository.getShortcuts(context)) }

    // Dialog & Action Sheet states
    var shortcutForAction by remember { mutableStateOf<QuickAccessItem?>(null) }
    var itemBeingEdited by remember { mutableStateOf<QuickAccessItem?>(null) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }

    val presetColors = listOf(
        "#4285F4", "#FF0000", "#1DA1F2", "#FF4500",
        "#6E5494", "#006699", "#00C853", "#FFAB00"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OctoBackground)
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        // 1. Sleek Brand Header with Shield Gradient Badge
        Box(
            modifier = Modifier
                .size(68.dp)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(OctoPrimary, OctoPrimary.copy(alpha = 0.6f))
                    ),
                    shape = RoundedCornerShape(20.dp)
                )
                .border(
                    BorderStroke(1.5.dp, Color.White.copy(alpha = 0.25f)),
                    shape = RoundedCornerShape(20.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = "Octo Shield",
                tint = Color.White,
                modifier = Modifier.size(36.dp)
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = "TersooPilot",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = OctoTextPrimary,
            letterSpacing = 0.5.sp
        )

        Text(
            text = "Anti-Detect • Hardware Masked Engine",
            style = MaterialTheme.typography.bodySmall,
            color = OctoTextSecondary
        )

        Spacer(modifier = Modifier.height(20.dp))

        // 2. Interactive Search Box Pill (Chrome / Firefox mobile style)
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = OctoSurfaceElevated,
            border = BorderStroke(1.dp, OctoBorder),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onFocusAddressBar() }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = OctoPrimary,
                    modifier = Modifier.size(20.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = "Search Google or type a URL...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OctoTextMuted,
                    modifier = Modifier.weight(1f)
                )

                Icon(
                    imageVector = Icons.Default.ArrowForward,
                    contentDescription = null,
                    tint = OctoTextMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 3. Active Profile Status Card
        if (activeProfile != null) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = OctoSurface,
                border = BorderStroke(1.dp, OctoBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(OctoSuccess, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = activeProfile.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = OctoTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${activeProfile.brand} ${activeProfile.modelName}".take(22),
                            style = MaterialTheme.typography.labelSmall,
                            color = OctoTextSecondary
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Fingerprint Protection Chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        StatusChip(label = "Canvas Noise", isProtected = true, modifier = Modifier.weight(1f))
                        StatusChip(label = "Audio Protect", isProtected = true, modifier = Modifier.weight(1f))
                        StatusChip(
                            label = if (activeProfile.proxyHost.isNotBlank()) "Proxy ON" else "Direct IP",
                            isProtected = activeProfile.proxyHost.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        // 4. Quick Access Shortcuts Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Quick Access",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = OctoTextPrimary
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "Long-press to edit",
                style = MaterialTheme.typography.labelSmall,
                color = OctoTextMuted
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Quick Access Grid with "+ Add" Tile
        val allTiles: List<QuickAccessTile> = shortcuts.map { QuickAccessTile.Shortcut(it) } + QuickAccessTile.AddTile
        val chunkedRows = allTiles.chunked(4)

        chunkedRows.forEach { rowTiles ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.Start
            ) {
                rowTiles.forEach { tile ->
                    when (tile) {
                        is QuickAccessTile.Shortcut -> {
                            ShortcutTile(
                                item = tile.item,
                                onClick = { onNavigate(tile.item.url) },
                                onLongClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    shortcutForAction = tile.item
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        is QuickAccessTile.AddTile -> {
                            AddShortcutTile(
                                onClick = { showAddDialog = true },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                // Fill empty slots so columns stay strictly uniform in width
                val remainingSlots = 4 - rowTiles.size
                for (i in 0 until remainingSlots) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // 5. Antidetect Engine Status Banner
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = OctoSurfaceElevated.copy(alpha = 0.6f),
            border = BorderStroke(1.dp, OctoBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = OctoSuccess,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Hardware Fingerprint Isolation Active",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = OctoTextPrimary
                    )
                    Text(
                        text = "WebRTC leaks, WebGL hashes & ClientHints strictly bounded.",
                        style = MaterialTheme.typography.bodySmall,
                        color = OctoTextMuted,
                        fontSize = 11.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
    }

    // --- DIALOG 1: Long Press Options (Edit / Delete) ---
    if (shortcutForAction != null) {
        val targetItem = shortcutForAction!!
        val brandColor = parseColorHex(targetItem.brandColorHex)

        AlertDialog(
            onDismissRequest = { shortcutForAction = null },
            containerColor = OctoSurface,
            shape = RoundedCornerShape(20.dp),
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .background(brandColor.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                            .border(BorderStroke(1.dp, brandColor.copy(alpha = 0.4f)), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = resolveQuickAccessIcon(targetItem.iconType),
                            contentDescription = null,
                            tint = brandColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = targetItem.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = OctoTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = targetItem.url.removePrefix("https://").removePrefix("http://"),
                            style = MaterialTheme.typography.bodySmall,
                            color = OctoTextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    // Option 1: Edit
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = OctoSurfaceElevated,
                        border = BorderStroke(1.dp, OctoBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                itemBeingEdited = targetItem
                                shortcutForAction = null
                                showEditDialog = true
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit",
                                tint = OctoPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Text(
                                text = "Edit Shortcut",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = OctoTextPrimary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Option 2: Delete
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = OctoSurfaceElevated,
                        border = BorderStroke(1.dp, OctoBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val updated = shortcuts.filter { it.id != targetItem.id }
                                shortcuts = updated
                                QuickAccessRepository.saveShortcuts(context, updated)
                                shortcutForAction = null
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = OctoDanger,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Text(
                                text = "Delete Shortcut",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = OctoDanger
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { shortcutForAction = null }) {
                    Text("Close", color = OctoTextMuted)
                }
            }
        )
    }

    // --- DIALOG 2: Edit Shortcut Dialog ---
    if (showEditDialog && itemBeingEdited != null) {
        val current = itemBeingEdited!!
        var editName by remember(current) { mutableStateOf(current.name) }
        var editUrl by remember(current) { mutableStateOf(current.url) }
        var selectedColor by remember(current) { mutableStateOf(current.brandColorHex) }

        AlertDialog(
            onDismissRequest = {
                showEditDialog = false
                itemBeingEdited = null
            },
            containerColor = OctoSurface,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text("Edit Shortcut", color = OctoTextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("Site Name", color = OctoTextSecondary) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = OctoTextPrimary,
                            unfocusedTextColor = OctoTextPrimary,
                            focusedBorderColor = OctoPrimary,
                            unfocusedBorderColor = OctoBorder,
                            focusedContainerColor = OctoSurfaceElevated,
                            unfocusedContainerColor = OctoSurfaceElevated
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = editUrl,
                        onValueChange = { editUrl = it },
                        label = { Text("Site URL", color = OctoTextSecondary) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = OctoTextPrimary,
                            unfocusedTextColor = OctoTextPrimary,
                            focusedBorderColor = OctoPrimary,
                            unfocusedBorderColor = OctoBorder,
                            focusedContainerColor = OctoSurfaceElevated,
                            unfocusedContainerColor = OctoSurfaceElevated
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text("Accent Color", style = MaterialTheme.typography.labelSmall, color = OctoTextSecondary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        presetColors.forEach { hex ->
                            val c = parseColorHex(hex)
                            val isSelected = selectedColor.equals(hex, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(c, CircleShape)
                                    .border(
                                        BorderStroke(
                                            if (isSelected) 2.5.dp else 1.dp,
                                            if (isSelected) Color.White else Color.Transparent
                                        ),
                                        CircleShape
                                    )
                                    .clickable { selectedColor = hex },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (editName.isNotBlank() && editUrl.isNotBlank()) {
                            val normalized = normalizeWebUrl(editUrl)
                            val updated = shortcuts.map {
                                if (it.id == current.id) {
                                    it.copy(name = editName.trim(), url = normalized, brandColorHex = selectedColor)
                                } else it
                            }
                            shortcuts = updated
                            QuickAccessRepository.saveShortcuts(context, updated)
                            showEditDialog = false
                            itemBeingEdited = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = OctoPrimary),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Save", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showEditDialog = false
                    itemBeingEdited = null
                }) {
                    Text("Cancel", color = OctoTextMuted)
                }
            }
        )
    }

    // --- DIALOG 3: Add Shortcut Dialog ---
    if (showAddDialog) {
        var newName by remember { mutableStateOf("") }
        var newUrl by remember { mutableStateOf("") }
        var selectedColor by remember { mutableStateOf("#4285F4") }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            containerColor = OctoSurface,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text("Add Quick Access Site", color = OctoTextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Site Name (e.g. Bing)", color = OctoTextSecondary) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = OctoTextPrimary,
                            unfocusedTextColor = OctoTextPrimary,
                            focusedBorderColor = OctoPrimary,
                            unfocusedBorderColor = OctoBorder,
                            focusedContainerColor = OctoSurfaceElevated,
                            unfocusedContainerColor = OctoSurfaceElevated
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = newUrl,
                        onValueChange = { newUrl = it },
                        label = { Text("Site URL (e.g. bing.com)", color = OctoTextSecondary) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = OctoTextPrimary,
                            unfocusedTextColor = OctoTextPrimary,
                            focusedBorderColor = OctoPrimary,
                            unfocusedBorderColor = OctoBorder,
                            focusedContainerColor = OctoSurfaceElevated,
                            unfocusedContainerColor = OctoSurfaceElevated
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text("Accent Color", style = MaterialTheme.typography.labelSmall, color = OctoTextSecondary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        presetColors.forEach { hex ->
                            val c = parseColorHex(hex)
                            val isSelected = selectedColor.equals(hex, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(c, CircleShape)
                                    .border(
                                        BorderStroke(
                                            if (isSelected) 2.5.dp else 1.dp,
                                            if (isSelected) Color.White else Color.Transparent
                                        ),
                                        CircleShape
                                    )
                                    .clickable { selectedColor = hex },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newName.isNotBlank() && newUrl.isNotBlank()) {
                            val normalized = normalizeWebUrl(newUrl)
                            val newItem = QuickAccessItem(
                                id = UUID.randomUUID().toString(),
                                name = newName.trim(),
                                url = normalized,
                                brandColorHex = selectedColor,
                                iconType = "globe"
                            )
                            val updated = shortcuts + newItem
                            shortcuts = updated
                            QuickAccessRepository.saveShortcuts(context, updated)
                            showAddDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = OctoPrimary),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Add Site", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel", color = OctoTextMuted)
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShortcutTile(
    item: QuickAccessItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val brandColor = parseColorHex(item.brandColorHex)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(brandColor.copy(alpha = 0.15f), RoundedCornerShape(14.dp))
                .border(BorderStroke(1.dp, brandColor.copy(alpha = 0.35f)), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = resolveQuickAccessIcon(item.iconType),
                contentDescription = item.name,
                tint = brandColor,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = item.name,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = OctoTextPrimary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AddShortcutTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(OctoSurfaceElevated, RoundedCornerShape(14.dp))
                .border(BorderStroke(1.dp, OctoBorder.copy(alpha = 0.8f)), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Add Shortcut",
                tint = OctoPrimary,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "+ Add",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = OctoTextSecondary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun StatusChip(
    label: String,
    isProtected: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isProtected) OctoSuccess.copy(alpha = 0.12f) else OctoSurfaceElevated,
        border = BorderStroke(1.dp, if (isProtected) OctoSuccess.copy(alpha = 0.4f) else OctoBorder),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .background(if (isProtected) OctoSuccess else OctoWarning, CircleShape)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = if (isProtected) OctoSuccess else OctoTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
