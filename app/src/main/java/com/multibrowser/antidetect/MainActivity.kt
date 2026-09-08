package com.multibrowser.antidetect

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.multibrowser.antidetect.data.db.AppDatabase
import com.multibrowser.antidetect.data.model.ProfileEntity
import com.multibrowser.antidetect.data.model.SavedTabEntity
import com.multibrowser.antidetect.engine.GeckoProfileEngine
import com.multibrowser.antidetect.network.AuthManager
import com.multibrowser.antidetect.network.RetrofitInstance
import com.multibrowser.antidetect.sync.SyncManager
import com.multibrowser.antidetect.ui.components.ActiveProfileState
import com.multibrowser.antidetect.ui.components.ActiveSessionBottomSheet
import com.multibrowser.antidetect.ui.components.AuthDialog
import com.multibrowser.antidetect.ui.components.BookmarksBottomSheet
import com.multibrowser.antidetect.ui.components.BrowserMenuBottomSheet
import com.multibrowser.antidetect.ui.components.BrowserTab
import com.multibrowser.antidetect.ui.components.CookieActionDialog
import com.multibrowser.antidetect.ui.components.CreateProfileBottomSheet
import com.multibrowser.antidetect.ui.components.HistoryBottomSheet
import com.multibrowser.antidetect.ui.components.ProfileSwitcherBottomSheet
import com.multibrowser.antidetect.ui.components.TabsBottomSheet
import com.multibrowser.antidetect.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.multibrowser.antidetect.automation.GhostPilotRunner
import com.multibrowser.antidetect.automation.NativeGestureInjector
import com.multibrowser.antidetect.automation.input.InputController
import com.multibrowser.antidetect.automation.input.NativeInputAdapter
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import java.net.URLEncoder

class MainActivity : ComponentActivity() {

    private lateinit var db: AppDatabase
    private lateinit var engine: GeckoProfileEngine

    // Callback to persist tabs across lifecycle events
    private var onPersistAllTabs: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = AppDatabase.getDatabase(this)
        engine = GeckoProfileEngine(this)
        AuthManager.init(this)
        ThemeManager.init(this)

