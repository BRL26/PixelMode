package com.pixelmode.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import com.pixelmode.app.model.ModeState

/**
 * Owns the TYPE_APPLICATION_OVERLAY window that shows the checkerboard.
 *
 * The window spans the display's native pixel size (read at runtime, never via density
 * conversion), sits above the status and navigation bars (FLAG_LAYOUT_IN_SCREEN /
 * FLAG_LAYOUT_NO_LIMITS), never steals input (FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE)
 * and is re-created at the new size when the display rotates.
 */
class OverlayController(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var view: CheckerboardView? = null
    private var widthPx = 0
    private var heightPx = 0

    var lastError: String? = null
        private set

    val isActive: Boolean get() = view != null

    /** Human-readable native size of the overlay window (for the status log). */
    fun describeSize(): String = when {
        widthPx > 0 && heightPx > 0 -> "${widthPx}×$heightPx px (native)"
        else -> "size unknown"
    }

    fun show(state: ModeState) {
        if (!state.checkerOn) {
            hide()
            return
        }
        val size = nativeDisplaySize()
        if (view == null || size.x != widthPx || size.y != heightPx) {
            removeView()
            val newView = CheckerboardView(context)
            try {
                windowManager.addView(newView, layoutParams(size))
                view = newView
                widthPx = size.x
                heightPx = size.y
                lastError = null
            } catch (t: Throwable) {
                lastError =
                    "Overlay window rejected: ${t.message}. Is SYSTEM_ALERT_WINDOW still granted?"
                view = null
                return
            }
        }
        view?.setPattern(size.x, size.y, state.checkerPhaseFlipped)
        view?.setPatternOpacity(state.checkerOpacity)
    }

    fun hide() {
        removeView()
        lastError = null
    }

    private fun removeView() {
        val v = view ?: return
        view = null
        widthPx = 0
        heightPx = 0
        runCatching { windowManager.removeViewImmediate(v) }
    }

    /**
     * Native pixel resolution of the panel, orientation aware. We take whichever candidate
     * is larger so a density/inset-trimmed window bound can never shrink the bitmap.
     */
    private fun nativeDisplaySize(): Point {
        var w = 0
        var h = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            w = bounds.width()
            h = bounds.height()
        }
        val dm = context.getSystemService(DisplayManager::class.java)
        @Suppress("DEPRECATION")
        dm?.getDisplay(Display.DEFAULT_DISPLAY)?.let { display ->
            val real = Point()
            display.getRealSize(real)
            if (real.x * real.y > w * h) {
                w = real.x
                h = real.y
            }
        }
        return Point(w, h)
    }

    private fun layoutParams(size: Point) =
        WindowManager.LayoutParams(
            size.x,
            size.y,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            title = "PixelModeCheckerboard"
            layoutInDisplayCutoutMode =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
        }
}
