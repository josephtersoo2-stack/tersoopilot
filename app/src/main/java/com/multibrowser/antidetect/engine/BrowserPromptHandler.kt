package com.multibrowser.antidetect.engine

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.EditText
import android.widget.LinearLayout
import com.multibrowser.antidetect.MainActivity
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession

/**
 * Handles web-initiated JavaScript alerts, confirms, and prompts
 * with native Android AlertDialog popups.
 *
 * If the activity is not visible or in background, prompts are safely dismissed
 * so background tasks never hang.
 */
class BrowserPromptHandler(
    private val context: Context
) : GeckoSession.PromptDelegate {

    private val TAG = "BrowserPromptHandler"
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onAlertPrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.AlertPrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()

        val activity = MainActivity.activeInstance
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            return GeckoResult.fromValue(prompt.dismiss())
        }

        mainHandler.post {
            try {
                AlertDialog.Builder(activity)
                    .setTitle(if (prompt.title.isNullOrBlank()) "Website Notice" else prompt.title)
                    .setMessage(prompt.message ?: "")
                    .setCancelable(false)
                    .setPositiveButton("OK") { dialog, _ ->
                        dialog.dismiss()
                        result.complete(prompt.dismiss())
                    }
                    .setOnCancelListener {
                        result.complete(prompt.dismiss())
                    }
                    .show()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to display alert prompt dialog: ${e.message}")
                result.complete(prompt.dismiss())
            }
        }

        return result
    }

    override fun onButtonPrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.ButtonPrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()

        val activity = MainActivity.activeInstance
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            return GeckoResult.fromValue(prompt.dismiss())
        }

        mainHandler.post {
            try {
                AlertDialog.Builder(activity)
                    .setTitle(if (prompt.title.isNullOrBlank()) "Confirmation" else prompt.title)
                    .setMessage(prompt.message ?: "")
                    .setCancelable(false)
                    .setPositiveButton("OK") { dialog, _ ->
                        dialog.dismiss()
                        result.complete(prompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.POSITIVE))
                    }
                    .setNegativeButton("Cancel") { dialog, _ ->
                        dialog.dismiss()
                        result.complete(prompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.NEGATIVE))
                    }
                    .setOnCancelListener {
                        result.complete(prompt.dismiss())
                    }
                    .show()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to display button prompt dialog: ${e.message}")
                result.complete(prompt.dismiss())
            }
        }

        return result
    }

    override fun onTextPrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.TextPrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()

        val activity = MainActivity.activeInstance
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            return GeckoResult.fromValue(prompt.dismiss())
        }

        mainHandler.post {
            try {
                val input = EditText(activity).apply {
                    setText(prompt.defaultValue ?: "")
                    setSelection((prompt.defaultValue ?: "").length)
                }
                val container = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(50, 20, 50, 10)
                    addView(input)
                }

                AlertDialog.Builder(activity)
                    .setTitle(if (prompt.title.isNullOrBlank()) "Prompt" else prompt.title)
                    .setMessage(prompt.message ?: "")
                    .setView(container)
                    .setCancelable(false)
                    .setPositiveButton("OK") { dialog, _ ->
                        dialog.dismiss()
                        val text = input.text.toString()
                        result.complete(prompt.confirm(text))
                    }
                    .setNegativeButton("Cancel") { dialog, _ ->
                        dialog.dismiss()
                        result.complete(prompt.dismiss())
                    }
                    .setOnCancelListener {
                        result.complete(prompt.dismiss())
                    }
                    .show()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to display text prompt dialog: ${e.message}")
                result.complete(prompt.dismiss())
            }
        }

        return result
    }
}
