package dev.audaro.runemobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.View

/**
 * Tap-feedback ring (parity with the iOS click ring): every world tap spawns an expanding/fading ring at the
 * touch point — YELLOW for a plain walk, RED when the engine consumed an interaction.
 *
 * The ring is drawn when the finger LIFTS, in the walk colour, because that is when the user is owed it; the
 * engine-resolved kind arrives ~80–160 ms later and is applied to the ring already on screen rather than
 * deciding whether to draw one ([recolor] repaints it, [retire] withdraws it). Waiting for truth before
 * drawing is what made the commonest tap in the game — a walk, which resolves by never bumping anything —
 * mark itself a quarter-second after a finger that had already left the glass.
 *
 * Non-interactive: it sits over the game frame but swallows no touches. The engine's own click-cross sprites
 * are blanked by the shared runtime (runelite.touch.hideClickCross), so this is the only tap feedback shown.
 */
class TapRingView(
    context: Context,
) : View(context) {
    private class Ring(
        val x: Float,
        val y: Float,
        var color: Int,
        val startMs: Long,
        val id: Int,
    ) {
        // Withdrawn rings freeze at the size they had reached and fade what is left of them out quickly:
        // dropping the layer outright mid-animation reads as a flicker. 0 = still running normally.
        var retiredMs: Long = 0L
        var retiredScale: Float = 0f
        var retiredAlpha: Float = 0f
    }

    private val rings = ArrayList<Ring>(4)
    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f * resources.displayMetrics.density
        }
    private val baseRadius = 16f * resources.displayMetrics.density

    init {
        // Never intercept the gesture stream the SurfaceView owns.
        isClickable = false
        isFocusable = false
    }

    companion object {
        const val COLOR_INTERACT = 0xFFE53935.toInt() // red: the engine consumed an interaction
        const val COLOR_WALK = 0xFFFFD54F.toInt() // yellow: plain walk
        private const val LIFE_MS = 450L // matches the iOS 0.45 s scale-and-fade
        private const val RETIRE_MS = 120L // the quick fade a withdrawn ring gets (iOS retireRing: 0.12 s)
        private const val SCALE_FROM = 0.5f
        private const val SCALE_TO = 2.2f
    }

    private var nextId = 1

    /**
     * Spawn a ring at view-local (x, y) and return its id, so the caller can still repaint or withdraw it
     * while it is alive. Call from the UI thread.
     */
    fun spawn(
        x: Float,
        y: Float,
        color: Int,
    ): Int {
        val id = nextId++
        rings.add(Ring(x, y, color, SystemClock.uptimeMillis(), id))
        invalidate()
        return id
    }

    /**
     * Repaint a live ring in place — the marker drawn at the lift is the right ring in the wrong colour once
     * the engine says the tap was an interaction. A ring that has already finished its life is simply gone,
     * and this is a no-op; the caller does not have to track that.
     */
    fun recolor(
        id: Int,
        color: Int,
    ) {
        val r = find(id) ?: return
        r.color = color
        invalidate()
    }

    /** Take a live ring back (the engine cuffs the slot itself, or the tap hit nothing): freeze and fade. */
    fun retire(id: Int) {
        val r = find(id) ?: return
        if (r.retiredMs != 0L) return
        val now = SystemClock.uptimeMillis()
        val t = ((now - r.startMs).toFloat() / LIFE_MS).coerceIn(0f, 1f)
        r.retiredMs = now
        r.retiredScale = SCALE_FROM + (SCALE_TO - SCALE_FROM) * t
        r.retiredAlpha = 1f - t
        invalidate()
    }

    private fun find(id: Int): Ring? {
        if (id <= 0) return null
        return rings.firstOrNull { it.id == id }
    }

    override fun onDraw(canvas: Canvas) {
        if (rings.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        val it = rings.iterator()
        while (it.hasNext()) {
            val r = it.next()
            val scale: Float
            val alpha: Float
            if (r.retiredMs != 0L) {
                val rt = (now - r.retiredMs).toFloat() / RETIRE_MS
                if (rt >= 1f) {
                    it.remove()
                    continue
                }
                scale = r.retiredScale // frozen at the size it had reached
                alpha = r.retiredAlpha * (1f - rt)
            } else {
                val t = (now - r.startMs).toFloat() / LIFE_MS
                if (t >= 1f) {
                    it.remove()
                    continue
                }
                scale = SCALE_FROM + (SCALE_TO - SCALE_FROM) * t
                alpha = 1f - t
            }
            paint.color = r.color
            paint.alpha = (alpha * 255f).toInt()
            canvas.drawCircle(r.x, r.y, baseRadius * scale, paint)
        }
        if (rings.isNotEmpty()) postInvalidateOnAnimation()
    }
}
