package com.multibrowser.antidetect.automation

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Random
import kotlin.math.pow

class NativeGestureInjector(private val targetView: View) {
    private val random = Random()

    /**
     * Executes a hardware tap at (x, y) with realistic dwell duration.
     */
    suspend fun injectTap(x: Float, y: Float) = withContext(Dispatchers.Main) {
        val downTime = SystemClock.uptimeMillis()
        val pressDuration = (45 + random.nextInt(40)).toLong() // 45-85ms dwell

        // Micro-jitter on coordinates (+/- 2px)
        val jitterX = x + (random.nextFloat() * 4f - 2f)
        val jitterY = y + (random.nextFloat() * 4f - 2f)

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
    }

    /**
     * Dispatches a thumb flick along a Cubic Bézier curve with deceleration.
     */
    suspend fun injectBezierSwipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long = 600
    ) = withContext(Dispatchers.Main) {
        val downTime = SystemClock.uptimeMillis()
        val steps = (durationMs / 16).toInt().coerceAtLeast(20) // ~60fps points

        // Generate curved control points simulating human thumb arc
        val controlX1 = startX + (endX - startX) * 0.25f + (random.nextFloat() * 60f - 30f)
        val controlY1 = startY + (endY - startY) * 0.1f + (random.nextFloat() * 40f - 20f)
        val controlX2 = startX + (endX - startX) * 0.75f + (random.nextFloat() * 60f - 30f)
        val controlY2 = startY + (endY - startY) * 0.85f + (random.nextFloat() * 40f - 20f)

        // Dispatch ACTION_DOWN
        val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, startX, startY, 0)
        targetView.dispatchTouchEvent(downEvent)
        downEvent.recycle()

        for (i in 1..steps) {
            val t = i.toFloat() / steps
            // Ease-Out Cubic timing curve
            val easeOutT = 1f - (1f - t).pow(3)

            val currentX = calculateCubicBezier(startX, controlX1, controlX2, endX, easeOutT)
            val currentY = calculateCubicBezier(startY, controlY1, controlY2, endY, easeOutT)

            val moveTime = downTime + (durationMs * t).toLong()
            val moveEvent = MotionEvent.obtain(downTime, moveTime, MotionEvent.ACTION_MOVE, currentX, currentY, 0)
            targetView.dispatchTouchEvent(moveEvent)
            moveEvent.recycle()

            delay(16)
        }

        // Dispatch ACTION_UP
        val upTime = downTime + durationMs
        val upEvent = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, endX, endY, 0)
        targetView.dispatchTouchEvent(upEvent)
        upEvent.recycle()
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

            val keyCode = charToKeyCode(char)
            if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                targetView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                delay((20 + random.nextInt(30)).toLong())
                targetView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            }

            // Extended hesitation at spaces (word boundaries)
            if (char == ' ') {
                delay((120 + random.nextInt(180)).toLong())
            } else {
                delay(charDelay)
            }
        }
    }

    private fun calculateCubicBezier(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        return (1 - t).pow(3) * p0 +
                3 * (1 - t).pow(2) * t * p1 +
                3 * (1 - t) * t.pow(2) * p2 +
                t.pow(3) * p3
    }

    private fun charToKeyCode(c: Char): Int {
        return when (c.lowercaseChar()) {
            in 'a'..'z' -> KeyEvent.KEYCODE_A + (c.lowercaseChar() - 'a')
            in '0'..'9' -> KeyEvent.KEYCODE_0 + (c - '0')
            ' ' -> KeyEvent.KEYCODE_SPACE
            '\n' -> KeyEvent.KEYCODE_ENTER
            '.' -> KeyEvent.KEYCODE_PERIOD
            ',' -> KeyEvent.KEYCODE_COMMA
            else -> KeyEvent.KEYCODE_UNKNOWN
        }
    }
}
