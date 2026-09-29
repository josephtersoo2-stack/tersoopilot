package com.multibrowser.antidetect.engine

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.multibrowser.antidetect.MainActivity
import com.multibrowser.antidetect.data.db.AppDatabase
import com.multibrowser.antidetect.data.model.ProfileEntity
import com.multibrowser.antidetect.data.model.SavedTabEntity
import com.multibrowser.antidetect.network.RetrofitInstance
import com.multibrowser.antidetect.sync.SyncManager
import com.multibrowser.antidetect.ui.components.ActiveProfileState
import com.multibrowser.antidetect.ui.components.BrowserTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoSession

/**
 * Coordinates multi-profile GeckoView session lifecycles, tab orchestration,
 * mutual exclusion audio muting, and session persistence.
 */
class BrowserCoordinator(
    private val context: Context,
    private val db: AppDatabase,
    val engine: GeckoProfileEngine,
    private val scope: CoroutineScope
) {
    private val TAG = "BrowserCoordinator"

    // Multiple concurrent running profiles: profileId -> ActiveProfileState
    var runningProfiles by mutableStateOf<Map<String, ActiveProfileState>>(emptyMap())
    var foregroundProfileId by mutableStateOf<String?>(null)

    // Real-time navigation and progress states for active tab
    var isPageLoading by mutableStateOf(false)
    var pageProgress by mutableFloatStateOf(0f)
    var canGoBackState by mutableStateOf(false)
    var canGoForwardState by mutableStateOf(false)
    var urlInputText by mutableStateOf("https://www.google.com")
    var currentScrollY by mutableIntStateOf(0)

    // Single-Active Audio Profile Orchestration (Mutual Audio Exclusion)
    val sessionMuteStates = mutableStateMapOf<String, Boolean>()
    var currentAudioOwnerId by mutableStateOf<String?>(null)

    val profileHistories = mutableStateMapOf<String, String>()

    val currentActiveProfileState: ActiveProfileState?
        get() = foregroundProfileId?.let { runningProfiles[it] }

    val currentSession: GeckoSession?
        get() {
            val tabs = currentActiveProfileState?.tabs ?: return null
            val activeTab = tabs.firstOrNull { it.id == currentActiveProfileState?.activeTabId } ?: tabs.firstOrNull()
            return activeTab?.session
        }

    var activeVideoResolution by mutableStateOf("240p")

    fun sendMuteCommandToSession(session: GeckoSession, muted: Boolean) {
        try {
            val jsCommand = """
                window.postMessage({ type: 'SET_MUTE_STATE', muted: $muted }, '*');
                document.querySelectorAll('video, audio').forEach(function(el) {
                    try {
                        el.muted = $muted;
                        el.volume = ${if (muted) "0.0" else "1.0"};
                        if (!${muted} && el.paused) {
                            el.play().catch(function(){});
                        }
                    } catch(e) {}
                });
                try {
                    var p = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                    if (p) {
                        if ($muted) {
                            if (typeof p.mute === 'function') p.mute();
                            if (typeof p.setVolume === 'function') p.setVolume(0);
                        } else {
                            if (typeof p.unMute === 'function') p.unMute();
                            if (typeof p.setVolume === 'function') p.setVolume(100);
                            if (typeof p.getPlayerState === 'function' && p.getPlayerState() === 2) {
                                p.playVideo();
                            }
                        }
                    }
                } catch(e) {}
            """.trimIndent().replace("\n", " ")

            session.loadUri("javascript:(function(){ $jsCommand })();")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending mute command to session", e)
        }
    }

    fun sendResolutionCommandToSession(session: GeckoSession, resolution: String) {
        try {
            val jsCommand = """
                window.postMessage({ type: 'SET_VIDEO_RESOLUTION', resolution: '$resolution' }, '*');
            """.trimIndent().replace("\n", " ")
            session.loadUri("javascript:(function(){ $jsCommand })();")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending resolution command to session", e)
        }
    }

    fun broadcastVideoResolution(resolution: String) {
        if (resolution.isBlank()) return
        activeVideoResolution = resolution
        Log.i(TAG, "Broadcasting video resolution: $resolution across all open tabs")
        runningProfiles.values.forEach { state ->
            state.tabs.forEach { tab ->
                sendResolutionCommandToSession(tab.session, resolution)
            }
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
            // Unmute target profile and mute previous owner (mutual audio exclusion)
            currentAudioOwnerId?.let { prevId ->
                if (prevId != profileId && runningProfiles.containsKey(prevId)) {
                    sendMuteCommandToProfile(prevId, muted = true)
                    sessionMuteStates[prevId] = true
                }
            }
            sendMuteCommandToProfile(profileId, muted = false)
            sessionMuteStates[profileId] = false
            currentAudioOwnerId = profileId
        } else {
            sendMuteCommandToProfile(profileId, muted = true)
            sessionMuteStates[profileId] = true
            if (currentAudioOwnerId == profileId) {
                currentAudioOwnerId = null
            }
        }
    }

    fun selectRunningProfile(profileId: String) {
        foregroundProfileId = profileId
        val state = runningProfiles[profileId] ?: return
        val tab = state.tabs.firstOrNull { it.id == state.activeTabId } ?: state.tabs.firstOrNull()
        if (tab != null) {
            urlInputText = if (tab.url == "about:home" || tab.url == "about:blank") "" else tab.url
            canGoBackState = tab.canGoBack
            canGoForwardState = tab.canGoForward
            isPageLoading = tab.isLoading
            pageProgress = tab.progress
            currentScrollY = tab.currentScrollY
            try {
                tab.session.setPriorityHint(GeckoSession.PRIORITY_HIGH)
                tab.session.setActive(true)
            } catch (_: Exception) {}
        }
        runningProfiles.filterKeys { it != profileId }.values.forEach { bgState ->
            bgState.tabs.forEach { bgTab ->
                try {
                    bgTab.session.setPriorityHint(GeckoSession.PRIORITY_DEFAULT)
                } catch (_: Exception) {}
            }
        }
    }

    fun startProfile(profile: ProfileEntity, openForeground: Boolean = true, maxAllowedConcurrency: Int = 5) {
        if (profile.id in runningProfiles) {
            if (openForeground) {
                selectRunningProfile(profile.id)
            }
            return
        }

        if (profile.cookiesJson.isNotBlank() && profile.cookiesJson != "[]") {
            com.multibrowser.antidetect.sync.CookieEngine.importCookiesFromJson(context, profile.id, profile.cookiesJson)
            engine.restoreCookies(profile.cookiesJson, profile.id)
        }
        profileHistories[profile.id] = profile.historyJson

        if (runningProfiles.size >= maxAllowedConcurrency) {
            Toast.makeText(
                context,
                "Limit of $maxAllowedConcurrency active profiles reached! Adjust in Admin Dashboard.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        scope.launch {
            val savedTabs = db.profileDao().getTabsForProfile(profile.id)
            val restoredTabs = mutableListOf<BrowserTab>()
            var initialActiveTabId = ""

            if (savedTabs.isNotEmpty()) {
                savedTabs.forEach { savedTab ->
                    val session = engine.createTabSession(profile)
                    val rawUrl = savedTab.url.trim()
                    val isAutomationArtifact = rawUrl.contains("the+homeless+billionaire+grandpa") ||
                            rawUrl.contains("9H3OTeDFN-Y") ||
                            (rawUrl.contains("youtube.com") && rawUrl.contains("&t="))
                    val isStartPage = isAutomationArtifact ||
                            rawUrl.isBlank() ||
                            rawUrl.equals("about:home", ignoreCase = true) ||
                            rawUrl.equals("about:blank", ignoreCase = true)

                    val finalUrl = if (isStartPage) "about:home" else rawUrl
                    val finalTitle = if (isStartPage) "New Tab" else savedTab.title.ifBlank { "Tab" }

                    val browserTab = BrowserTab(
                        id = savedTab.id,
                        session = session,
                        title = finalTitle,
                        url = finalUrl
                    )
                    attachDelegatesToSession(profile.id, browserTab)
                    if (isStartPage) {
                        session.loadUri("about:blank")
                        browserTab.isLoading = false
                        browserTab.progress = 0f
                    } else {
                        session.loadUri(finalUrl)
                        browserTab.isLoading = true
                        browserTab.progress = 0.15f
                    }
                    restoredTabs.add(browserTab)
                    if (savedTab.isCurrentTab || initialActiveTabId.isEmpty()) {
                        initialActiveTabId = browserTab.id
                    }
                }
            }

            if (restoredTabs.isEmpty()) {
                val defaultUrl = "about:home"
                val session = engine.createTabSession(profile)
                val initialTab = BrowserTab(
                    session = session,
                    title = "New Tab",
                    url = defaultUrl
                )
                attachDelegatesToSession(profile.id, initialTab)
                session.loadUri("about:blank")
                restoredTabs.add(initialTab)
                initialActiveTabId = initialTab.id
            }

            sessionMuteStates[profile.id] = true // Guaranteed muted initially

            val newState = ActiveProfileState(
                profile = profile,
                tabs = restoredTabs,
                activeTabId = initialActiveTabId
            )

            runningProfiles = runningProfiles + (profile.id to newState)

            if (openForeground) {
                foregroundProfileId = profile.id
                val activeTab = restoredTabs.firstOrNull { it.id == initialActiveTabId } ?: restoredTabs.first()
                urlInputText = if (activeTab.url == "about:home" || activeTab.url == "about:blank") "" else activeTab.url
                canGoBackState = activeTab.canGoBack
                canGoForwardState = activeTab.canGoForward
                isPageLoading = activeTab.isLoading
                pageProgress = activeTab.progress
                currentScrollY = activeTab.currentScrollY
                try {
                    activeTab.session.setPriorityHint(GeckoSession.PRIORITY_HIGH)
                    activeTab.session.setActive(true)
                } catch (_: Exception) {}
            }

            persistTabsForProfile(profile.id)
            db.profileDao().updateLastUsed(profile.id, System.currentTimeMillis())

            try {
                val info = RetrofitInstance.api.lookupIp()
                runningProfiles = runningProfiles.toMutableMap().apply {
                    get(profile.id)?.let { st ->
                        put(profile.id, st.copy(liveIp = info.ip, location = "${info.city}, ${info.country}"))
                    }
                }
            } catch (_: Exception) {
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
        val state = runningProfiles[profileId]
        if (state != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    com.multibrowser.antidetect.sync.CookieEngine.syncProfileCookies(context, state.profile)
                } catch (e: Exception) {
                    android.util.Log.w("BrowserCoordinator", "Cookie sync on stop profile failed: ${e.message}")
                }
            }
        }
        if (currentAudioOwnerId == profileId) {
            currentAudioOwnerId = null
        }
        sessionMuteStates.remove(profileId)
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

    fun openNewTab(url: String = "about:home") {
        val state = currentActiveProfileState ?: return
        val targetUrl = if (url.isBlank()) "about:home" else url
        val session = engine.createTabSession(state.profile)
        val newTab = BrowserTab(
            session = session,
            title = if (targetUrl == "about:home" || targetUrl == "about:blank") "New Tab" else targetUrl,
            url = targetUrl
        )
        attachDelegatesToSession(state.profile.id, newTab)
        if (targetUrl != "about:home" && targetUrl != "about:blank") {
            session.loadUri(targetUrl)
            isPageLoading = true
            pageProgress = 0.15f
        } else {
            session.loadUri("about:blank")
            isPageLoading = false
            pageProgress = 0f
        }

        val updatedTabs = state.tabs + newTab
        val updatedState = state.copy(
            tabs = updatedTabs,
            activeTabId = newTab.id
        )
        runningProfiles = runningProfiles + (state.profile.id to updatedState)
        urlInputText = if (targetUrl == "about:home" || targetUrl == "about:blank") "" else targetUrl
        canGoBackState = false
        canGoForwardState = false
        persistTabsForProfile(state.profile.id)
    }

    fun switchTab(tab: BrowserTab) {
        val state = currentActiveProfileState ?: return
        val updatedState = state.copy(activeTabId = tab.id)
        runningProfiles = runningProfiles + (state.profile.id to updatedState)
        urlInputText = if (tab.url == "about:home" || tab.url == "about:blank") "" else tab.url
        canGoBackState = tab.canGoBack
        canGoForwardState = tab.canGoForward
        isPageLoading = tab.isLoading
        pageProgress = tab.progress
        currentScrollY = tab.currentScrollY
        try {
            tab.session.setPriorityHint(GeckoSession.PRIORITY_HIGH)
            tab.session.setActive(true)
            state.tabs.filter { it.id != tab.id }.forEach { other ->
                other.session.setPriorityHint(GeckoSession.PRIORITY_DEFAULT)
            }
        } catch (_: Exception) {}
        persistTabsForProfile(state.profile.id)
    }

    fun resetProfileToStartPage(profileId: String) {
        val state = runningProfiles[profileId] ?: return
        val activeTab = state.tabs.firstOrNull { it.id == state.activeTabId } ?: state.tabs.firstOrNull() ?: return
        activeTab.url = "about:home"
        activeTab.title = "New Tab"
        activeTab.isLoading = false
        activeTab.progress = 0f
        activeTab.session.loadUri("about:blank")
        if (foregroundProfileId == profileId) {
            urlInputText = ""
            canGoBackState = false
            canGoForwardState = false
            isPageLoading = false
            pageProgress = 0f
        }
        persistTabsForProfile(profileId)
    }

    fun closeTab(tab: BrowserTab) {
        val state = currentActiveProfileState ?: return
        if (state.tabs.size <= 1) {
            stopProfile(state.profile.id)
            return
        }

        engine.closeTabSession(state.profile.id, tab.session)
        val remaining = state.tabs.filter { it.id != tab.id }
        val newActiveTabId = if (state.activeTabId == tab.id) {
            val index = state.tabs.indexOfFirst { it.id == tab.id }
            val nextIndex = (index - 1).coerceAtLeast(0)
            remaining.getOrNull(nextIndex)?.id ?: remaining.first().id
        } else {
            state.activeTabId
        }

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

    fun attachDelegatesToSession(profileId: String, tab: BrowserTab) {
        tab.session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(s: GeckoSession, canGoBack: Boolean) {
                tab.canGoBack = canGoBack
                if (profileId == foregroundProfileId && tab.id == runningProfiles[profileId]?.activeTabId) {
                    canGoBackState = canGoBack
                }
            }

            override fun onCanGoForward(s: GeckoSession, canGoForward: Boolean) {
                tab.canGoForward = canGoForward
                if (profileId == foregroundProfileId && tab.id == runningProfiles[profileId]?.activeTabId) {
                    canGoForwardState = canGoForward
                }
            }

            override fun onLocationChange(
                s: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                url?.let { newUrl ->
                    val effectiveUrl = if (newUrl == "about:blank") {
                        if (tab.url == "about:home") "about:home" else "about:blank"
                    } else {
                        newUrl
                    }
                    tab.url = effectiveUrl
                    if (profileId == foregroundProfileId && tab.id == runningProfiles[profileId]?.activeTabId) {
                        urlInputText = if (effectiveUrl == "about:home" || effectiveUrl == "about:blank") "" else effectiveUrl
                    }
                    persistTabsForProfile(profileId)

                    if (newUrl.startsWith("http")) {
                        val currentHist = profileHistories[profileId] ?: runningProfiles[profileId]?.profile?.historyJson ?: "[]"
                        try {
                            val arr = JSONArray(if (currentHist.isBlank()) "[]" else currentHist)
                            val newEntry = JSONObject().apply {
                                put("title", tab.title.ifBlank { newUrl })
                                put("url", newUrl)
                                put("timestamp", System.currentTimeMillis())
                            }
                            arr.put(newEntry)
                            val trimmed = if (arr.length() > 200) {
                                val sub = JSONArray()
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

        tab.session.promptDelegate = BrowserPromptHandler(context)

        tab.session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(s: GeckoSession, url: String) {
                tab.isLoading = true
                tab.progress = 0.05f
                if (profileId == foregroundProfileId && tab.id == runningProfiles[profileId]?.activeTabId) {
                    isPageLoading = true
                    pageProgress = 0.05f
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

                val isMuted = sessionMuteStates[profileId] ?: true
                sendMuteCommandToSession(s, isMuted)
                sendResolutionCommandToSession(s, activeVideoResolution)

                runningProfiles[profileId]?.let { st ->
                    val tabsJson = serializeTabs(st.tabs)
                    val histJson = profileHistories[profileId] ?: st.profile.historyJson

                    scope.launch {
                        // 1. Fetch live cookies directly from WebExtension memory bridge
                        val (liveCookiesStr, liveCount) = engine.getLiveCookies(profileId)

                        // 2. If live cookies obtained, use them; otherwise fallback to SQLite export
                        val (effectiveCookiesJson, effectiveCount) = if (liveCount > 0 && liveCookiesStr != "[]") {
                            Pair(liveCookiesStr, liveCount)
                        } else {
                            val sqliteJson = com.multibrowser.antidetect.sync.CookieEngine.exportCookiesToJson(
                                context = context,
                                profileId = profileId,
                                altId = st.profile.cloudSyncId
                            )
                            val parsedArray = try { org.json.JSONArray(sqliteJson) } catch (e: Exception) { org.json.JSONArray() }
                            Pair(sqliteJson, parsedArray.length())
                        }

                        val finalCount = if (effectiveCount > 0) effectiveCount else st.profile.cookieCount
                        val finalCookiesJson = if (effectiveCount > 0) {
                            com.multibrowser.antidetect.sync.CookieEngine.encryptCookiePayload(effectiveCookiesJson)
                        } else {
                            st.profile.cookiesJson
                        }

                        // Update running profile state
                        st.profile = st.profile.copy(
                            cookiesJson = finalCookiesJson,
                            cookieCount = finalCount
                        )

                        SyncManager.scheduleAutoSave(
                            context,
                            profileId,
                            st.profile.name,
                            finalCookiesJson,
                            histJson,
                            tabsJson,
                            finalCount
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

        tab.session.scrollDelegate = object : GeckoSession.ScrollDelegate {
            override fun onScrollChanged(s: GeckoSession, scrollX: Int, scrollY: Int) {
                tab.currentScrollY = scrollY
                if (profileId == foregroundProfileId && tab.id == runningProfiles[profileId]?.activeTabId) {
                    currentScrollY = scrollY
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

            override fun onCrash(s: GeckoSession) {
                Log.w(TAG, "Content process crashed for profile $profileId, tab ${tab.id}. Triggering auto-recovery...")
                recoverSession(profileId, tab)
            }

            override fun onKill(s: GeckoSession) {
                Log.w(TAG, "Content process killed by OS for profile $profileId, tab ${tab.id}. Triggering auto-recovery...")
                recoverSession(profileId, tab)
            }
        }
    }

    fun persistTabsForProfile(profileId: String) {
        val state = runningProfiles[profileId] ?: return
        scope.launch(Dispatchers.IO) {
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

    fun syncRunningProfilesCookies() {
        runningProfiles.values.forEach { state ->
            scope.launch(Dispatchers.IO) {
                try {
                    com.multibrowser.antidetect.sync.CookieEngine.syncProfileCookies(context, state.profile)
                } catch (e: Exception) {
                    android.util.Log.w("BrowserCoordinator", "Cookie sync on lifecycle pause failed: ${e.message}")
                }
            }
        }
    }

    fun stopAll() {
        persistAllRunningTabs()
        syncRunningProfilesCookies()
        runningProfiles.keys.forEach { pid ->
            engine.closeProfile(pid)
        }
        runningProfiles = emptyMap()
        foregroundProfileId = null
    }

    fun resolveNavigationTarget(input: String): String {
        val trimmed = input.trim()
        return when {
            trimmed.startsWith("about:") -> trimmed
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.contains(".") && !trimmed.contains(" ") -> "https://$trimmed"
            else -> "https://www.google.com/search?q=" + java.net.URLEncoder.encode(trimmed, "UTF-8")
        }
    }

    fun serializeTabs(tabs: List<BrowserTab>): String {
        val arr = JSONArray()
        tabs.forEach { t ->
            val obj = JSONObject().apply {
                put("id", t.id)
                put("title", t.title)
                put("url", t.url)
            }
            arr.put(obj)
        }
        return arr.toString()
    }

    fun recoverSession(profileId: String, tab: BrowserTab) {
        scope.launch(Dispatchers.Main) {
            val profile = runningProfiles[profileId]?.profile ?: return@launch
            Log.i(TAG, "Attempting session recovery for profile: $profileId, tab: ${tab.id}, url: ${tab.url}")
            try {
                if (!tab.session.isOpen) {
                    val runtime = engine.getOrCreateRuntime(profile)
                    tab.session.open(runtime)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to re-open existing GeckoSession: ${e.message}. Creating fresh replacement session.")
                try {
                    val replacementSession = engine.createTabSession(profile)
                    tab.session = replacementSession
                    attachDelegatesToSession(profileId, tab)
                } catch (e2: Exception) {
                    Log.e(TAG, "Failed to instantiate replacement session: ${e2.message}", e2)
                }
            }

            val isForeground = (profileId == foregroundProfileId && tab.id == runningProfiles[profileId]?.activeTabId)
            if (isForeground) {
                try {
                    tab.session.setPriorityHint(GeckoSession.PRIORITY_HIGH)
                    tab.session.setActive(true)
                } catch (_: Exception) {}

                MainActivity.activeGeckoViewInstance?.let { gv ->
                    try {
                        gv.releaseSession()
                        gv.setSession(tab.session)
                        gv.requestLayout()
                        gv.invalidate()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error rebinding recovered session to GeckoView: ${e.message}")
                    }
                }
            }

            val targetUrl = tab.url.trim()
            if (targetUrl.isNotBlank() && targetUrl != "about:home" && targetUrl != "about:blank") {
                tab.isLoading = true
                tab.progress = 0.15f
                if (isForeground) {
                    isPageLoading = true
                    pageProgress = 0.15f
                }
                try {
                    tab.session.loadUri(targetUrl)
                } catch (e: Exception) {
                    Log.e(TAG, "Error reloading targetUrl in recoverSession: ${e.message}")
                }
            } else {
                try {
                    tab.session.loadUri("about:blank")
                } catch (_: Exception) {}
                tab.isLoading = false
                tab.progress = 0f
                if (isForeground) {
                    isPageLoading = false
                    pageProgress = 0f
                }
            }
        }
    }

    fun onResume() {
        val profId = foregroundProfileId ?: return
        val curState = runningProfiles[profId] ?: return
        val activeTab = curState.tabs.firstOrNull { it.id == curState.activeTabId } ?: curState.tabs.firstOrNull() ?: return

        Log.i(TAG, "onResume() called in BrowserCoordinator. Active tab: ${activeTab.id}, url: ${activeTab.url}, isOpen: ${activeTab.session.isOpen}")

        if (!activeTab.session.isOpen) {
            Log.w(TAG, "Active session is dead/closed upon resume. Triggering auto-recovery...")
            recoverSession(profId, activeTab)
            return
        }

        try {
            activeTab.session.setPriorityHint(GeckoSession.PRIORITY_HIGH)
            activeTab.session.setActive(true)
        } catch (_: Exception) {}

        MainActivity.activeGeckoViewInstance?.let { view ->
            try {
                if (view.session != activeTab.session) {
                    view.releaseSession()
                    view.setSession(activeTab.session)
                }
                view.requestLayout()
                view.invalidate()
            } catch (e: Exception) {
                Log.w(TAG, "Error re-attaching session on resume: ${e.message}")
            }
        }
    }

    fun onPause() {
        Log.i(TAG, "onPause() called in BrowserCoordinator")
        currentSession?.let { s ->
            try {
                s.setPriorityHint(GeckoSession.PRIORITY_DEFAULT)
            } catch (e: Exception) {
                Log.w(TAG, "Error setting session priority on pause: ${e.message}")
            }
        }
    }

    fun reloadCurrentTab() {
        val profId = foregroundProfileId ?: return
        val state = runningProfiles[profId] ?: return
        val tab = state.tabs.firstOrNull { it.id == state.activeTabId } ?: state.tabs.firstOrNull() ?: return
        val session = tab.session

        if (!session.isOpen) {
            Log.w(TAG, "Session closed when reload requested. Recovering tab...")
            recoverSession(profId, tab)
            return
        }

        try {
            tab.isLoading = true
            tab.progress = 0.15f
            isPageLoading = true
            pageProgress = 0.15f
            session.reload()
        } catch (e: Exception) {
            Log.e(TAG, "Error reloading session: ${e.message}. Recovering tab...", e)
            recoverSession(profId, tab)
        }
    }

    fun navigateToUrl(target: String) {
        val resolved = resolveNavigationTarget(target)
        urlInputText = resolved
        val profId = foregroundProfileId ?: return
        val state = runningProfiles[profId] ?: return
        val tab = state.tabs.firstOrNull { it.id == state.activeTabId } ?: state.tabs.firstOrNull() ?: return
        val session = tab.session

        tab.url = resolved
        tab.isLoading = true
        tab.progress = 0.15f
        isPageLoading = true
        pageProgress = 0.15f

        if (!session.isOpen) {
            Log.w(TAG, "Session closed when navigate requested. Recovering tab with new URL...")
            recoverSession(profId, tab)
            return
        }

        try {
            session.loadUri(resolved)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading URI '$resolved': ${e.message}. Recovering tab...", e)
            recoverSession(profId, tab)
        }
    }
}
