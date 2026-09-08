package com.multibrowser.antidetect.automation.input

import com.multibrowser.antidetect.automation.perception.ScreenPoint

interface InputController {
    suspend fun tap(point: ScreenPoint)
    suspend fun swipe(start: ScreenPoint, end: ScreenPoint, durationMs: Long)
    suspend fun type(text: String, wpm: Int = 65, typoProb: Double = 0.03)
    suspend fun back()
}
