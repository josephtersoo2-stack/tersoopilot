package com.multibrowser.antidetect.automation

import android.util.Log
import com.multibrowser.antidetect.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/**
 * Coordinates physical screen / GeckoView display focus across concurrent automation profiles.
 * Because a physical device has only one display pipeline and hardware composer,
 * interactive commands (taps, swipes, DOM snapshots, visual captures) acquire this mutex
 * so the targeted GeckoSession is attached to the active GeckoView before interaction.
 *
 * Passive dwell (playback wait, idle dwell) executes WITHOUT holding this mutex,
 * allowing other concurrent profiles to take turns interacting with the screen.
 */
object DisplayFocusManager {
    private const val TAG = "DisplayFocusManager"
    private const val LOCK_TIMEOUT_MS = 120_000L

    val displayMutex = Mutex()
    var currentFocusedProfileId: String? = null
        private set

    /**
     * Executes the given block under display mutual exclusion.
     * Ensures the session is attached to the view before executing block.
     */
    suspend fun <T> withDisplayFocus(
        profileId: String,
        session: GeckoSession,
        geckoView: GeckoView?,
        actionName: String = "DisplayAction",
        block: suspend () -> T
    ): T {
        return withTimeout(LOCK_TIMEOUT_MS) {
            displayMutex.withLock {
                try {
                    ensureDisplayAttached(profileId, session, geckoView)
                    block()
                } finally {
                    // Lock is released upon exiting withLock block
                }
            }
        }
    }

    private suspend fun ensureDisplayAttached(
        profileId: String,
        session: GeckoSession,
        geckoView: GeckoView?
    ) = withContext(Dispatchers.Main) {
        val viewToAttach = MainActivity.activeGeckoViewInstance ?: geckoView
        if (viewToAttach != null && (currentFocusedProfileId != profileId || viewToAttach.session != session)) {
            Log.i(TAG, "Switching display focus to profile: $profileId")
            if (viewToAttach.session != null && viewToAttach.session != session) {
                viewToAttach.releaseSession()
            }
            viewToAttach.setSession(session)
            currentFocusedProfileId = profileId
            // Settle time for surface buffer swap
            delay(200L)
        }
    }
}
