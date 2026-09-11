package com.multibrowser.antidetect.automation

import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Random
import kotlin.math.pow

data class ScreenBounds(
    val width: Int,
    val height: Int
)

data class GestureFailure(
    val gestureType: String,
    val reason: String,
    val x: Float,
    val y: Float,
    val timestamp: Long = System.currentTimeMillis()
)

class NativeGestureInjector(private val targetView: View) {
    private val random = Random()
    private val TAG = "NativeGestureInjector"

    var lastFailure: GestureFailure? = null
        private set

    /**
     * Resolves dynamic target screen bounds, handling rotation or unmeasured views gracefully.
     */
    fun getScreenBounds(): ScreenBounds {
        val w = targetView.width.takeIf { it > 0 } ?: targetView.resources.displayMetrics.widthPixels
        val h = targetView.height.takeIf { it > 0 } ?: targetView.resources.displayMetrics.heightPixels
        return ScreenBounds(w, h)
    }

    /**
     * Validates whether target coordinates are within screen bounds.
     */
    fun validateCoordinates(x: Float, y: Float, gestureType: String): Boolean {
        val bounds = getScreenBounds()
        if (x < 0 || x > bounds.width || y < 0 || y > bounds.height) {
            val failure = GestureFailure(
                gestureType = gestureType,
                reason = "Coordinates ($x, $y) outside screen bounds (${bounds.width}x${bounds.height})",
                x = x,
                y = y
            )
            lastFailure = failure
            Log.w(TAG, failure.reason)
            return false
        }
        return true
    }

    /**
     * Executes a hardware tap at (x, y) with realistic dwell duration.
     * Enforces screen bounds validation and clamping to prevent injection faults.
     */
    suspend fun injectTap(x: Float, y: Float): Boolean = withContext(Dispatchers.Main) {
        val bounds = getScreenBounds()
        val clampedX = x.coerceIn(0f, bounds.width.toFloat())
        val clampedY = y.coerceIn(0f, bounds.height.toFloat())

        if (x != clampedX || y != clampedY) {
            lastFailure = GestureFailure(
                gestureType = "TAP",
                reason = "Clamped out-of-bounds tap ($x, $y) to ($clampedX, $clampedY)",
                x = x,
                y = y
            )
            Log.w(TAG, lastFailure!!.reason)
        }

        val downTime = SystemClock.uptimeMillis()
        val pressDuration = (45 + random.nextInt(40)).toLong() // 45-85ms dwell

        // Micro-jitter on coordinates (+/- 2px), clamped
        val jitterX = (clampedX + (random.nextFloat() * 4f - 2f)).coerceIn(0f, bounds.width.toFloat())
        val jitterY = (clampedY + (random.nextFloat() * 4f - 2f)).coerceIn(0f, bounds.height.toFloat())

        val downEvent = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN,
            jitterX, jitterY, 0
        )
        targetView.dispatchTouchEvent(downEvent)
        downEvent.recycle()

        delay(pressDuration)

