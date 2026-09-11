package com.multibrowser.antidetect.engine

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
            Log.e(TAG, "Error sending mute command to session", e)
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
            urlInputText = tab.url
            canGoBackState = tab.canGoBack
            canGoForwardState = tab.canGoForward
            isPageLoading = tab.isLoading
            pageProgress = tab.progress
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
            engine.restoreCookies(profile.cookiesJson)
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
                urlInputText = activeTab.url
                canGoBackState = activeTab.canGoBack
                canGoForwardState = activeTab.canGoForward
                isPageLoading = activeTab.isLoading
                pageProgress = activeTab.progress
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

    fun openNewTab(url: String = "https://www.google.com") {
        val state = currentActiveProfileState ?: return
        val targetUrl = if (url.isBlank()) "https://www.google.com" else url
        val session = engine.createTabSession(state.profile)
        val newTab = BrowserTab(
            session = session,
            title = "New Tab",
            url = targetUrl
        )
        attachDelegatesToSession(state.profile.id, newTab)
        session.loadUri(targetUrl)

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
        persistTabsForProfile(state.profile.id)
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
                    tab.url = newUrl
                    if (profileId == foregroundProfileId && tab.id == runningProfiles[profileId]?.activeTabId) {
                        urlInputText = newUrl
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

                val isMuted = sessionMuteStates[profileId] ?: true
                sendMuteCommandToSession(s, isMuted)

                engine.requestCookies(profileId) { rawCookiesJson, cookieCount ->
                    runningProfiles[profileId]?.let { st ->
                        val tabsJson = serializeTabs(st.tabs)
                        val histJson = profileHistories[profileId] ?: st.profile.historyJson
                        val encryptedCookies = com.multibrowser.antidetect.sync.CookieEngine.encryptCookiePayload(rawCookiesJson)
                        SyncManager.scheduleAutoSave(
                            context,
                            profileId,
                            st.profile.name,
                            encryptedCookies,
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

    fun stopAll() {
        persistAllRunningTabs()
        runningProfiles.keys.forEach { pid ->
            engine.closeProfile(pid)
        }
        runningProfiles = emptyMap()
        foregroundProfileId = null
    }

    fun resolveNavigationTarget(input: String): String {
        val trimmed = input.trim()
        return when {
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
}
