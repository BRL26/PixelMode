package com.pixelmode.app.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.view.View

/**
 * Draws a 1px on/off checkerboard so that exactly half of the panel's pixels are black.
 *
 * The bitmap is generated once per (native size, phase) change at the display's native pixel
 * resolution — no density scaling, no filtering (nearest-neighbour only) — and then simply
 * drawn 1:1. There is no invalidation loop and no redraw timer: battery cost is zero while
 * nothing changes.
 *
 * "On" pixels are opaque black; "off" pixels are transparent so the UI stays visible between
 * the black pixels (the opacity slider scales the whole view).
 */
class CheckerboardView(context: Context) : View(context) {

    private val bitmapPaint = Paint().apply { isFilterBitmap = false }

    private var bitmap: Bitmap? = null
    private var bmpW = -1
    private var bmpH = -1
    private var phaseFlipped: Boolean? = null

    /** Rebuild the pattern only when the native size or the phase actually changed. */
    fun setPattern(widthPx: Int, heightPx: Int, flipped: Boolean) {
        if (widthPx <= 0 || heightPx <= 0) return
        if (bitmap != null && bmpW == widthPx && bmpH == heightPx && phaseFlipped == flipped) return
        bitmap?.recycle()
        bitmap = buildCheckerboard(widthPx, heightPx, flipped)
        bmpW = widthPx
        bmpH = heightPx
        phaseFlipped = flipped
        invalidate() // single redraw, triggered only by an explicit user change
    }

    /** 0..100 % → view alpha (a RenderNode property; no redraw loop). */
    fun setPatternOpacity(percent: Int) {
        alpha = percent.coerceIn(0, 100) / 100f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = bitmap ?: return
        canvas.drawBitmap(b, 0f, 0f, bitmapPaint) // 1:1, unfiltered
    }

    private fun buildCheckerboard(w: Int, h: Int, flipped: Boolean): Bitmap {
        val target = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        // A 2x2 tile repeated with nearest-neighbour sampling yields an exact 1px grid.
        val tile = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        for (y in 0..1) {
            for (x in 0..1) {
                val on = ((x + y) % 2 == 0) xor flipped
                tile.setPixel(x, y, if (on) Color.BLACK else Color.TRANSPARENT)
            }
        }
        val tileShader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        val paint = Paint().apply {
            isFilterBitmap = false
            shader = tileShader
        }
        Canvas(target).drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        tile.recycle()
        return target
    }
}
