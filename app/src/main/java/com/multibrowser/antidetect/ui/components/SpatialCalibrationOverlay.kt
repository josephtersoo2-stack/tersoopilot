package com.multibrowser.antidetect.ui.components

import android.view.View
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multibrowser.antidetect.automation.NativeGestureInjector
import com.multibrowser.antidetect.automation.perception.AnchorRegistry
import com.multibrowser.antidetect.network.GhostPilotApiService
import com.multibrowser.antidetect.network.RetrofitInstance
import com.multibrowser.antidetect.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun SpatialCalibrationOverlay(
    targetGeckoView: View?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val api = remember { RetrofitInstance.retrofit.create(GhostPilotApiService::class.java) }

    // List of canonical anchors to calibrate
    val anchorKeys = remember {
        listOf(
            AnchorRegistry.SEARCH_BUTTON_HOME,
            AnchorRegistry.SEARCH_BUTTON_WATCH,
            AnchorRegistry.SEARCH_BUTTON_RESULTS,
            AnchorRegistry.SEARCH_INPUT,
            AnchorRegistry.SEARCH_CLEAR,
            AnchorRegistry.SEARCH_SUBMIT,
            AnchorRegistry.LIKE_BUTTON,
            AnchorRegistry.DISLIKE_BUTTON,
            AnchorRegistry.SHARE_BUTTON,
            AnchorRegistry.SUBSCRIBE_BUTTON,
            AnchorRegistry.COMMENTS_SECTION,
            AnchorRegistry.DESCRIPTION_EXPAND,
            AnchorRegistry.VIDEO_MENU_DOTS,
            AnchorRegistry.NAV_HOME,
            AnchorRegistry.NAV_SHORTS,
            AnchorRegistry.NAV_SUBSCRIPTIONS,
            AnchorRegistry.NAV_PROFILE,
            AnchorRegistry.PLAYER_PLAY_PAUSE,
            AnchorRegistry.PLAYER_QUALITY_MENU
        )
    }

    var selectedAnchor by remember { mutableStateOf(AnchorRegistry.SEARCH_BUTTON_HOME) }
    var tappedLocalPoint by remember { mutableStateOf<Offset?>(null) }
    var currentNormX by remember { mutableStateOf<Int?>(null) }
    var currentNormY by remember { mutableStateOf<Int?>(null) }
    var physicalScreenX by remember { mutableStateOf<Float?>(null) }
    var physicalScreenY by remember { mutableStateOf<Float?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var lastSavedMessage by remember { mutableStateOf<String?>(null) }

    // When selecting a new anchor, display its currently saved coordinates
    LaunchedEffect(selectedAnchor) {
        val anchor = AnchorRegistry.getAnchor(selectedAnchor)
        currentNormX = anchor.x
        currentNormY = anchor.y

        if (targetGeckoView != null) {
            val metrics = targetGeckoView.resources.displayMetrics
            val screenW = metrics.widthPixels.toFloat()
            val screenH = metrics.heightPixels.toFloat()

            val physX = (anchor.x / 1000f) * screenW
            val physY = (anchor.y / 1000f) * screenH
            physicalScreenX = physX
            physicalScreenY = physY

            val loc = IntArray(2)
            targetGeckoView.getLocationOnScreen(loc)
            val locX = physX - loc[0]
            val locY = physY - loc[1]
            tappedLocalPoint = Offset(locX, locY)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 1. Transparent Touch-Interception Layer over GeckoView
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(selectedAnchor) {
                    detectTapGestures { offset ->
                        val view = targetGeckoView ?: return@detectTapGestures
                        val location = IntArray(2)
                        view.getLocationOnScreen(location)

                        val metrics = view.resources.displayMetrics
                        val screenW = metrics.widthPixels.toFloat()
                        val screenH = metrics.heightPixels.toFloat()

                        val locX = offset.x
                        val locY = offset.y
                        val physX = locX + location[0]
                        val physY = locY + location[1]

                        val nX = ((physX / screenW) * 1000f).roundToInt().coerceIn(0, 1000)
                        val nY = ((physY / screenH) * 1000f).roundToInt().coerceIn(0, 1000)

                        tappedLocalPoint = offset
                        physicalScreenX = physX
                        physicalScreenY = physY
                        currentNormX = nX
                        currentNormY = nY
                        lastSavedMessage = null
                    }
                }
        )

        // 2. Visual Crosshair Reticle placed at tapped location
        tappedLocalPoint?.let { pt ->
            Box(
                modifier = Modifier
                    .offset(x = (pt.x - 24).dp, y = (pt.y - 24).dp)
                    .size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                // Outer Pulse Ring
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(OctoPrimary.copy(alpha = 0.25f), CircleShape)
                        .border(1.5.dp, OctoPrimary, CircleShape)
                )
                // Center Dot
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(Color.Cyan, CircleShape)
                )
                // Label tag
                Surface(
                    color = Color.Black.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(0.8.dp, OctoPrimary),
                    modifier = Modifier
                        .offset(y = (-30).dp)
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "$selectedAnchor (${currentNormX ?: 0}, ${currentNormY ?: 0})",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Cyan
                    )
                }
            }
        }

        // 3. Floating Bottom Calibration Control Panel
        Surface(
            color = OctoSurfaceElevated,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            border = BorderStroke(1.dp, OctoBorder),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Top Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(OctoPrimary.copy(alpha = 0.2f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Adjust,
                                contentDescription = null,
                                tint = OctoPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Column {
                            Text(
                                "Live Spatial Anchor Calibrator",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = OctoTextPrimary
                            )
                            Text(
                                "Tap exact element on webpage to calibrate spot",
                                style = MaterialTheme.typography.labelSmall,
                                color = OctoTextSecondary
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Exit Calibration",
                            tint = OctoTextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Horizontal Anchor Selector Chips
                val scrollState = rememberScrollState()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(scrollState),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    anchorKeys.forEach { key ->
                        val isSelected = selectedAnchor == key
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) OctoPrimary else OctoSurface,
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) OctoPrimary else OctoBorder
                            ),
                            modifier = Modifier.clickable {
                                selectedAnchor = key
                            }
                        ) {
                            Text(
                                text = key,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) Color.White else OctoTextSecondary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                // Coordinates & Status readout
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = OctoSurface,
                    border = BorderStroke(1.dp, OctoBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Selected: $selectedAnchor",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            if (physicalScreenX != null && physicalScreenY != null && currentNormX != null && currentNormY != null) {
                                Text(
                                    text = "Screen: (${physicalScreenX!!.toInt()}, ${physicalScreenY!!.toInt()}) • Norm: (${currentNormX}, ${currentNormY})",
                                    fontSize = 11.sp,
                                    color = OctoTextSecondary
                                )
                            } else {
                                Text(
                                    text = "Tap on webpage to set spot",
                                    fontSize = 11.sp,
                                    color = Color.Yellow
                                )
                            }
                        }

                        lastSavedMessage?.let { msg ->
                            Text(
                                text = msg,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = OctoSuccess
                            )
                        }
                    }
                }

                // Action Buttons Row: Test Tap & Save & Sync
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Test Tap Button
                    OutlinedButton(
                        onClick = {
                            val view = targetGeckoView ?: return@OutlinedButton
                            val pt = tappedLocalPoint ?: return@OutlinedButton
                            isTesting = true
                            coroutineScope.launch {
                                try {
                                    val injector = NativeGestureInjector(view)
                                    injector.injectTap(pt.x, pt.y)
                                    Toast.makeText(
                                        context,
                                        "🎯 Simulated tap on $selectedAnchor at (${pt.x.toInt()}, ${pt.y.toInt()})",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Tap failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                } finally {
                                    isTesting = false
                                }
                            }
                        },
                        enabled = tappedLocalPoint != null && !isTesting,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, if (tappedLocalPoint != null) OctoPrimary else OctoBorder),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = OctoPrimary
                        )
                    ) {
                        Icon(
                            Icons.Default.AdsClick,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isTesting) "Tapping..." else "Test Tap",
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Save & Sync Button
                    Button(
                        onClick = {
                            val nX = currentNormX ?: return@Button
                            val nY = currentNormY ?: return@Button
                            isSaving = true
                            coroutineScope.launch {
                                try {
                                    // 1. Save locally in device AnchorRegistry & JSON file
                                    AnchorRegistry.get()?.saveAnchor(selectedAnchor, nX, nY)

                                    // 2. Post to Backend control plane
                                    withContext(Dispatchers.IO) {
                                        api.postSingleAnchor(
                                            mapOf(
                                                "platform" to "YOUTUBE",
                                                "anchor_id" to selectedAnchor,
                                                "x" to nX,
                                                "y" to nY,
                                                "label" to selectedAnchor
                                            )
                                        )
                                    }

                                    lastSavedMessage = "✓ Synced ($nX, $nY)"
                                    Toast.makeText(
                                        context,
                                        "✓ Calibrated $selectedAnchor ($nX, $nY) saved & synced to fleet!",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } catch (e: Exception) {
                                    lastSavedMessage = "Saved locally"
                                    Toast.makeText(
                                        context,
                                        "Saved locally (Backend notice: ${e.message})",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } finally {
                                    isSaving = false
                                }
                            }
                        },
                        enabled = currentNormX != null && currentNormY != null && !isSaving,
                        modifier = Modifier.weight(1.2f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = OctoSuccess
                        )
                    ) {
                        Icon(
                            Icons.Default.CloudUpload,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isSaving) "Syncing..." else "Save & Sync",
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}
