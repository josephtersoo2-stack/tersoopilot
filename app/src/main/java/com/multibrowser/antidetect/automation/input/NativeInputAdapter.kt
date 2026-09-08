package com.multibrowser.antidetect.automation.input

import android.view.KeyEvent
import android.view.View
import com.multibrowser.antidetect.automation.NativeGestureInjector
import com.multibrowser.antidetect.automation.perception.ScreenPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NativeInputAdapter(
    private val targetView: View,
    private val injector: NativeGestureInjector
) : InputController {

    override suspend fun tap(point: ScreenPoint) {
        injector.injectTap(point.x, point.y)
    }

    override suspend fun swipe(start: ScreenPoint, end: ScreenPoint, durationMs: Long) {
        injector.injectBezierSwipe(start.x, start.y, end.x, end.y, durationMs)
    }

    override suspend fun type(text: String, wpm: Int, typoProb: Double) {
        injector.injectText(text, wpm, typoProb)
    }

    override suspend fun back() = withContext(Dispatchers.Main) {
        targetView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        targetView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        Unit
    }
}
