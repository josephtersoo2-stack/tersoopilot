package com.multibrowser.antidetect.automation.perception

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.PixelCopy
import androidx.annotation.RequiresApi
import com.multibrowser.antidetect.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoView
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

object ScreenCaptureBridge {
    private const val TAG = "ScreenCaptureBridge"

    /**
     * Captures a screenshot of the current browser display, downscales it to maxDimension,
     * compresses to JPEG with given quality, and returns the Base64 string.
     */
    suspend fun captureBase64(
        geckoView: GeckoView?,
        maxDimension: Int = 768,
        quality: Int = 75
    ): String? = withContext(Dispatchers.Main) {
        val bitmap = captureBitmap(geckoView) ?: return@withContext null
        try {
            val scaled = downscaleBitmap(bitmap, maxDimension)
            val outputStream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            val bytes = outputStream.toByteArray()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode screenshot: ${e.message}", e)
            null
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private suspend fun captureBitmap(geckoView: GeckoView?): Bitmap? {
        val window = MainActivity.activeInstance?.window
        val targetView = geckoView ?: MainActivity.activeGeckoViewInstance

        if (window != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                return captureWithPixelCopy(window, targetView)
            } catch (e: Exception) {
                Log.w(TAG, "PixelCopy capture failed, falling back to view.draw: ${e.message}")
            }
        }

        if (targetView != null && targetView.width > 0 && targetView.height > 0) {
            try {
                val bitmap = Bitmap.createBitmap(targetView.width, targetView.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                targetView.draw(canvas)
                return bitmap
            } catch (e: Exception) {
                Log.e(TAG, "view.draw fallback failed: ${e.message}", e)
            }
        }

        return null
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun captureWithPixelCopy(window: android.view.Window, targetView: GeckoView?): Bitmap? {
        val view = targetView ?: window.decorView
        val width = view.width.coerceAtLeast(1)
        val height = view.height.coerceAtLeast(1)

        val location = IntArray(2)
        view.getLocationInWindow(location)
        val rect = Rect(location[0], location[1], location[0] + width, location[1] + height)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val handler = Handler(Looper.getMainLooper())

        return suspendCancellableCoroutine { cont ->
            PixelCopy.request(window, rect, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) {
                    cont.resume(bitmap)
                } else {
                    bitmap.recycle()
                    cont.resume(null)
                }
            }, handler)
        }
    }

    private fun downscaleBitmap(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxDimension && h <= maxDimension) return bitmap

        val ratio = w.toFloat() / h.toFloat()
        val targetW: Int
        val targetH: Int
        if (ratio > 1f) {
            targetW = maxDimension
            targetH = (maxDimension / ratio).toInt().coerceAtLeast(1)
        } else {
            targetH = maxDimension
            targetW = (maxDimension * ratio).toInt().coerceAtLeast(1)
        }
        val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        if (scaled != bitmap) {
            bitmap.recycle()
        }
        return scaled
    }
}
