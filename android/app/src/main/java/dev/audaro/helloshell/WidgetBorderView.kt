package dev.audaro.helloshell

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View

/**
 * Tap-feedback BORDER for interface ops, matching the scene's tap-target outline: tapping an item / prayer /
 * spell / tab outlines that widget instead of spawning a world ring. Same CYAN as the engine's NPC/object model
 * outline (JavaBridge.SEL_OUTLINE = 60,224,255,235), drawn FLUSH to the engine-resolved widget bounds (the
 * item's own slot, not the grid) — a sharp rect, no rounding/padding. Holds ~SEL_FLASH_MS then fades.
 * Non-interactive: sits over the game frame, swallows no touches.
 */
class WidgetBorderView(context: Context) : View(context) {

    private data class Border(val rect: RectF, val startMs: Long)

    private val borders = ArrayList<Border>(4)
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = COLOR_BORDER
    }

    init {
        isClickable = false
        isFocusable = false
    }

    companion object {
        // Cyan, identical to the engine's NPC/object selection outline (Color(60,224,255,235)).
        private const val COLOR_BORDER = 0xEB3CE0FF.toInt()
        private const val LIFE_MS = 1500L     // matches JavaBridge SEL_FLASH_MS (the NPC outline hold)
        private const val HOLD_MS = 1150L     // full opacity, then a short fade to LIFE_MS
    }

    /** Outline the view-px rect (left, top, right, bottom) flush. Call from the UI thread. */
    fun spawn(left: Float, top: Float, right: Float, bottom: Float) {
        // Inset half the stroke so the outline sits ON the slot edge (flush), not clipped or ballooned.
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
            canvas.drawRect(b.rect, paint)
        }
        if (borders.isNotEmpty()) postInvalidateOnAnimation()
    }
}
