package com.multibrowser.antidetect.automation.perception

import android.graphics.RectF
import android.view.View
import java.util.Random

data class ScreenPoint(
    val x: Float,
    val y: Float
)

class CoordinateMapper(private val geckoView: View) {
    private val random = Random()

    /**
     * Translates a CSS-based DOM bounding box into absolute Android View coordinates.
     * Takes into account Device Pixel Ratio (DPR) and GeckoView's physical screen location.
     */
    fun mapDomToScreen(rect: DomRect, dpr: Float): RectF {
        val location = IntArray(2)
        geckoView.getLocationOnScreen(location)
        val surfaceOffsetX = location[0].toFloat()
        val surfaceOffsetY = location[1].toFloat()

        // Scale CSS pixels by DPR and offset by GeckoView screen placement
        val left = (rect.left * dpr) + surfaceOffsetX
        val top = (rect.top * dpr) + surfaceOffsetY
        val right = ((rect.left + rect.width) * dpr) + surfaceOffsetX
        val bottom = ((rect.top + rect.height) * dpr) + surfaceOffsetY

        return RectF(left, top, right, bottom)
    }

    /**
     * Chooses an organic, humanized touch point inside the target's bounding box.
     * Confines taps between 25% and 75% of the element's width/height to prevent edge misses.
     */
    fun getOrganicTapPoint(screenRect: RectF): ScreenPoint {
        // Fallback to center point if bounding box is unexpectedly zero or negative
        if (screenRect.width() <= 0f || screenRect.height() <= 0f) {
            return ScreenPoint(screenRect.centerX(), screenRect.centerY())
        }

        val minX = screenRect.left + (screenRect.width() * 0.25f)
        val maxX = screenRect.left + (screenRect.width() * 0.75f)
        val minY = screenRect.top + (screenRect.height() * 0.25f)
        val maxY = screenRect.top + (screenRect.height() * 0.75f)

        val targetX = minX + (random.nextFloat() * (maxX - minX))
        val targetY = minY + (random.nextFloat() * (maxY - minY))

        return ScreenPoint(targetX, targetY)
    }
}