        val eventTime = SystemClock.uptimeMillis()
        val upEvent = MotionEvent.obtain(
            downTime, eventTime, MotionEvent.ACTION_UP,
            jitterX, jitterY, 0
        )
        targetView.dispatchTouchEvent(upEvent)
        upEvent.recycle()
        true
    }

    /**
     * Dispatches a thumb flick along a Cubic Bézier curve with deceleration.
     * Enforces start and end point bounds validation.
     */
    suspend fun injectBezierSwipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long = 600
    ): Boolean = withContext(Dispatchers.Main) {
        val bounds = getScreenBounds()
        val safeStartX = startX.coerceIn(0f, bounds.width.toFloat())
        val safeStartY = startY.coerceIn(0f, bounds.height.toFloat())
        val safeEndX = endX.coerceIn(0f, bounds.width.toFloat())
        val safeEndY = endY.coerceIn(0f, bounds.height.toFloat())

        val downTime = SystemClock.uptimeMillis()
        val steps = (durationMs / 16).toInt().coerceAtLeast(20) // ~60fps points

        // Generate curved control points simulating human thumb arc
        val controlX1 = safeStartX + (safeEndX - safeStartX) * 0.25f + (random.nextFloat() * 60f - 30f)
        val controlY1 = safeStartY + (safeEndY - safeStartY) * 0.1f + (random.nextFloat() * 40f - 20f)
        val controlX2 = safeStartX + (safeEndX - safeStartX) * 0.75f + (random.nextFloat() * 60f - 30f)
        val controlY2 = safeStartY + (safeEndY - safeStartY) * 0.85f + (random.nextFloat() * 40f - 20f)

        // Dispatch ACTION_DOWN
        val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, safeStartX, safeStartY, 0)
        targetView.dispatchTouchEvent(downEvent)
        downEvent.recycle()

        for (i in 1..steps) {
            val t = i.toFloat() / steps
            // Ease-Out Cubic timing curve
            val easeOutT = 1f - (1f - t).pow(3)

            val currentX = calculateCubicBezier(safeStartX, controlX1, controlX2, safeEndX, easeOutT).coerceIn(0f, bounds.width.toFloat())
            val currentY = calculateCubicBezier(safeStartY, controlY1, controlY2, safeEndY, easeOutT).coerceIn(0f, bounds.height.toFloat())

            val moveTime = downTime + (durationMs * t).toLong()
            val moveEvent = MotionEvent.obtain(downTime, moveTime, MotionEvent.ACTION_MOVE, currentX, currentY, 0)
            targetView.dispatchTouchEvent(moveEvent)
            moveEvent.recycle()

            delay(16)
        }

        // Dispatch ACTION_UP
        val upTime = downTime + durationMs
        val upEvent = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, safeEndX, safeEndY, 0)
        targetView.dispatchTouchEvent(upEvent)
        upEvent.recycle()
        true
    }

    /**
     * Types text using realistic per-character typing cadences and pauses.
     */
    suspend fun injectText(text: String, wpm: Int = 65, typoProb: Double = 0.03) = withContext(Dispatchers.Main) {
        val baseDelayMs = (60000 / (wpm * 5)).coerceIn(50, 250)

        for (char in text) {
            // Natural keystroke cadence variation
            val jitter = (random.nextGaussian() * (baseDelayMs * 0.35)).toLong()
            val charDelay = (baseDelayMs + jitter).coerceIn(40, 450)

            // Simulate occasional typo & backspace correction
            if (random.nextDouble() < typoProb && char.isLetter()) {
                val typoChar = (char.code + if (random.nextBoolean()) 1 else -1).toChar()
                dispatchCharKey(typoChar)
                delay((baseDelayMs * 1.5).toLong()) // Realization pause
                dispatchKeyEvent(KeyEvent.KEYCODE_DEL) // Backspace
                delay((baseDelayMs * 0.8).toLong())
            }

            dispatchCharKey(char)
            delay(charDelay)
        }
    }

    private fun dispatchCharKey(char: Char) {
        val eventTime = SystemClock.uptimeMillis()
        val keyEvent = KeyEvent(
            eventTime,
            char.toString(),
            0,
            0
        )
        targetView.dispatchKeyEvent(keyEvent)
    }

    private fun dispatchKeyEvent(keyCode: Int) {
        val downTime = SystemClock.uptimeMillis()
        val down = KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0)
        targetView.dispatchKeyEvent(down)

        val upTime = SystemClock.uptimeMillis()
        val up = KeyEvent(downTime, upTime, KeyEvent.ACTION_UP, keyCode, 0)
        targetView.dispatchKeyEvent(up)
    }

    private fun calculateCubicBezier(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        return (1 - t).pow(3) * p0 +
                3 * (1 - t).pow(2) * t * p1 +
                3 * (1 - t) * t.pow(2) * p2 +
                t.pow(3) * p3
    }
}
