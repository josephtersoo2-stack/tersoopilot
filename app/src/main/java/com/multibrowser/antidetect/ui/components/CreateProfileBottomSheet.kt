package com.multibrowser.antidetect.ui.components

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import com.multibrowser.antidetect.data.model.ProfileEntity
import com.multibrowser.antidetect.network.GenerateDeviceRequest
import com.multibrowser.antidetect.network.RetrofitInstance
import com.multibrowser.antidetect.sync.CookieEngine
import com.multibrowser.antidetect.sync.CookieSyncDispatcher
import com.multibrowser.antidetect.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateProfileBottomSheet(
    profileToEdit: ProfileEntity? = null,
    onDismiss: () -> Unit,
    onSaveProfile: (ProfileEntity, Boolean) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    var preloadedCookiesRaw by remember { mutableStateOf("") }

    // Native Storage File Picker Contract (.json or .txt)
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            try {
                context.contentResolver.openInputStream(selectedUri)?.use { stream ->
                    preloadedCookiesRaw = stream.bufferedReader().readText()
                    Toast.makeText(context, "Loaded cookies from file!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to read file: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    var profileName by remember { mutableStateOf(profileToEdit?.name ?: "") }
    var selectedTag by remember { mutableStateOf(profileToEdit?.tag?.ifBlank { "Default" } ?: "Default") }
    var aiQuery by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }

    // Dynamic LLM Provider selection (the backend uses its saved model for this provider)
    var selectedProvider by remember { mutableStateOf("openrouter") }

    // Fingerprint parameters (editable strings for easy manual input and tweaking)
    var brand by remember { mutableStateOf(profileToEdit?.brand ?: "") }
    var selectedModel by remember { mutableStateOf(profileToEdit?.modelName ?: "") }
    var selectedModelCode by remember { mutableStateOf(profileToEdit?.modelCode ?: "") }
    var androidVersion by remember { mutableStateOf(profileToEdit?.androidVersion?.toString() ?: "") }
    var userAgent by remember { mutableStateOf(profileToEdit?.userAgent ?: "") }
    var soc by remember { mutableStateOf(profileToEdit?.soc ?: "") }
    var webGlVendor by remember { mutableStateOf(profileToEdit?.webGlVendor ?: "") }
    var webGlRenderer by remember { mutableStateOf(profileToEdit?.webGlRenderer ?: "") }
    var ramGb by remember { mutableStateOf(profileToEdit?.ramGb?.toString() ?: "") }
    var cpuCores by remember { mutableStateOf(profileToEdit?.cpuCores?.toString() ?: "") }
    var screenWidth by remember { mutableStateOf(profileToEdit?.screenWidth?.toString() ?: "") }
    var screenHeight by remember { mutableStateOf(profileToEdit?.screenHeight?.toString() ?: "") }
    var dpr by remember { mutableStateOf(profileToEdit?.dpr?.toString() ?: "") }

    // Proxy
    var proxyType by remember { mutableStateOf(profileToEdit?.proxyType ?: "DIRECT") }
    var proxyHost by remember { mutableStateOf(profileToEdit?.proxyHost ?: "") }
    var proxyPort by remember {
        mutableStateOf(
            if ((profileToEdit?.proxyPort ?: 0) > 0) profileToEdit!!.proxyPort.toString() else ""
        )
    }
    var proxyUser by remember { mutableStateOf(profileToEdit?.proxyUser ?: "") }
    var proxyPass by remember { mutableStateOf(profileToEdit?.proxyPass ?: "") }
    var showPassword by remember { mutableStateOf(false) }

    // WebRTC
    var webRtcMode by remember { mutableStateOf(profileToEdit?.webRtcMode ?: "Mdns") }

    fun populateSpecs(
        b: String,
        model: String,
        code: String,
        av: String,
        s: String,
        vendor: String,
        renderer: String,
        ram: String,
        cores: String,
        w: String,
        h: String,
        scale: String,
        ua: String
    ) {
        brand = b
        selectedModel = model
        selectedModelCode = code
        androidVersion = av
        soc = s
        webGlVendor = vendor
        webGlRenderer = renderer
        ramGb = ram
        cpuCores = cores
        screenWidth = w
        screenHeight = h
        dpr = scale
        userAgent = ua

        if (profileName.isBlank() || profileName.startsWith("Profile")) {
            profileName = "$b $model".trim()
        }
    }

    fun loadPreset(query: String) {
        val fallback = getOfflineSpecs(query)
        populateSpecs(
            b = fallback.brand,
            model = fallback.modelName,
            code = fallback.modelCode,
            av = fallback.androidVersion.toString(),
            s = fallback.soc,
            vendor = fallback.webGlVendor,
            renderer = fallback.webGlRenderer,
            ram = fallback.ramGb.toString(),
            cores = fallback.cpuCores.toString(),
            w = fallback.screenWidth.toString(),
            h = fallback.screenHeight.toString(),
            scale = fallback.dpr.toString(),
            ua = fallback.userAgent
        )
        Toast.makeText(context, "Loaded preset: ${fallback.brand} ${fallback.modelName}", Toast.LENGTH_SHORT).show()
    }

    fun triggerDeviceGeneration(query: String) {
        if (query.isBlank()) return
        coroutineScope.launch {
            isGenerating = true
            try {
                val res = RetrofitInstance.api.generateDevice(
                    GenerateDeviceRequest(
                        query = query.trim(),
                        provider = selectedProvider
                    )
                )
                populateSpecs(
                    b = res.brand,
                    model = res.modelName,
                    code = res.modelCode,
                    av = res.androidVersion.toString(),
                    s = res.soc,
                    vendor = res.webGlVendor,
                    renderer = res.webGlRenderer,
                    ram = res.ramGb.toString(),
                    cores = res.cpuCores.toString(),
                    w = res.screenWidth.toString(),
                    h = res.screenHeight.toString(),
                    scale = res.dpr.toString(),
                    ua = res.userAgent
                )
                Toast.makeText(context, "Generated specs for ${res.brand} ${res.modelName}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                val err = e.localizedMessage ?: e.message ?: "Connection error"
                Toast.makeText(context, "Generation error: $err", Toast.LENGTH_LONG).show()
            } finally {
                isGenerating = false
            }
        }
    }

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
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        if (profileToEdit != null) "Edit Profile" else "New Browser Profile",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = OctoTextPrimary
                    )
                    Text(
                        if (profileToEdit != null) "Modify hardware fingerprint & proxy parameters" else "Isolated storage & native hardware fingerprint",
                        style = MaterialTheme.typography.bodySmall,
                        color = OctoTextSecondary
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = OctoTextSecondary)
                }
            }

            HorizontalDivider(color = OctoBorder, thickness = 0.8.dp)

            // Profile Identity: Name & Category
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "PROFILE IDENTITY",
                    style = MaterialTheme.typography.labelSmall,
                    color = OctoTextMuted,
                    fontWeight = FontWeight.Bold
                )

                OutlinedTextField(
                    value = profileName,
                    onValueChange = { profileName = it },
                    label = { Text("Profile Name") },
                    placeholder = { Text("e.g. Personal OnePlus 12") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = octoElevatedTextFieldColors()
                )

                // Category Tag Chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("Default", "Work", "Personal", "Crypto", "Social").forEach { tag ->
                        val isSelected = selectedTag == tag
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedTag = tag },
                            label = { Text(tag, style = MaterialTheme.typography.bodySmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = OctoPrimary.copy(alpha = 0.2f),
                                selectedLabelColor = OctoPrimary
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = isSelected,
                                borderColor = if (isSelected) OctoPrimary else OctoBorder
                            )
                        )
                    }
                }
            }

            // AI Device Blueprint Generator Box
            Card(
                colors = CardDefaults.cardColors(containerColor = OctoSurfaceElevated),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, OctoBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = OctoPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                "AI Hardware Generator",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = OctoTextPrimary
                            )
                        }
                        Surface(
                            color = OctoPrimary.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                if (selectedProvider == "openrouter") "OpenRouter.ai" else "Google Gemini",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = OctoPrimary
                            )
                        }
                    }

                    // LLM Provider Toggle Chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            "openrouter" to "OpenRouter.ai",
                            "gemini" to "Google Gemini"
                        ).forEach { (prov, label) ->
                            val isSel = selectedProvider == prov
                            FilterChip(
                                selected = isSel,
                                onClick = { selectedProvider = prov },
                                label = { Text(label, fontSize = 12.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = OctoPrimary.copy(alpha = 0.2f),
                                    selectedLabelColor = OctoPrimary
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = isSel,
                                    borderColor = if (isSel) OctoPrimary else OctoBorder
                                )
                            )
                        }
                    }

                    // Backend Saved Model Info
                    Surface(
                        color = OctoSurface,
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, OctoBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.CloudDone,
                                contentDescription = null,
                                tint = OctoPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                "Using saved backend model for ${if (selectedProvider == "openrouter") "OpenRouter.ai" else "Google Gemini"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = OctoTextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Text(
                        "Verified real-world hardware: GPU renderer, SoC, DPR and User-Agent.",
                        style = MaterialTheme.typography.bodySmall,
                        color = OctoTextSecondary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = aiQuery,
                            onValueChange = { aiQuery = it },
                            placeholder = { Text("e.g. OnePlus 12, S24 Ultra, Pixel 8") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            colors = octoTextFieldColors()
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        FilledIconButton(
                            onClick = { triggerDeviceGeneration(aiQuery) },
                            enabled = !isGenerating && aiQuery.isNotBlank(),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = OctoPrimary
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.size(48.dp)
                        ) {
                            if (isGenerating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.AutoAwesome, contentDescription = "Generate")
                            }
                        }
                    }

                    // Quick Suggestion Preset Chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("itel S26 Ultra", "Galaxy S24", "Pixel 8", "OnePlus 12").forEach { preset ->
                            SuggestionChip(
                                onClick = {
                                    aiQuery = preset
                                    triggerDeviceGeneration(preset)
                                },
                                label = { Text(preset, fontSize = 12.sp) },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = OctoSurface,
                                    labelColor = OctoTextSecondary
                                ),
                                border = SuggestionChipDefaults.suggestionChipBorder(
                                    enabled = true,
                                    borderColor = OctoBorder
                                )
                            )
                        }
                    }

                    AnimatedVisibility(visible = isGenerating) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = OctoPrimary,
                                trackColor = OctoBorder
                            )
                            Text(
                                "Querying live spec sites (GSMArena, etc.) & Google AI...",
                                style = MaterialTheme.typography.labelSmall,
                                color = OctoPrimary
                            )
                        }
                    }
                }
            }

            // Specs Card: Fully Editable Device Hardware Specifications
            val hasHardwareSpecs = brand.isNotBlank() || selectedModel.isNotBlank() || soc.isNotBlank()

            Card(
                colors = CardDefaults.cardColors(containerColor = OctoSurfaceElevated),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (hasHardwareSpecs) OctoPrimary.copy(alpha = 0.5f) else OctoBorder
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.PhoneAndroid,
                                contentDescription = null,
                                tint = if (hasHardwareSpecs) OctoPrimary else OctoTextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                "DEVICE HARDWARE SPECIFICATIONS",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (hasHardwareSpecs) OctoTextPrimary else OctoTextMuted,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (hasHardwareSpecs) {
                                TextButton(
                                    onClick = {
                                        brand = ""
                                        selectedModel = ""
                                        selectedModelCode = ""
                                        androidVersion = ""
                                        soc = ""
                                        webGlVendor = ""
                                        webGlRenderer = ""
                                        ramGb = ""
                                        cpuCores = ""
                                        screenWidth = ""
                                        screenHeight = ""
                                        dpr = ""
                                        userAgent = ""
                                    },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                    modifier = Modifier.height(24.dp)
                                ) {
                                    Text("Clear", style = MaterialTheme.typography.labelSmall, color = OctoDanger)
                                }
                            }

                            Surface(
                                color = if (hasHardwareSpecs) OctoSuccess.copy(alpha = 0.15f) else OctoBorder.copy(alpha = 0.3f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    if (hasHardwareSpecs) "Configured (Editable)" else "Empty",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (hasHardwareSpecs) OctoSuccess else OctoTextMuted
                                )
                            }
                        }
                    }

                    if (!hasHardwareSpecs) {
                        Surface(
                            color = OctoSurface,
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, OctoBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = null,
                                    tint = OctoTextMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    "No hardware selected. Generate with AI above or enter specs manually below.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = OctoTextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    // 1. Brand & Model Name Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = brand,
                            onValueChange = { brand = it },
                            label = { Text("Brand", fontSize = 11.sp) },
                            placeholder = { Text("e.g. itel, Samsung", fontSize = 11.sp) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            colors = octoTextFieldColors()
                        )
                        OutlinedTextField(
                            value = selectedModel,
                            onValueChange = { selectedModel = it },
                            label = { Text("Model Name", fontSize = 11.sp) },
                            placeholder = { Text("e.g. S26 Ultra, S24", fontSize = 11.sp) },
                            modifier = Modifier.weight(1.3f),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            colors = octoTextFieldColors()
                        )
                    }

                    // 2. Model Code & Android Version Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = selectedModelCode,
                            onValueChange = { selectedModelCode = it },
                            label = { Text("Model Code / SKU", fontSize = 11.sp) },
                            placeholder = { Text("e.g. S698LN, CPH2581", fontSize = 11.sp) },
                            modifier = Modifier.weight(1.3f),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            colors = octoTextFieldColors()
                        )
                        OutlinedTextField(
                            value = androidVersion,
                            onValueChange = { androidVersion = it },
                            label = { Text("Android OS", fontSize = 11.sp) },
                            placeholder = { Text("14 or 15", fontSize = 11.sp) },
                            modifier = Modifier.weight(0.7f),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            colors = octoTextFieldColors()
                        )
                    }

                    // 3. SoC Chipset & GPU Renderer Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = soc,
                            onValueChange = { soc = it },
                            label = { Text("SoC Chipset", fontSize = 11.sp) },
                            placeholder = { Text("e.g. Unisoc T7300, Snapdragon 8 Gen 3", fontSize = 11.sp) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            colors = octoTextFieldColors()
                        )
                        OutlinedTextField(
                            value = webGlRenderer,
                            onValueChange = { webGlRenderer = it },
                            label = { Text("GPU Renderer", fontSize = 11.sp) },
                            placeholder = { Text("e.g. Mali-G57 MP2, Adreno 750", fontSize = 11.sp) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            colors = octoTextFieldColors()
                        )
                    }

                    // 4. WebGL Vendor, RAM & CPU Cores Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = webGlVendor,
                            onValueChange = { webGlVendor = it },
                            label = { Text("WebGL Vendor", fontSize = 11.sp) },
                            placeholder = { Text("e.g. ARM, Qualcomm", fontSize = 11.sp) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            colors = octoTextFieldColors()
                        )
                        OutlinedTextField(
                            value = ramGb,
                            onValueChange = { ramGb = it },
                            label = { Text("RAM (GB)", fontSize = 11.sp) },
                            placeholder = { Text("8, 12, 16", fontSize = 11.sp) },
                            modifier = Modifier.weight(0.65f),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            colors = octoTextFieldColors()
                        )
                        OutlinedTextField(
                            value = cpuCores,
                            onValueChange = { cpuCores = it },
                            label = { Text("Cores", fontSize = 11.sp) },
                            placeholder = { Text("8", fontSize = 11.sp) },
                            modifier = Modifier.weight(0.55f),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            colors = octoTextFieldColors()
                        )
                    }

                    // 5. Screen Width, Height & DPR Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = screenWidth,
                            onValueChange = { screenWidth = it },
                            label = { Text("Width (px)", fontSize = 11.sp) },
                            placeholder = { Text("360, 450", fontSize = 11.sp) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            colors = octoTextFieldColors()
                        )
                        OutlinedTextField(
                            value = screenHeight,
                            onValueChange = { screenHeight = it },
                            label = { Text("Height (px)", fontSize = 11.sp) },
                            placeholder = { Text("800, 1000", fontSize = 11.sp) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            colors = octoTextFieldColors()
                        )
                        OutlinedTextField(
                            value = dpr,
                            onValueChange = { dpr = it },
                            label = { Text("DPR Scale", fontSize = 11.sp) },
                            placeholder = { Text("3.0, 3.2", fontSize = 11.sp) },
                            modifier = Modifier.weight(0.85f),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            colors = octoTextFieldColors()
                        )
                    }

                    // 6. User-Agent
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("User-Agent", style = MaterialTheme.typography.labelSmall, color = OctoTextMuted)
                            if (userAgent.isBlank() && androidVersion.isNotBlank()) {
                                TextButton(
                                    onClick = {
                                        val v = androidVersion.ifBlank { "14" }
                                        userAgent = "Mozilla/5.0 (Android $v; Mobile; rv:135.0) Gecko/135.0 Firefox/135.0"
                                    },
                                    contentPadding = PaddingValues(0.dp),
                                    modifier = Modifier.height(20.dp)
                                ) {
                                    Text("Auto-generate UA", style = MaterialTheme.typography.labelSmall, color = OctoPrimary)
                                }
                            }
                        }

                        OutlinedTextField(
                            value = userAgent,
                            onValueChange = { userAgent = it },
                            placeholder = { Text("e.g. Mozilla/5.0 (Android 14; Mobile; rv:135.0) Gecko/135.0 Firefox/135.0", fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 3,
                            shape = RoundedCornerShape(8.dp),
                            colors = octoTextFieldColors()
                        )
                    }
                }
            }

            // Network & Proxy Settings
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "NETWORK & PROXY",
                    style = MaterialTheme.typography.labelSmall,
                    color = OctoTextMuted,
                    fontWeight = FontWeight.Bold
                )

                // Proxy Type Selector Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("DIRECT", "HTTP", "SOCKS5").forEach { type ->
                        val isSelected = proxyType == type
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { proxyType = type },
                            color = if (isSelected) OctoPrimary else OctoSurfaceElevated,
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isSelected) OctoPrimary else OctoBorder
                            )
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    type,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isSelected) Color.White else OctoTextSecondary
                                )
                            }
                        }
                    }
                }

                if (proxyType != "DIRECT") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = proxyHost,
                            onValueChange = { proxyHost = it },
                            label = { Text("Proxy Host / IP") },
                            placeholder = { Text("192.168.1.1 or proxy.io") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = octoElevatedTextFieldColors()
                        )
                        OutlinedTextField(
                            value = proxyPort,
                            onValueChange = { proxyPort = it },
                            label = { Text("Port") },
                            placeholder = { Text("8080") },
                            modifier = Modifier.width(100.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = octoElevatedTextFieldColors()
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = proxyUser,
                            onValueChange = { proxyUser = it },
                            label = { Text("Username (Optional)") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = octoElevatedTextFieldColors()
                        )
                        OutlinedTextField(
                            value = proxyPass,
                            onValueChange = { proxyPass = it },
                            label = { Text("Password") },
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showPassword = !showPassword }) {
                                    Icon(
                                        if (showPassword) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = null,
                                        tint = OctoTextMuted
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = octoElevatedTextFieldColors()
                        )
                    }
                }
            }

            // WebRTC Mode Selector
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "WEBRTC PRIVACY PROTECTION",
                    style = MaterialTheme.typography.labelSmall,
                    color = OctoTextMuted,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("Mdns" to "MDNS Protected", "Disabled" to "Disable WebRTC", "Direct" to "Direct IP").forEach { (mode, label) ->
                        val isSelected = webRtcMode == mode
                        FilterChip(
                            selected = isSelected,
                            onClick = { webRtcMode = mode },
                            label = { Text(label, style = MaterialTheme.typography.bodySmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = OctoPrimary.copy(alpha = 0.2f),
                                selectedLabelColor = OctoPrimary
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = isSelected,
                                borderColor = if (isSelected) OctoPrimary else OctoBorder
                            )
                        )
                    }
                }
            }

            // Pre-Populate Cookies (Optional)
            // Interactive Pre-Populate Cookie Section
            Card(
                colors = CardDefaults.cardColors(containerColor = OctoSurfaceElevated),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, OctoBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Pre-Populate Cookies (Optional)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = OctoTextPrimary
                            )
                            Text(
                                "Paste JSON/Netscape or pick a file",
                                style = MaterialTheme.typography.bodySmall,
                                color = OctoTextMuted
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            // Direct Clipboard Paste
                            IconButton(onClick = {
                                clipboardManager.getText()?.text?.let { clipText ->
                                    preloadedCookiesRaw = clipText
                                    Toast.makeText(context, "Pasted from clipboard!", Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Icon(Icons.Default.ContentPaste, contentDescription = "Paste Clipboard", tint = OctoPrimary)
                            }

                            // Storage File Picker
                            IconButton(onClick = { filePickerLauncher.launch("*/*") }) {
                                Icon(Icons.Default.AttachFile, contentDescription = "Attach File", tint = Color(0xFF34C759))
                            }
                        }
                    }

                    // Active OutlinedTextField
                    OutlinedTextField(
                        value = preloadedCookiesRaw,
                        onValueChange = { preloadedCookiesRaw = it },
                        placeholder = {
                            Text(
                                "[{\"name\":\"SID\",\"value\":\"...\",\"domain\":\".google.com\"}]",
                                color = OctoTextMuted,
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = OctoTextPrimary,
                            unfocusedTextColor = OctoTextPrimary,
                            focusedBorderColor = OctoPrimary,
                            unfocusedBorderColor = OctoBorder,
                            focusedContainerColor = OctoSurface,
                            unfocusedContainerColor = OctoSurface
                        ),
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Action Buttons
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                fun buildProfile(): ProfileEntity? {
                    val finalBrand = brand.trim()
                    val finalModel = selectedModel.trim()
                    if (finalBrand.isBlank() && finalModel.isBlank()) {
                        Toast.makeText(context, "Please generate or enter device brand and model first", Toast.LENGTH_SHORT).show()
                        return null
                    }
                    val portInt = proxyPort.toIntOrNull() ?: 0
                    val b = finalBrand.ifBlank { "Generic" }
                    val m = finalModel.ifBlank { "Android Device" }
                    val finalName = if (profileName.isBlank()) "$b $m" else profileName.trim()
                    val av = androidVersion.trim().toIntOrNull() ?: 14
                    val finalUa = userAgent.trim().ifBlank {
                        "Mozilla/5.0 (Android $av; Mobile; rv:135.0) Gecko/135.0 Firefox/135.0"
                    }

                    val profileId = profileToEdit?.id ?: java.util.UUID.randomUUID().toString()
                    var initialCookieCount = profileToEdit?.cookieCount ?: 0

                    // Ingest cookies immediately if provided during creation
                    if (preloadedCookiesRaw.isNotBlank()) {
                        val imported = CookieEngine.importCookiesFromJson(context, profileId, preloadedCookiesRaw)
                        initialCookieCount = imported

                        // Real-Time Django REST Backend Sync
                        coroutineScope.launch {
                            CookieSyncDispatcher.syncCookiesToBackend(profileId, preloadedCookiesRaw)
                        }
                    }

                    return ProfileEntity(
                        id = profileId,
                        name = finalName,
                        tag = selectedTag,
                        brand = b,
                        modelName = m,
                        modelCode = selectedModelCode.trim().ifBlank { "GenericCode" },
                        androidVersion = av,
                        userAgent = finalUa,
                        soc = soc.trim().ifBlank { "Generic SoC" },
                        webGlVendor = webGlVendor.trim().ifBlank { "ARM" },
                        webGlRenderer = webGlRenderer.trim().ifBlank { "Mali-G57" },
                        ramGb = ramGb.trim().toIntOrNull() ?: 8,
                        cpuCores = cpuCores.trim().toIntOrNull() ?: 8,
                        screenWidth = screenWidth.trim().toIntOrNull() ?: 360,
                        screenHeight = screenHeight.trim().toIntOrNull() ?: 800,
                        dpr = dpr.trim().toDoubleOrNull() ?: 3.0,
                        proxyType = proxyType.uppercase(),
                        proxyHost = proxyHost.trim(),
                        proxyPort = portInt,
                        proxyUser = proxyUser.trim(),
                        proxyPass = proxyPass.trim(),
                        webRtcMode = webRtcMode,
                        lastUsedTimestamp = profileToEdit?.lastUsedTimestamp ?: 0L,
                        cookieCount = initialCookieCount,
                        selectedCameraVideoPath = profileToEdit?.selectedCameraVideoPath
                    )
                }

                Button(
                    onClick = {
                        val profile = buildProfile() ?: return@Button
                        onSaveProfile(profile, true) // Save & Launch
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = OctoPrimary)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (profileToEdit != null) "Save & Launch" else "Save & Launch Profile",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                OutlinedButton(
                    onClick = {
                        val profile = buildProfile() ?: return@OutlinedButton
                        onSaveProfile(profile, false) // Save only
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = OctoTextPrimary),
                    border = androidx.compose.foundation.BorderStroke(1.dp, OctoBorder)
                ) {
                    Text(
                        if (profileToEdit != null) "Save Changes" else "Save Profile Only",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }

}

private data class OfflineSpecs(
    val brand: String,
    val modelName: String,
    val modelCode: String,
    val androidVersion: Int,
    val soc: String,
    val webGlVendor: String,
    val webGlRenderer: String,
    val ramGb: Int,
    val cpuCores: Int,
    val screenWidth: Int,
    val screenHeight: Int,
    val dpr: Double,
    val userAgent: String
)

private fun getOfflineSpecs(query: String): OfflineSpecs {
    val q = query.lowercase()
    return when {
        q.contains("itel") || q.contains("s26") -> OfflineSpecs(
            brand = "itel",
            modelName = "S26 Ultra",
            modelCode = "S698LN",
            androidVersion = 15,
            soc = "Unisoc T7300",
            webGlVendor = "ARM",
            webGlRenderer = "Mali-G57 MP2",
            ramGb = 8,
            cpuCores = 8,
            screenWidth = 360,
            screenHeight = 812,
            dpr = 3.0,
            userAgent = "Mozilla/5.0 (Android 15; Mobile; rv:135.0) Gecko/135.0 Firefox/135.0"
        )
        q.contains("pixel") -> OfflineSpecs(
            brand = "Google",
            modelName = "Pixel 8 Pro",
            modelCode = "GC3VE",
            androidVersion = 14,
            soc = "Google Tensor G3",
            webGlVendor = "ARM",
            webGlRenderer = "Mali-G715 Immortalis MC10",
            ramGb = 12,
            cpuCores = 9,
            screenWidth = 448,
            screenHeight = 998,
            dpr = 3.0,
            userAgent = "Mozilla/5.0 (Android 14; Mobile; rv:135.0) Gecko/135.0 Firefox/135.0"
        )
        q.contains("galaxy") || q.contains("s24") || q.contains("samsung") -> OfflineSpecs(
            brand = "Samsung",
            modelName = "Galaxy S24 Ultra",
            modelCode = "SM-S928B",
            androidVersion = 14,
            soc = "Snapdragon 8 Gen 3 for Galaxy",
            webGlVendor = "Qualcomm",
            webGlRenderer = "Adreno (TM) 750",
            ramGb = 12,
            cpuCores = 8,
            screenWidth = 412,
            screenHeight = 915,
            dpr = 3.125,
            userAgent = "Mozilla/5.0 (Android 14; Mobile; rv:135.0) Gecko/135.0 Firefox/135.0"
        )
        q.contains("tecno") || q.contains("camon") -> OfflineSpecs(
            brand = "Tecno",
            modelName = "Camon 30 Premier",
            modelCode = "CL9",
            androidVersion = 14,
            soc = "MediaTek Dimensity 8200 Ultimate",
            webGlVendor = "ARM",
            webGlRenderer = "Mali-G610 MC6",
            ramGb = 12,
            cpuCores = 8,
            screenWidth = 422,
            screenHeight = 927,
            dpr = 3.0,
            userAgent = "Mozilla/5.0 (Android 14; Mobile; rv:135.0) Gecko/135.0 Firefox/135.0"
        )
        else -> OfflineSpecs(
            brand = "OnePlus",
            modelName = "OnePlus 12",
            modelCode = "CPH2581",
            androidVersion = 14,
            soc = "Snapdragon 8 Gen 3",
            webGlVendor = "Qualcomm",
            webGlRenderer = "Adreno (TM) 750",
            ramGb = 16,
            cpuCores = 8,
            screenWidth = 450,
            screenHeight = 1000,
            dpr = 3.2,
            userAgent = "Mozilla/5.0 (Android 14; Mobile; rv:135.0) Gecko/135.0 Firefox/135.0"
        )
    }
}

