package dev.audaro.helloshell

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View

/**
 * Tap-feedback BORDER for interface ops (parity with the official Jagex Mobile "you selected this" outline):
 * tapping an item / prayer / spell / tab outlines that widget instead of spawning a world ring. The rect is the
 * engine-resolved widget bounds (from MenuOptionClicked truth), mapped to view px by the caller. The outline
 * holds briefly then fades. Non-interactive: it sits over the game frame but swallows no touches.
 */
class WidgetBorderView(context: Context) : View(context) {

    private data class Border(val rect: RectF, val startMs: Long)

    private val borders = ArrayList<Border>(4)
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = COLOR_BORDER
    }
    private val corner = 4f * density

    init {
        isClickable = false
        isFocusable = false
    }

    companion object {
        private const val COLOR_BORDER = 0xFFFFFFFF.toInt()   // white, like the engine's selected-item outline
        private const val LIFE_MS = 500L                       // hold-then-fade
        private const val HOLD_MS = 150L                       // full opacity before the fade begins
    }

    /** Outline the view-px rect (left, top, right, bottom). Call from the UI thread. */
    fun spawn(left: Float, top: Float, right: Float, bottom: Float) {
        // Inset half the stroke so the outline hugs the slot without clipping at the view edge.
        val h = paint.strokeWidth / 2f
        borders.add(Border(RectF(left + h, top + h, right - h, bottom - h), SystemClock.uptimeMillis()))
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (borders.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        val it = borders.iterator()
        while (it.hasNext()) {
            val b = it.next()
            val age = now - b.startMs
            if (age >= LIFE_MS) { it.remove(); continue }
            val fade = if (age <= HOLD_MS) 1f else 1f - (age - HOLD_MS).toFloat() / (LIFE_MS - HOLD_MS)
            paint.alpha = (fade * 255f).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(b.rect, corner, corner, paint)
        }
        if (borders.isNotEmpty()) postInvalidateOnAnimation()
    }
}
