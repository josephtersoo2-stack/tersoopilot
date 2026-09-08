package com.multibrowser.antidetect.automation.perception

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession

class SnapshotBridge(private val session: GeckoSession) {
    private val gson = Gson()
    private val TAG = "SnapshotBridge"

    /**
     * Executes window.extractDomSnapshot() in the GeckoSession page context
     * and deserializes the resulting JSON into a DomSnapshot.
     */
    suspend fun captureSnapshot(timeoutMs: Long = 4000L): DomSnapshot? = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<String?>()

        // Install a temporary prompt delegate to capture evaluatePromise / prompt return
        val originalPromptDelegate = session.promptDelegate
        session.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onAlertPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.AlertPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                val message = prompt.message
                if (message != null && (message.startsWith("{\"url\":") || message == "{}")) {
                    deferred.complete(message)
                }
                return GeckoResult.fromValue(prompt.dismiss())
            }
        }

        try {
            // Trigger perception extractor via alert bridge
            session.loadUri(
                "javascript:(function(){" +
                "  try {" +
                "    if (typeof window.extractDomSnapshot === 'function') {" +
                "      alert(window.extractDomSnapshot());" +
                "    } else {" +
                "      alert('{}');" +
                "    }" +
                "  } catch(e) {" +
                "    alert('{}');" +
                "  }" +
                "})();"
            )

            val rawJson = withTimeoutOrNull(timeoutMs) { deferred.await() }
            if (!rawJson.isNullOrBlank() && rawJson != "{}") {
                gson.fromJson(rawJson, DomSnapshot::class.java)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Snapshot extraction failed: ${e.message}")
            null
        } finally {
            session.promptDelegate = originalPromptDelegate
        }
    }
}
