package dev.audaro.helloshell

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * The A2 press-and-peek native menu: a full-screen, non-interactive overlay drawn over the live SurfaceView
 * (selection is driven by the host's long-press drag, not by this view's own touch). It renders an OSRS-styled
 * "Choose Option" box at the finger — header at the press point, body extending down and toward the side AWAY
 * from the holding hand — and hit-tests the finger to a display-row index.
 *
 * This view owns only the on-screen *presentation* (view-space geometry, touch-friendly row heights). The host
 * (MainActivity) maps the chosen row back to the engine's 19px-header/15px-row geometry to commit the click via
 * JavaBridge.selectMenuPoint, so plugin onClick consumers + Menu Entry Swapper ordering are honoured.
 */
class MenuOverlayView(context: Context) : View(context) {

    /** One menu row in display order (index 0 = top = the engine's left-click default). */
    data class Entry(val opt: String, val tgt: String)

    private var entries: List<Entry> = emptyList()
    private var active = false
    private var boxX = 0f
    private var boxY = 0f
    private var highlight = -1

    // View-space geometry matching the iOS native menu (kMenuHeaderViewH 22 / kMenuRowViewH 32 / kMenuWidth 210),
    // independent of the engine's fixed 19/15 px menu.
    private val headerH = dp(22f)
    private val baseRowH = dp(32f)
    // FIX-10: a slide-select peek menu can't scroll, so a row past the screen edge is unreachable. rowH shrinks
    // (down to a legible floor) in show() when a tall menu wouldn't otherwise fit fully on-screen.
    private var rowH = baseRowH
    private val menuW = dp(210f)
    private val pad = dp(6f)
    private val xTol = dp(44f)   // horizontal slack so a straight slide down still tracks the rows

    private val bgPaint = Paint().apply { color = Color.argb(238, 33, 28, 23) }
    private val borderPaint = Paint().apply {
        color = Color.argb(255, 140, 128, 102); style = Paint.Style.STROKE; strokeWidth = dp(1f); isAntiAlias = true
    }
    private val hlPaint = Paint().apply { color = Color.argb(56, 255, 255, 255) }
    private val headerPaint = Paint().apply {
        color = Color.WHITE; textSize = sp(14f); isFakeBoldText = true; isAntiAlias = true
    }
    private val optPaint = Paint().apply {
        color = Color.WHITE; textSize = sp(16f); isFakeBoldText = true; isAntiAlias = true
    }
    private val tgtPaint = Paint().apply {
        color = Color.argb(255, 140, 217, 255); textSize = sp(16f); isFakeBoldText = true; isAntiAlias = true
    }

    init {
        // Always laid out to full screen (so geometry/draw are valid the instant show() flips `active`); it is
        // non-interactive, so touches fall through to the SurfaceView below. Content is gated on `active`.
        isClickable = false
        isFocusable = false
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity

    /** Show the menu (display-order entries) anchored at the finger; body biased away from the holding hand. */
    fun show(items: List<Entry>, anchorX: Float, anchorY: Float, handRight: Boolean) {
        entries = items
        highlight = -1
        val vw = if (width > 0) width.toFloat() else resources.displayMetrics.widthPixels.toFloat()
        val vh = if (height > 0) height.toFloat() else resources.displayMetrics.heightPixels.toFloat()
        // FIX-10: shrink the rows so even a tall menu fits fully on-screen (rowAt + onDraw use this same rowH,
        // and the host maps by row INDEX, so the shrink is self-consistent and doesn't shift the selection).
        val avail = vh - pad * 2 - headerH
        rowH = if (items.isNotEmpty() && items.size * baseRowH > avail)
            (avail / items.size).coerceAtLeast(dp(22f)) else baseRowH
        val totalH = headerH + items.size * rowH
        // Finger sits near the near top corner; body opens away from the hand.
        var ox = if (handRight) anchorX - (menuW - dp(26f)) else anchorX - dp(26f)
        var oy = anchorY - headerH * 0.5f
        ox = ox.coerceIn(pad, (vw - menuW - pad).coerceAtLeast(pad))
        oy = oy.coerceIn(pad, (vh - totalH - pad).coerceAtLeast(pad))
        boxX = ox
        boxY = oy
        active = true
        invalidate()
    }

    fun hide() {
        active = false
        entries = emptyList()
        highlight = -1
        invalidate()
    }

    val isActive: Boolean get() = active

    /** Display-row index under a finger point (view px), or -1 for the header/cancel zone. Y-driven. */
    fun rowAt(x: Float, y: Float): Int {
        if (!active || entries.isEmpty()) return -1
        if (x < boxX - xTol || x > boxX + menuW + xTol) return -1
        val ry = y - (boxY + headerH)
        if (ry < 0) return -1
        val r = (ry / rowH).toInt()
        return r.coerceAtMost(entries.size - 1)
    }

    /** Set the locked-on row; returns true if it changed (so the host can fire a haptic). */
    fun setHighlight(row: Int): Boolean {
        if (row == highlight) return false
        highlight = row
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        if (!active || entries.isEmpty()) return
        val totalH = headerH + entries.size * rowH
        canvas.drawRect(boxX, boxY, boxX + menuW, boxY + totalH, bgPaint)
        canvas.drawRect(boxX, boxY, boxX + menuW, boxY + totalH, borderPaint)

        // Header "Choose Option".
        val hdrBaseline = boxY + headerH / 2f - (headerPaint.ascent() + headerPaint.descent()) / 2f
        canvas.drawText("Choose Option", boxX + dp(10f), hdrBaseline, headerPaint)

        var ry = boxY + headerH
        for (i in entries.indices) {
            if (i == highlight) {
                canvas.drawRect(boxX, ry, boxX + menuW, ry + rowH, hlPaint)
            }
            val e = entries[i]
            val baseline = ry + rowH / 2f - (optPaint.ascent() + optPaint.descent()) / 2f
            var x = boxX + dp(10f)
            canvas.drawText(e.opt, x, baseline, optPaint)
            if (e.tgt.isNotEmpty()) {
                x += optPaint.measureText(e.opt) + sp(5f)
                canvas.drawText(e.tgt, x, baseline, tgtPaint)
            }
            ry += rowH
        }
    }
}
