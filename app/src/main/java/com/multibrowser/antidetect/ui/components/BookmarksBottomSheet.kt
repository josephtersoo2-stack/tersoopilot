package com.multibrowser.antidetect.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.multibrowser.antidetect.ui.theme.*

data class BookmarkItem(
    val title: String,
    val url: String,
    val category: String = "Popular"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksBottomSheet(
    currentUrl: String,
    onNavigate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // Default bookmarks including the ones moved from the top bar
    val defaultBookmarks = remember {
        listOf(
            BookmarkItem("Google", "https://www.google.com", "Search"),
            BookmarkItem("YouTube", "https://m.youtube.com", "Media"),
            BookmarkItem("Iphey", "https://iphey.com", "Anti-Detect Test"),
            BookmarkItem("CreepJS", "https://abrahamjuliot.github.io/creepjs/", "Anti-Detect Test"),
            BookmarkItem("BrowserLeaks", "https://browserleaks.com", "Fingerprint Test"),
            BookmarkItem("Pixelscan", "https://pixelscan.net", "Fingerprint Test"),
            BookmarkItem("Whoer", "https://whoer.net", "IP / Proxy Test")
        )
    }

    var customBookmarks by remember { mutableStateOf<List<BookmarkItem>>(emptyList()) }

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
                        Icons.Default.Bookmark,
                        contentDescription = null,
                        tint = OctoPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        "Bookmarks",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = OctoTextPrimary
                    )
                }

                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = OctoTextMuted)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Bookmark current page action
            if (currentUrl.isNotBlank() && currentUrl.startsWith("http")) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = OctoSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val newB = BookmarkItem("Saved Page", currentUrl, "User Saved")
                            if (!customBookmarks.any { it.url == currentUrl }) {
                                customBookmarks = customBookmarks + newB
                            }
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
                                .background(OctoPrimary.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.BookmarkAdd,
                                contentDescription = null,
                                tint = OctoPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Bookmark current page",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = OctoTextPrimary
                            )
                            Text(
                                currentUrl,
                                style = MaterialTheme.typography.labelSmall,
                                color = OctoTextMuted,
                                maxLines = 1
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // List of Bookmarks
            val allBookmarks = customBookmarks + defaultBookmarks
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(allBookmarks) { item ->
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
                                    Icons.Default.Public,
                                    contentDescription = null,
                                    tint = OctoTextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    item.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = OctoTextPrimary
                                )
                                Text(
                                    item.url,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = OctoTextMuted,
                                    maxLines = 1
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = OctoSurfaceElevated
                            ) {
                                Text(
                                    item.category,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = OctoTextSecondary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