        setContent {
            OctoTheme {
                MainScreen()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        onPersistAllTabs?.invoke()
    }

    override fun onStop() {
        super.onStop()
        onPersistAllTabs?.invoke()
    }

    override fun onDestroy() {
        onPersistAllTabs?.invoke()
        engine.stopAll()
        super.onDestroy()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainScreen() {
        val profiles by db.profileDao().getAllProfiles().collectAsState(initial = emptyList())

        // Multiple concurrent running profiles: profileId -> ActiveProfileState
        var runningProfiles by remember { mutableStateOf<Map<String, ActiveProfileState>>(emptyMap()) }
        var foregroundProfileId by remember { mutableStateOf<String?>(null) }

        // Dynamic server-controlled max active profiles concurrency (1 to 10)
        var maxAllowedConcurrency by remember { mutableIntStateOf(5) }

        // Fetch global settings from backend control plane
        LaunchedEffect(Unit) {
            try {
                val globalSettings = withContext(Dispatchers.IO) {
                    RetrofitInstance.api.getGlobalSettings()
                }
                maxAllowedConcurrency = globalSettings.maxActiveProfiles
            } catch (e: Exception) {
                maxAllowedConcurrency = 5 // Fallback default
            }
        }

        // Current active foreground profile state and tab
        val currentActiveProfileState = foregroundProfileId?.let { runningProfiles[it] }
        val activeProfile = currentActiveProfileState?.profile
        val currentTabs = currentActiveProfileState?.tabs ?: emptyList()
        val currentTab = currentTabs.firstOrNull { it.id == currentActiveProfileState?.activeTabId }
            ?: currentTabs.firstOrNull()
        val currentSession = currentTab?.session

        // Real-time navigation and progress states
        var isPageLoading by remember { mutableStateOf(false) }
        var pageProgress by remember { mutableFloatStateOf(0f) }
        var canGoBackState by remember { mutableStateOf(false) }
        var canGoForwardState by remember { mutableStateOf(false) }
        var urlInputText by remember { mutableStateOf("https://www.google.com") }

        // Sheet and dialog states
        var showCreateSheet by remember { mutableStateOf(false) }
        var profileToEdit by remember { mutableStateOf<ProfileEntity?>(null) }
        var showTabsSheet by remember { mutableStateOf(false) }
        var showActiveSheet by remember { mutableStateOf(false) }
        var showProfileSwitcherSheet by remember { mutableStateOf(false) }
        var profileToDelete by remember { mutableStateOf<ProfileEntity?>(null) }
        var profileToStopConfirm by remember { mutableStateOf<Pair<String, String>?>(null) }
        var showExitAppDialog by remember { mutableStateOf(false) }
        var showLogoutConfirmDialog by remember { mutableStateOf(false) }
        var showAccountMenuDialog by remember { mutableStateOf(false) }
        var showAuthDialog by remember { mutableStateOf(false) }
        var showBrowserMenuSheet by remember { mutableStateOf(false) }
        var showBookmarksSheet by remember { mutableStateOf(false) }
        var showHistorySheet by remember { mutableStateOf(false) }
        var cookieActionProfile by remember { mutableStateOf<Pair<String, String>?>(null) }
        val profileHistories = remember { mutableStateMapOf<String, String>() }

        // Single-Active Audio Profile Orchestration (Mutual Audio Exclusion)
        val sessionMuteStates = remember { mutableStateMapOf<String, Boolean>() }
        var currentAudioOwnerId by remember { mutableStateOf<String?>(null) }

        // GhostPilot Execution Engines (Physical native automation runners)
        val ghostPilotRunners = remember { mutableStateMapOf<String, GhostPilotRunner>() }
        var activeGeckoView by remember { mutableStateOf<GeckoView?>(null) }

        // GhostPilot Runner for active foreground profile
        val currentRunner = remember(foregroundProfileId, currentSession, activeGeckoView) {
            if (foregroundProfileId != null && currentSession != null && activeGeckoView != null) {
                val injector = NativeGestureInjector(activeGeckoView!!)
                val inputController = NativeInputAdapter(activeGeckoView!!, injector)
                val existing = ghostPilotRunners[foregroundProfileId]
                val prof = runningProfiles[foregroundProfileId]?.profile
                val pName = prof?.name ?: ""
                val pCloudId = prof?.cloudSyncId ?: ""
                if (existing != null) {
                    existing.updateSessionAndView(currentSession, activeGeckoView!!, inputController)
                    existing.profileName = pName
                    if (pCloudId.isNotBlank()) existing.cloudSyncId = pCloudId
                    existing.getCurrentUrl = {
                        runningProfiles[foregroundProfileId]?.let { st ->
                            st.tabs.firstOrNull { it.id == st.activeTabId }?.url ?: ""
                        } ?: ""
                    }
                    existing.getCurrentTitle = {
                        runningProfiles[foregroundProfileId]?.let { st ->
                            st.tabs.firstOrNull { it.id == st.activeTabId }?.title ?: ""
                        } ?: ""
                    }
                    existing
                } else {
                    val created = GhostPilotRunner(
                        context = this@MainActivity,
                        profileId = foregroundProfileId!!,
                        session = currentSession,
                        targetView = activeGeckoView!!,
                        inputController = inputController,
                        profileName = pName,
                        cloudSyncId = pCloudId
                    )
                    created.getCurrentUrl = {
                        runningProfiles[foregroundProfileId]?.let { st ->
                            st.tabs.firstOrNull { it.id == st.activeTabId }?.url ?: ""
                        } ?: ""
                    }
                    created.getCurrentTitle = {
                        runningProfiles[foregroundProfileId]?.let { st ->
                            st.tabs.firstOrNull { it.id == st.activeTabId }?.title ?: ""
                        } ?: ""
                    }
                    ghostPilotRunners[foregroundProfileId!!] = created
                    created
                }
            } else {
                null
            }
        }

        fun sendMuteCommandToSession(session: GeckoSession, muted: Boolean) {
            try {
                val jsCommand = """
                    window.postMessage({ type: 'SET_MUTE_STATE', muted: $muted }, '*');
                    document.querySelectorAll('video, audio').forEach(function(el) {
                        try {
                            el.muted = $muted;
                            el.volume = ${if (muted) "0.0" else "1.0"};
                        } catch(e) {}
                    });
                    try {
                        var p = document.getElementById('movie_player');
                        if (p) {
                            if ($muted) {
                                if (p.mute) p.mute();
                            } else {
                                if (p.unMute) p.unMute();
                                if (p.setVolume) p.setVolume(100);
                            }
                        }
                    } catch(e) {}
                """.trimIndent().replace("\n", " ")

                session.loadUri("javascript:(function(){ $jsCommand })();")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun sendMuteCommandToProfile(profileId: String, muted: Boolean) {
            val state = runningProfiles[profileId] ?: return
            state.tabs.forEach { tab ->
                sendMuteCommandToSession(tab.session, muted)
            }
        }

        fun toggleProfileAudio(profileId: String) {
            val currentlyMuted = sessionMuteStates[profileId] ?: true
            if (currentlyMuted) {
                // User wants to UNMUTE this profile
                // 1. Mute previous audio owner if different (Mutual Audio Exclusion)
                currentAudioOwnerId?.let { prevId ->
                    if (prevId != profileId && runningProfiles.containsKey(prevId)) {
                        sendMuteCommandToProfile(prevId, muted = true)
                        sessionMuteStates[prevId] = true
                    }
                }
                // 2. Unmute the target profile
                sendMuteCommandToProfile(profileId, muted = false)
                sessionMuteStates[profileId] = false
                currentAudioOwnerId = profileId
            } else {
                // User wants to MUTE this profile
                sendMuteCommandToProfile(profileId, muted = true)
                sessionMuteStates[profileId] = true
                if (currentAudioOwnerId == profileId) {
                    currentAudioOwnerId = null
                }
            }
        }

        val isLoggedIn by AuthManager.isLoggedIn.collectAsState()
        val currentUsername by AuthManager.currentUsername.collectAsState()

        fun persistTabsForProfile(profileId: String) {
            val state = runningProfiles[profileId] ?: return
            lifecycleScope.launch {
                val entities = state.tabs.mapIndexed { index, tab ->
                    SavedTabEntity(
                        id = tab.id,
                        profileId = profileId,
                        title = tab.title,
                        url = tab.url,
                        tabOrder = index,
                        isCurrentTab = (tab.id == state.activeTabId)
                    )
                }
                db.profileDao().saveTabsForProfile(profileId, entities)
            }
        }

        fun persistAllRunningTabs() {
            runningProfiles.keys.forEach { profileId ->
                persistTabsForProfile(profileId)
            }
        }

        // Back button navigation when browser is active
        BackHandler(enabled = foregroundProfileId != null) {
            if (canGoBackState && currentSession != null) {
                currentSession.goBack()
            } else {
                persistAllRunningTabs()
                foregroundProfileId = null
            }
        }

        // Back button confirmation when on main overview screen
        BackHandler(enabled = foregroundProfileId == null) {
            showExitAppDialog = true
        }

        // Helper to serialize tabs for cloud/sqlite session syncing
        fun serializeTabs(tabs: List<BrowserTab>): String {
            val arr = org.json.JSONArray()
            tabs.forEach { t ->
                val obj = org.json.JSONObject().apply {
                    put("id", t.id)
                    put("title", t.title)
                    put("url", t.url)
                }
                arr.put(obj)
            }
            return arr.toString()
        }

        // Search & Filter state
        var searchQuery by remember { mutableStateOf("") }
        var selectedFilterTag by remember { mutableStateOf("All") }

        val filteredProfiles = remember(profiles, searchQuery, selectedFilterTag) {
            profiles.filter { p ->
                val matchesQuery = searchQuery.isBlank() ||
                    p.name.contains(searchQuery, ignoreCase = true) ||
                    p.modelName.contains(searchQuery, ignoreCase = true) ||
                    p.brand.contains(searchQuery, ignoreCase = true)
                val matchesTag = selectedFilterTag == "All" || p.tag.equals(selectedFilterTag, ignoreCase = true)
                matchesQuery && matchesTag
            }
        }

        // Register lifecycle callback
        DisposableEffect(runningProfiles) {
            onPersistAllTabs = { persistAllRunningTabs() }
            onDispose { onPersistAllTabs = null }
        }

        // Smart address bar URL / Google search query resolver
        fun resolveNavigationTarget(input: String): String {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return "https://www.google.com"

            val isUrl = trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) ||
                trimmed.startsWith("about:", ignoreCase = true) ||
                (!trimmed.contains(" ") && (
                    trimmed.contains(".") ||
                    trimmed.startsWith("localhost") ||
                    trimmed.matches(Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}(:[0-9]+)?.*$"))
                ))

            return if (isUrl) {
                if (trimmed.startsWith("http://", ignoreCase = true) ||
                    trimmed.startsWith("https://", ignoreCase = true) ||
                    trimmed.startsWith("about:", ignoreCase = true)
                ) {
                    trimmed
                } else {
                    "https://$trimmed"
                }
            } else {
                // Direct Google Search
                val encoded = URLEncoder.encode(trimmed, "UTF-8")
                "https://www.google.com/search?q=$encoded"
            }
        }

        // Handle system back gesture
        BackHandler(enabled = foregroundProfileId != null) {
            if (canGoBackState && currentSession != null) {
                currentSession.goBack()
            } else {
                // Return to profiles list overview while keeping all sessions and tabs alive in background
                persistAllRunningTabs()
                foregroundProfileId = null
            }
        }

        fun attachDelegatesToSession(profileId: String, tab: BrowserTab) {
            tab.session.navigationDelegate = object : GeckoSession.NavigationDelegate {
                override fun onCanGoBack(s: GeckoSession, back: Boolean) {
                    tab.canGoBack = back
                    if (profileId == foregroundProfileId && tab.id == runningProfiles[profileId]?.activeTabId) {
                        canGoBackState = back
                    }
                }

                override fun onCanGoForward(s: GeckoSession, forward: Boolean) {
                    tab.canGoForward = forward
                    if (profileId == foregroundProfileId && tab.id == runningProfiles[profileId]?.activeTabId) {
                        canGoForwardState = forward
                    }
                }

                override fun onLocationChange(
                    s: GeckoSession,
                    url: String?,
                    perms: List<GeckoSession.PermissionDelegate.ContentPermission>,
                    hasUserGesture: Boolean
                ) {
                    url?.let { newUrl ->
                        tab.url = newUrl
                        if (profileId == foregroundProfileId && tab.id == runningProfiles[profileId]?.activeTabId) {
                            urlInputText = newUrl
                        }
                        persistTabsForProfile(profileId)

                        // Record into history JSON array
                        if (newUrl.startsWith("http")) {
                            val currentHist = profileHistories[profileId] ?: runningProfiles[profileId]?.profile?.historyJson ?: "[]"
                            try {
                                val arr = org.json.JSONArray(if (currentHist.isBlank()) "[]" else currentHist)
                                val newEntry = org.json.JSONObject().apply {
                                    put("title", tab.title.ifBlank { newUrl })
                                    put("url", newUrl)
                                    put("timestamp", System.currentTimeMillis())
                                }
                                arr.put(newEntry)
                                val trimmed = if (arr.length() > 200) {
                                    val sub = org.json.JSONArray()
                                    for (i in (arr.length() - 200) until arr.length()) {
                                        sub.put(arr.get(i))
                                    }
                                    sub
                                } else arr
                                profileHistories[profileId] = trimmed.toString()
                            } catch (_: Exception) {}
                        }
                    }
                }
            }

            tab.session.progressDelegate = object : GeckoSession.ProgressDelegate {
                override fun onPageStart(s: GeckoSession, url: String) {
                    tab.isLoading = true
                    tab.progress = 0.15f
                    if (profileId == foregroundProfileId && tab.id == runningProfiles[profileId]?.activeTabId) {
                        isPageLoading = true
                        pageProgress = 0.15f
                    }
                }

                override fun onPageStop(s: GeckoSession, success: Boolean) {
                    tab.isLoading = false
                    tab.progress = 1.0f
                    if (profileId == foregroundProfileId && tab.id == runningProfiles[profileId]?.activeTabId) {
                        isPageLoading = false
                        pageProgress = 1.0f
                    }
                    persistTabsForProfile(profileId)

                    // Enforce session mute state upon page load completion
                    val isMuted = sessionMuteStates[profileId] ?: true
                    sendMuteCommandToSession(s, isMuted)

                    // Request cookies from WebExtension native port and auto-save session
                    engine.requestCookies { cookiesJson, cookieCount ->
                        runningProfiles[profileId]?.let { st ->
                            val tabsJson = serializeTabs(st.tabs)
                            val histJson = profileHistories[profileId] ?: st.profile.historyJson
                            SyncManager.scheduleAutoSave(
                                this@MainActivity,
                                profileId,
                                st.profile.name,
                                cookiesJson,
                                histJson,
                                tabsJson,
                                cookieCount
                            )
                        }
                    }
                }

                override fun onProgressChange(s: GeckoSession, progress: Int) {
                    val p = progress / 100f
                    tab.progress = p
                    if (profileId == foregroundProfileId && tab.id == runningProfiles[profileId]?.activeTabId) {
                        pageProgress = p
                    }
                }
            }

            tab.session.contentDelegate = object : GeckoSession.ContentDelegate {
                override fun onTitleChange(s: GeckoSession, title: String?) {
                    title?.let {
                        tab.title = it
                        persistTabsForProfile(profileId)
                    }
                }
            }
        }

        fun selectRunningProfile(profileId: String) {
            foregroundProfileId = profileId
            val state = runningProfiles[profileId] ?: return
            val tab = state.tabs.firstOrNull { it.id == state.activeTabId } ?: state.tabs.firstOrNull()
            if (tab != null) {
                urlInputText = tab.url
                canGoBackState = tab.canGoBack
                canGoForwardState = tab.canGoForward
                isPageLoading = tab.isLoading
                pageProgress = tab.progress
            }
        }

        fun startProfile(profile: ProfileEntity, openForeground: Boolean = true) {
            if (profile.id in runningProfiles) {
                if (openForeground) {
                    selectRunningProfile(profile.id)
                }
                return
            }

            // Restore cookies into engine if available
            if (profile.cookiesJson.isNotBlank() && profile.cookiesJson != "[]") {
                engine.restoreCookies(profile.cookiesJson)
            }
            profileHistories[profile.id] = profile.historyJson

            // Check dynamic concurrency cap
            if (runningProfiles.size >= maxAllowedConcurrency) {
                Toast.makeText(
                    this@MainActivity,
                    "Limit of $maxAllowedConcurrency active profiles reached! Adjust in Admin Dashboard.",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }

            lifecycleScope.launch {
                // Restore tabs from SQLite if previously saved
                val savedTabs = db.profileDao().getTabsForProfile(profile.id)
                val restoredTabs = mutableListOf<BrowserTab>()
                var initialActiveTabId = ""

                if (savedTabs.isNotEmpty()) {
                    savedTabs.forEach { savedTab ->
                        val session = engine.createTabSession(profile)
                        val browserTab = BrowserTab(
                            id = savedTab.id,
                            session = session,
                            title = savedTab.title.ifBlank { "Tab" },
                            url = savedTab.url.ifBlank { "https://www.google.com" }
                        )
                        attachDelegatesToSession(profile.id, browserTab)
                        session.loadUri(browserTab.url)
                        restoredTabs.add(browserTab)
                        if (savedTab.isCurrentTab || initialActiveTabId.isEmpty()) {
                            initialActiveTabId = browserTab.id
                        }
                    }
                } else {
                    // Fresh profile start -> Default to Google
                    val defaultUrl = "https://www.google.com"
                    val session = engine.createTabSession(profile)
                    val initialTab = BrowserTab(
                        session = session,
                        title = "Google",
                        url = defaultUrl
                    )
                    attachDelegatesToSession(profile.id, initialTab)
                    session.loadUri(defaultUrl)
                    restoredTabs.add(initialTab)
                    initialActiveTabId = initialTab.id
                }

                sessionMuteStates[profile.id] = true // Guaranteed muted on initial launch

                val newState = ActiveProfileState(
                    profile = profile,
                    tabs = restoredTabs,
                    activeTabId = initialActiveTabId
                )

                runningProfiles = runningProfiles + (profile.id to newState)

                if (openForeground) {
                    foregroundProfileId = profile.id
                    val activeTab = restoredTabs.firstOrNull { it.id == initialActiveTabId } ?: restoredTabs.first()
                    urlInputText = activeTab.url
                    canGoBackState = activeTab.canGoBack
                    canGoForwardState = activeTab.canGoForward
                    isPageLoading = activeTab.isLoading
                    pageProgress = activeTab.progress
                }

                // Persist tabs for this profile
                persistTabsForProfile(profile.id)

                // Resolve live network status via backend
                db.profileDao().updateLastUsed(profile.id, System.currentTimeMillis())
                try {
                    val info = RetrofitInstance.api.lookupIp()
                    runningProfiles = runningProfiles.toMutableMap().apply {
                        get(profile.id)?.let { st ->
                            put(profile.id, st.copy(liveIp = info.ip, location = "${info.city}, ${info.country}"))
                        }
                    }
                } catch (e: Exception) {
                    runningProfiles = runningProfiles.toMutableMap().apply {
                        get(profile.id)?.let { st ->
                            put(profile.id, st.copy(liveIp = "Direct IP", location = "Local Network"))
                        }
                    }
                }
            }
        }

        fun stopProfile(profileId: String) {
            persistTabsForProfile(profileId)
            ghostPilotRunners[profileId]?.stop()
            ghostPilotRunners.remove(profileId)
            if (currentAudioOwnerId == profileId) {
                currentAudioOwnerId = null
            }
            sessionMuteStates.remove(profileId)
            val state = runningProfiles[profileId]
            state?.tabs?.forEach { tab ->
                engine.closeTabSession(profileId, tab.session)
            }
            engine.closeProfile(profileId)
            val updated = runningProfiles - profileId
            runningProfiles = updated

            if (foregroundProfileId == profileId) {
                val nextId = updated.keys.lastOrNull()
                if (nextId != null) {
                    selectRunningProfile(nextId)
                } else {
                    foregroundProfileId = null
                }
            }
        }

        fun createNewTab(targetUrl: String = "https://www.google.com") {
            val state = currentActiveProfileState ?: return
            val session = engine.createTabSession(state.profile)
            val isMuted = sessionMuteStates[state.profile.id] ?: true
            sendMuteCommandToSession(session, isMuted)
            val newTab = BrowserTab(
                session = session,
                title = "New Tab",
                url = targetUrl
            )
            attachDelegatesToSession(state.profile.id, newTab)

            val updatedTabs = state.tabs + newTab
            val updatedState = state.copy(
                tabs = updatedTabs,
                activeTabId = newTab.id
            )
            runningProfiles = runningProfiles + (state.profile.id to updatedState)
            urlInputText = targetUrl
            canGoBackState = false
            canGoForwardState = false
            isPageLoading = true
            pageProgress = 0.15f
            showTabsSheet = false
            session.loadUri(targetUrl)
            persistTabsForProfile(state.profile.id)
        }

        fun switchTab(tab: BrowserTab) {
            val state = currentActiveProfileState ?: return
            val updatedState = state.copy(activeTabId = tab.id)
            runningProfiles = runningProfiles + (state.profile.id to updatedState)
            urlInputText = tab.url
            canGoBackState = tab.canGoBack
            canGoForwardState = tab.canGoForward
            isPageLoading = tab.isLoading
            pageProgress = tab.progress
            showTabsSheet = false
            persistTabsForProfile(state.profile.id)
        }

        fun closeTab(tab: BrowserTab) {
            val state = currentActiveProfileState ?: return
            engine.closeTabSession(state.profile.id, tab.session)
            val remaining = state.tabs.filter { it.id != tab.id }
            if (remaining.isEmpty()) {
                stopProfile(state.profile.id)
                showTabsSheet = false
            } else {
                val newActiveTabId = if (state.activeTabId == tab.id) remaining.last().id else state.activeTabId
                val updatedState = state.copy(
                    tabs = remaining,
                    activeTabId = newActiveTabId
                )
                runningProfiles = runningProfiles + (state.profile.id to updatedState)
                if (state.activeTabId == tab.id) {
                    val active = remaining.firstOrNull { it.id == newActiveTabId } ?: remaining.last()
                    switchTab(active)
                }
                persistTabsForProfile(state.profile.id)
            }
        }

        Scaffold(
            topBar = {
                if (foregroundProfileId == null) {
                    TopAppBar(
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .background(OctoPrimary.copy(alpha = 0.15f), RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Shield,
                                        contentDescription = null,
                                        tint = OctoPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        "OctoMobile",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = OctoTextPrimary
                                    )
                                    Text(
                                        "${profiles.size} Profiles • Active: ${runningProfiles.size}/$maxAllowedConcurrency",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = OctoTextSecondary
                                    )
                                }
                            }
                        },
                        actions = {
                            // Theme Toggle Button
                            IconButton(
                                onClick = { ThemeManager.toggleTheme(this@MainActivity) }
                            ) {
                                Icon(
                                    if (isAppInDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                                    contentDescription = "Toggle Theme",
                                    tint = OctoPrimary
                                )
                            }

                            // Account / Cloud Sync Chip
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = if (isLoggedIn) OctoSuccess.copy(alpha = 0.15f) else OctoPrimary.copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, if (isLoggedIn) OctoSuccess else OctoPrimary),
                                modifier = Modifier
                                    .padding(end = 12.dp)
                                    .clickable {
                                        if (isLoggedIn) {
                                            showAccountMenuDialog = true
                                        } else {
                                            showAuthDialog = true
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        if (isLoggedIn) Icons.Default.CloudDone else Icons.Default.AccountCircle,
                                        contentDescription = null,
                                        tint = if (isLoggedIn) OctoSuccess else OctoPrimary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        if (isLoggedIn) currentUsername ?: "Cloud" else "Sign In",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isLoggedIn) OctoSuccess else OctoPrimary
                                    )
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = OctoSurface
                        )
                    )
                }
            },
            floatingActionButton = {
                if (foregroundProfileId == null) {
                    ExtendedFloatingActionButton(
                        onClick = {
                            profileToEdit = null
                            showCreateSheet = true
                        },
                        containerColor = OctoPrimary,
                        contentColor = Color.White,
                        icon = { Icon(Icons.Default.Add, contentDescription = null) },
                        text = { Text("New Profile", fontWeight = FontWeight.Bold) }
                    )
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .then(if (foregroundProfileId == null) Modifier.padding(padding) else Modifier)
                    .fillMaxSize()
            ) {
                if (foregroundProfileId != null && currentSession != null) {
                    // Active Browser Mode with ONLY Address Bar at Top + GeckoView + Unified Bottom Controls
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                    ) {
                        // Address Bar Header (Only address bar at top)
                        Surface(
                            color = OctoSurface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        onClick = {
                                            persistAllRunningTabs()
                                            foregroundProfileId = null
                                        },
                                        modifier = Modifier.size(38.dp)
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "Back to profiles overview",
                                            tint = OctoTextPrimary
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(4.dp))

                                    OutlinedTextField(
                                        value = urlInputText,
                                        onValueChange = { urlInputText = it },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                        placeholder = {
                                            Text(
                                                "Search or enter URL",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = OctoTextMuted
                                            )
                                        },
                                        shape = RoundedCornerShape(22.dp),
                                        textStyle = MaterialTheme.typography.bodySmall.copy(color = OctoTextPrimary),
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                        keyboardActions = KeyboardActions(onGo = {
                                            val target = resolveNavigationTarget(urlInputText)
                                            urlInputText = target
                                            currentSession.loadUri(target)
                                        }),
                                        leadingIcon = {
                                            Icon(
                                                if (urlInputText.startsWith("https")) Icons.Default.Lock else Icons.Default.Search,
                                                contentDescription = null,
                                                tint = if (urlInputText.startsWith("https")) OctoSuccess else OctoTextMuted,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        },
                                        trailingIcon = {
                                            if (urlInputText.isNotBlank()) {
                                                IconButton(onClick = { urlInputText = "" }) {
                                                    Icon(
                                                        Icons.Default.Close,
                                                        contentDescription = "Clear",
                                                        tint = OctoTextMuted,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        },
                                        colors = octoElevatedTextFieldColors()
                                    )

                                    Spacer(modifier = Modifier.width(6.dp))

                                    // Dynamic Audio Mute / Unmute Toggle for Active Profile
                                    foregroundProfileId?.let { fgId ->
                                        val isMuted = sessionMuteStates[fgId] ?: true
                                        FilledIconButton(
                                            onClick = { toggleProfileAudio(fgId) },
                                            colors = IconButtonDefaults.filledIconButtonColors(
                                                containerColor = if (isMuted) OctoSurfaceElevated else OctoSuccess.copy(alpha = 0.2f)
                                            ),
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.size(42.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                                contentDescription = if (isMuted) "Unmute Profile Audio" else "Mute Profile Audio",
                                                tint = if (isMuted) OctoTextMuted else OctoSuccess,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(6.dp))
                                    }

                                    // Inline Refresh / Stop / Go Button
                                    FilledIconButton(
                                        onClick = {
                                            if (isPageLoading) {
                                                currentSession.stop()
                                            } else {
                                                val target = resolveNavigationTarget(urlInputText)
                                                urlInputText = target
                                                currentSession.loadUri(target)
                                            }
                                        },
                                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = OctoPrimary),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.size(42.dp)
                                    ) {
                                        Icon(
                                            if (isPageLoading) Icons.Default.Close else Icons.Default.Refresh,
                                            contentDescription = "Reload",
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }

                                // Running Profiles Tab Bar (Hot-swap profiles onto single GeckoView)
                                if (runningProfiles.size > 1) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState())
                                            .padding(top = 6.dp),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        runningProfiles.forEach { (profId, state) ->
                                            val isSelected = profId == foregroundProfileId
                                            Surface(
                                                color = if (isSelected) OctoPrimary else OctoSurfaceElevated,
                                                shape = RoundedCornerShape(16.dp),
                                                border = BorderStroke(1.dp, if (isSelected) OctoPrimary else OctoBorder),
                                                modifier = Modifier.clickable {
                                                    selectRunningProfile(profId)
                                                }
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(6.dp)
                                                            .background(OctoSuccess, CircleShape)
                                                    )
                                                    Text(
                                                        state.profile.name,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                        color = if (isSelected) Color.White else OctoTextPrimary
                                                    )

                                                    // Independent Audio Toggle Button
                                                    val isProfMuted = sessionMuteStates[profId] ?: true
                                                    Icon(
                                                        imageVector = if (isProfMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                                        contentDescription = if (isProfMuted) "Unmute Audio" else "Mute Audio",
                                                        tint = if (isProfMuted) {
                                                            if (isSelected) Color.White.copy(alpha = 0.7f) else OctoTextMuted
                                                        } else {
                                                            OctoSuccess
                                                        },
                                                        modifier = Modifier
                                                            .size(15.dp)
                                                            .clickable {
                                                                toggleProfileAudio(profId)
                                                            }
                                                    )

                                                    Icon(
                                                        Icons.Default.Close,
                                                        contentDescription = "Stop",
                                                        tint = if (isSelected) Color.White.copy(alpha = 0.8f) else OctoTextMuted,
                                                        modifier = Modifier
                                                            .size(14.dp)
                                                            .clickable {
                                                                profileToStopConfirm = Pair(profId, state.profile.name)
                                                            }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Real-time Page Loading Indicator
                        AnimatedVisibility(visible = isPageLoading) {
                            LinearProgressIndicator(
                                progress = { pageProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(2.5.dp),
                                color = OctoPrimary,
                                trackColor = OctoBorder
                            )
                        }

                        // GeckoView Engine Container
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            AndroidView(
                                factory = { ctx ->
                                    GeckoView(ctx).apply {
                                        activeGeckoView = this
                                        setSession(currentSession)
                                    }
                                },
                                update = { view ->
                                    activeGeckoView = view
                                    if (view.session != currentSession) {
                                        view.releaseSession()
                                        setSessionSafely(view, currentSession)
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        // Bottom Navigation Action Bar - Symmetrical 5-Button Toolbar (Firefox & Chrome Mobile style)
                        Surface(
                            color = OctoSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 1. Back
                                IconButton(
                                    onClick = { currentSession.goBack() },
                                    enabled = canGoBackState
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back",
                                        tint = if (canGoBackState) OctoTextPrimary else OctoTextMuted.copy(alpha = 0.4f),
                                        modifier = Modifier.size(24.dp)
                                    )
                                }

                                // 2. Forward
                                IconButton(
                                    onClick = { currentSession.goForward() },
                                    enabled = canGoForwardState
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = "Forward",
                                        tint = if (canGoForwardState) OctoTextPrimary else OctoTextMuted.copy(alpha = 0.4f),
                                        modifier = Modifier.size(24.dp)
                                    )
                                }

                                // 3. Home
                                IconButton(
                                    onClick = {
                                        urlInputText = "https://www.google.com"
                                        currentSession.loadUri("https://www.google.com")
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Home,
                                        contentDescription = "Home",
                                        tint = OctoTextPrimary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }

                                // 4. Tabs Button (Counter badge)
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = OctoSurfaceElevated,
                                    border = BorderStroke(1.2.dp, OctoBorder),
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clickable { showTabsSheet = true }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            "${currentTabs.size}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = OctoTextPrimary
                                        )
                                    }
                                }

                                // 5. Menu Button (⋮)
                                IconButton(onClick = { showBrowserMenuSheet = true }) {
                                    Icon(
                                        Icons.Default.MoreVert,
                                        contentDescription = "Browser Menu",
                                        tint = OctoTextPrimary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // Profile List Overview Screen
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                    ) {
                        // Search Input
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search profiles, models, or brands...") },
                            leadingIcon = {
                                Icon(Icons.Default.Search, contentDescription = null, tint = OctoTextMuted)
                            },
                            trailingIcon = {
                                if (searchQuery.isNotBlank()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = OctoTextMuted)
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = OctoTextPrimary),
                            colors = octoTextFieldColors()
                        )

                        // Category Filter Chips Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(bottom = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf("All", "Default", "Work", "Personal", "Crypto", "Social").forEach { tag ->
                                val isSelected = selectedFilterTag == tag
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { selectedFilterTag = tag },
                                    label = { Text(tag, fontSize = 12.sp) },
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

                        // Active Sessions Banner (if any running in background)
                        if (runningProfiles.isNotEmpty()) {
                            Surface(
                                color = OctoPrimary.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, OctoPrimary.copy(alpha = 0.3f)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp)
                                    .clickable { showProfileSwitcherSheet = true }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
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
                                            "${runningProfiles.size} Profile${if (runningProfiles.size > 1) "s" else ""} Active in Background",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = OctoTextPrimary
                                        )
                                    }
                                    Text(
                                        "Manage ➔",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = OctoPrimary
                                    )
                                }
                            }
                        }

                        // Profiles List or Empty State
                        if (filteredProfiles.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(bottom = 80.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = OctoSurface),
                                    shape = RoundedCornerShape(16.dp),
                                    border = BorderStroke(1.dp, OctoBorder),
                                    modifier = Modifier.fillMaxWidth(0.9f)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(54.dp)
                                                .background(OctoPrimary.copy(alpha = 0.15f), CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                Icons.Default.Smartphone,
                                                contentDescription = null,
                                                tint = OctoPrimary,
                                                modifier = Modifier.size(28.dp)
                                            )
                                        }
                                        Text(
                                            "No Profiles Found",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = OctoTextPrimary
                                        )
                                        Text(
                                            if (searchQuery.isNotBlank()) "No profile matches '$searchQuery'" else "Create your first anti-detect browser container with grounded AI hardware specs.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = OctoTextSecondary
                                        )
                                        Button(
                                            onClick = {
                                                profileToEdit = null
                                                showCreateSheet = true
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = OctoPrimary),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Icon(Icons.Default.Add, contentDescription = null)
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Generate Profile")
                                        }
                                    }
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(bottom = 90.dp)
                            ) {
                                items(filteredProfiles, key = { it.id }) { profile ->
                                    val isRunning = profile.id in runningProfiles
                                    ProfileCard(
                                        profile = profile,
                                        isRunning = isRunning,
                                        onStart = { startProfile(profile, openForeground = true) },
                                        onOpen = { selectRunningProfile(profile.id) },
                                        onStop = { profileToStopConfirm = Pair(profile.id, profile.name) },
                                        onEdit = {
                                            profileToEdit = profile
                                            showCreateSheet = true
                                        },
                                        onDeleteRequest = { profileToDelete = profile },
                                        onCookieAction = { cookieActionProfile = Pair(profile.id, profile.name) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Profile Switcher Bottom Sheet
            if (showProfileSwitcherSheet) {
                ProfileSwitcherBottomSheet(
                    runningProfiles = runningProfiles,
                    currentForegroundProfileId = foregroundProfileId,
                    allProfiles = profiles,
                    sessionMuteStates = sessionMuteStates,
                    onToggleAudio = { profId ->
                        toggleProfileAudio(profId)
                    },
                    onSelectRunningProfile = { profileId ->
                        selectRunningProfile(profileId)
                    },
                    onStartProfile = { profile ->
                        startProfile(profile, openForeground = true)
                    },
                    onStopProfile = { profileId ->
                        val pName = runningProfiles[profileId]?.profile?.name ?: "Profile"
                        profileToStopConfirm = Pair(profileId, pName)
                    },
                    onDismiss = { showProfileSwitcherSheet = false }
                )
            }

            // Tabs Switcher Bottom Sheet
            if (showTabsSheet && activeProfile != null) {
                TabsBottomSheet(
                    tabs = currentTabs,
                    activeTabId = currentActiveProfileState?.activeTabId ?: "",
                    onSelectTab = { switchTab(it) },
                    onCloseTab = { closeTab(it) },
                    onNewTab = { createNewTab("https://www.google.com") },
                    onDismiss = { showTabsSheet = false }
                )
            }

            // Create / Edit Profile Bottom Sheet
            if (showCreateSheet) {
                CreateProfileBottomSheet(
                    profileToEdit = profileToEdit,
                    onDismiss = {
                        showCreateSheet = false
                        profileToEdit = null
                    },
                    onSaveProfile = { savedProfile, launchImmediate ->
                        lifecycleScope.launch {
                            db.profileDao().insertProfile(savedProfile)
                            // Auto-sync profile to cloud backend if logged in
                            if (AuthManager.isLoggedIn.value) {
                                SyncManager.pushProfileToCloud(this@MainActivity, savedProfile)
                            }
                            if (savedProfile.id in runningProfiles) {
                                runningProfiles = runningProfiles.toMutableMap().apply {
                                    get(savedProfile.id)?.let { st ->
                                        put(savedProfile.id, st.copy(profile = savedProfile))
                                    }
                                }
                            }
                            showCreateSheet = false
                            profileToEdit = null
                            if (launchImmediate) {
                                startProfile(savedProfile, openForeground = true)
                            }
                        }
                    }
                )
            }

            // Browser Menu Bottom Sheet (Firefox Mobile inspired)
            if (showBrowserMenuSheet) {
                BrowserMenuBottomSheet(
                    activeProfile = activeProfile,
                    isAudioMuted = foregroundProfileId?.let { sessionMuteStates[it] } ?: true,
                    ghostPilotRunner = currentRunner,
                    onToggleAudio = {
                        foregroundProfileId?.let { toggleProfileAudio(it) }
                    },
                    onOpenAuth = { showAuthDialog = true },
                    onOpenBookmarks = { showBookmarksSheet = true },
                    onOpenHistory = { showHistorySheet = true },
                    onOpenProfileSpecs = { showActiveSheet = true },
                    onNewTab = { createNewTab("https://www.google.com") },
                    onReload = { currentSession?.reload() },
                    onSwitchProfile = { showProfileSwitcherSheet = true },
                    onStopSession = {
                        foregroundProfileId?.let { profId ->
                            profileToStopConfirm = Pair(profId, activeProfile?.name ?: "Current Profile")
                        }
                    },
                    onDismiss = { showBrowserMenuSheet = false }
                )
            }

            // Bookmarks Bottom Sheet (Google, YouTube, Iphey, CreepJS, and custom bookmarks)
            if (showBookmarksSheet && currentSession != null) {
                BookmarksBottomSheet(
                    currentUrl = urlInputText,
                    onNavigate = { url ->
                        urlInputText = url
                        currentSession.loadUri(url)
                    },
                    onDismiss = { showBookmarksSheet = false }
                )
            }

            // History Bottom Sheet
            if (showHistorySheet && foregroundProfileId != null) {
                val histJson = profileHistories[foregroundProfileId] ?: activeProfile?.historyJson ?: "[]"
                HistoryBottomSheet(
                    historyJson = histJson,
                    onNavigate = { url ->
                        urlInputText = url
                        currentSession?.loadUri(url)
                    },
                    onClearHistory = {
                        foregroundProfileId?.let { profId ->
                            profileHistories[profId] = "[]"
                            lifecycleScope.launch {
                                db.profileDao().updateSessionData(
                                    id = profId,
                                    cookiesJson = activeProfile?.cookiesJson ?: "[]",
                                    historyJson = "[]",
                                    tabsJson = serializeTabs(currentTabs),
                                    cookieCount = activeProfile?.cookieCount ?: 0
                                )
                            }
                        }
                    },
                    onDismiss = { showHistorySheet = false }
                )
            }

            // User Authentication Dialog (Sign in / Register)
            if (showAuthDialog) {
                AuthDialog(
                    onDismiss = { showAuthDialog = false },
                    onAuthSuccess = {
                        lifecycleScope.launch {
                            SyncManager.pushAllProfiles(this@MainActivity)
                        }
                    }
                )
            }

            // Active Session Telemetry Bottom Sheet
            if (showActiveSheet && activeProfile != null && currentActiveProfileState != null) {
                ActiveSessionBottomSheet(
                    profile = activeProfile,
                    liveIp = currentActiveProfileState.liveIp,
                    location = currentActiveProfileState.location,
                    onStopSession = {
                        profileToStopConfirm = Pair(activeProfile.id, activeProfile.name)
                        showActiveSheet = false
                    },
                    onDismiss = { showActiveSheet = false }
                )
            }

            // Delete Confirmation Dialog
            if (profileToDelete != null) {
                AlertDialog(
                    onDismissRequest = { profileToDelete = null },
                    containerColor = OctoSurfaceElevated,
                    title = {
                        Text("Delete Profile", fontWeight = FontWeight.Bold, color = OctoTextPrimary)
                    },
                    text = {
                        Text(
                            "Are you sure you want to delete '${profileToDelete!!.name}'? All isolated cookies and data will be removed.",
                            color = OctoTextSecondary
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val target = profileToDelete!!
                                profileToDelete = null
                                if (target.id in runningProfiles) {
                                    stopProfile(target.id)
                                }
                                lifecycleScope.launch {
                                    db.profileDao().clearTabsForProfile(target.id)
                                    db.profileDao().deleteProfile(target)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = OctoDanger)
                        ) {
                            Text("Delete", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { profileToDelete = null }) {
                            Text("Cancel", color = OctoTextSecondary)
                        }
                    }
                )
            }

            // Stop Profile Confirmation Dialog
            if (profileToStopConfirm != null) {
                val (profId, profName) = profileToStopConfirm!!
                AlertDialog(
                    onDismissRequest = { profileToStopConfirm = null },
                    containerColor = OctoSurfaceElevated,
                    title = {
                        Text("Stop Profile Session?", fontWeight = FontWeight.Bold, color = OctoTextPrimary)
                    },
                    text = {
                        Text(
                            "Are you sure you want to stop session '$profName'? Open tabs, cookies, and browsing history will be automatically saved.",
                            color = OctoTextSecondary
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                profileToStopConfirm = null
                                stopProfile(profId)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = OctoDanger)
                        ) {
                            Text("Stop Session", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { profileToStopConfirm = null }) {
                            Text("Cancel", color = OctoTextSecondary)
                        }
                    }
                )
            }

            // Exit App Confirmation Dialog
            if (showExitAppDialog) {
                AlertDialog(
                    onDismissRequest = { showExitAppDialog = false },
                    containerColor = OctoSurfaceElevated,
                    title = {
                        Text("Exit MultiBrowser?", fontWeight = FontWeight.Bold, color = OctoTextPrimary)
                    },
                    text = {
                        val activeCount = runningProfiles.size
                        val extraMsg = if (activeCount > 0) "\n\n$activeCount active background profile session${if (activeCount > 1) "s" else ""} will be closed." else ""
                        Text(
                            "Are you sure you want to exit the application?$extraMsg",
                            color = OctoTextSecondary
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showExitAppDialog = false
                                finish()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = OctoPrimary)
                        ) {
                            Text("Exit App", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showExitAppDialog = false }) {
                            Text("Cancel", color = OctoTextSecondary)
                        }
                    }
                )
            }

            // Logout Confirmation Dialog
            if (showLogoutConfirmDialog) {
                AlertDialog(
                    onDismissRequest = { showLogoutConfirmDialog = false },
                    containerColor = OctoSurfaceElevated,
                    title = {
                        Text("Sign Out?", fontWeight = FontWeight.Bold, color = OctoTextPrimary)
                    },
                    text = {
                        Text(
                            "Are you sure you want to sign out of '$currentUsername'? Cloud profile synchronization will pause on this device.",
                            color = OctoTextSecondary
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showLogoutConfirmDialog = false
                                AuthManager.logout()
                                Toast.makeText(this@MainActivity, "Signed out successfully", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = OctoDanger)
                        ) {
                            Text("Sign Out", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showLogoutConfirmDialog = false }) {
                            Text("Cancel", color = OctoTextSecondary)
                        }
                    }
                )
            }

            // Account Options Dialog (when clicking username in TopAppBar)
            if (showAccountMenuDialog) {
                AlertDialog(
                    onDismissRequest = { showAccountMenuDialog = false },
                    containerColor = OctoSurfaceElevated,
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.AccountCircle, contentDescription = null, tint = OctoPrimary)
                            Text("Account: $currentUsername", fontWeight = FontWeight.Bold, color = OctoTextPrimary)
                        }
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Your profiles, cookies, and history are synchronized with your account in the cloud.",
                                color = OctoTextSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showAccountMenuDialog = false
                                lifecycleScope.launch {
                                    val res = SyncManager.pullProfilesFromCloud(this@MainActivity)
                                    Toast.makeText(
                                        this@MainActivity,
                                        if (res.isSuccess) "Synced with Cloud (${res.getOrNull()} profiles)" else "Sync error: ${res.exceptionOrNull()?.message}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = OctoPrimary)
                        ) {
                            Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Sync from Cloud")
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                showAccountMenuDialog = false
                                showLogoutConfirmDialog = true
                            }
                        ) {
                            Text("Sign Out", color = OctoDanger, fontWeight = FontWeight.SemiBold)
                        }
                    }
                )
            }

            // Cookie Import / Export Dialog
            cookieActionProfile?.let { (pId, pName) ->
                CookieActionDialog(
                    profileId = pId,
                    profileName = pName,
                    onDismiss = { cookieActionProfile = null },
                    onCookiesUpdated = { count ->
                        Toast.makeText(this@MainActivity, "$count cookies active for $pName", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }

    private fun setSessionSafely(view: GeckoView, session: GeckoSession?) {
        try {
            if (session != null) {
                view.setSession(session)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Composable
    fun ProfileCard(
        profile: ProfileEntity,
        isRunning: Boolean,
        onStart: () -> Unit,
        onOpen: () -> Unit,
        onStop: () -> Unit,
        onEdit: () -> Unit,
        onDeleteRequest: () -> Unit,
        onCookieAction: () -> Unit
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            // NOTE: Clicking the outer card container does NOTHING as requested by user.
            colors = CardDefaults.cardColors(containerColor = OctoSurface),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(
                width = if (isRunning) 1.2.dp else 1.dp,
                color = if (isRunning) OctoSuccess.copy(alpha = 0.6f) else OctoBorder
            )
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Header row: Device Icon + Profile Name + Tag + Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
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
                                .size(40.dp)
                                .background(
                                    if (isRunning) OctoSuccess.copy(alpha = 0.15f) else OctoSurfaceElevated,
                                    RoundedCornerShape(10.dp)
                                )
                                .border(
                                    1.dp,
                                    if (isRunning) OctoSuccess.copy(alpha = 0.4f) else OctoBorder,
                                    RoundedCornerShape(10.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Smartphone,
                                contentDescription = null,
                                tint = if (isRunning) OctoSuccess else OctoPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    profile.name,
                                    modifier = Modifier.weight(1f, fill = false),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = OctoTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (isRunning) {
                                    Surface(
                                        color = OctoSuccess.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(4.dp),
                                        modifier = Modifier.wrapContentWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(6.dp)
                                                    .background(OctoSuccess, CircleShape)
                                            )
                                            Text(
                                                "Active",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = OctoSuccess,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }
                                } else if (profile.tag.isNotBlank()) {
                                    Surface(
                                        color = OctoPrimary.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(4.dp),
                                        modifier = Modifier.wrapContentWidth()
                                    ) {
                                        Text(
                                            profile.tag,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = OctoPrimary,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                            }
                            Text(
                                "${profile.brand} ${profile.modelName} • ${profile.modelCode}",
                                style = MaterialTheme.typography.bodySmall,
                                color = OctoTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Header Cookie, Edit & Delete Icon Buttons
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        IconButton(onClick = onCookieAction) {
                            Icon(
                                Icons.Default.Cookie,
                                contentDescription = "Manage Cookies",
                                tint = OctoPrimary,
                                modifier = Modifier.size(19.dp)
                            )
                        }

                        IconButton(onClick = onEdit) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Edit Profile",
                                tint = OctoTextSecondary,
                                modifier = Modifier.size(19.dp)
                            )
                        }

                        IconButton(onClick = onDeleteRequest) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete Profile",
                                tint = OctoTextMuted,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                }

                HorizontalDivider(color = OctoBorder, thickness = 0.8.dp)

                // Specs Summary Badges
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        color = OctoSurfaceElevated,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(6.dp)) {
                            Text("GPU", style = MaterialTheme.typography.labelSmall, color = OctoTextMuted)
                            Text(
                                profile.webGlRenderer.take(18),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = OctoTextPrimary,
                                maxLines = 1
                            )
                        }
                    }
                    Surface(
                        color = OctoSurfaceElevated,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(6.dp)) {
                            Text("Screen", style = MaterialTheme.typography.labelSmall, color = OctoTextMuted)
                            Text(
                                "${profile.screenWidth}x${profile.screenHeight}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = OctoTextPrimary,
                                maxLines = 1
                            )
                        }
                    }
                    Surface(
                        color = OctoSurfaceElevated,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(6.dp)) {
                            Text("Memory", style = MaterialTheme.typography.labelSmall, color = OctoTextMuted)
                            Text(
                                "${profile.ramGb}GB / ${profile.cpuCores}C",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = OctoTextPrimary,
                                maxLines = 1
                            )
                        }
                    }
                }

                // Connection Route & Launch / Controls Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .background(
                                    if (profile.proxyType == "DIRECT") OctoSuccess else OctoPrimary,
                                    CircleShape
                                )
                        )
                        Text(
                            if (profile.proxyType == "DIRECT") "Direct Network" else "${profile.proxyType}: ${profile.proxyHost}:${profile.proxyPort}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (profile.proxyType == "DIRECT") OctoSuccess else OctoPrimary,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // Card Action Buttons:
                    // If started/running: show Open and Stop buttons.
                    // If not running: show Start button.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (isRunning) {
                            Button(
                                onClick = onOpen,
                                colors = ButtonDefaults.buttonColors(containerColor = OctoPrimary),
                                shape = RoundedCornerShape(20.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Launch,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Open", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = onStop,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = OctoDanger),
                                border = BorderStroke(1.dp, OctoDanger.copy(alpha = 0.5f)),
                                shape = RoundedCornerShape(20.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Icon(
                                    Icons.Default.Stop,
                                    contentDescription = null,
                                    tint = OctoDanger,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text("Stop", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = OctoDanger)
                            }
                        } else {
                            Button(
                                onClick = onStart,
                                colors = ButtonDefaults.buttonColors(containerColor = OctoPrimary),
                                shape = RoundedCornerShape(20.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Start", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun ProfileAutomationToolbar(
        profileId: String,
        runner: GhostPilotRunner?,
        modifier: Modifier = Modifier
    ) {
        var isAutomationRunning by remember(profileId, runner) {
            mutableStateOf(runner?.isRunning ?: false)
        }
        var currentStep by remember(profileId, runner) {
            mutableStateOf<String?>(null)
        }

        DisposableEffect(runner) {
            runner?.onStateChanged = { running, step ->
                isAutomationRunning = running
                currentStep = step
            }
            isAutomationRunning = runner?.isRunning ?: false
            onDispose {
                runner?.onStateChanged = null
            }
        }

        Surface(
            color = Color(0xFF141416),
            modifier = modifier
                .fillMaxWidth()
                .height(44.dp),
            border = BorderStroke(1.dp, Color(0xFF1E2638))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Pulsing / Status indicator dot
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                if (isAutomationRunning) Color(0xFF34C759) else Color(0xFF8E8E93),
                                CircleShape
                            )
                    )
                    Text(
                        text = if (isAutomationRunning) {
                            "GhostPilot Active" + (if (!currentStep.isNullOrBlank()) " ($currentStep)" else "")
                        } else {
                            "GhostPilot Idle"
                        },
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                Button(
                    onClick = {
                        if (runner == null) return@Button
                        if (isAutomationRunning) {
                            runner.stop()
                            isAutomationRunning = false
                        } else {
                            runner.start()
                            isAutomationRunning = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isAutomationRunning) Color(0xFFFF3B30) else Color(0xFF007AFF)
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Text(
                        text = if (isAutomationRunning) "Pause Pilot" else "Start Pilot",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

