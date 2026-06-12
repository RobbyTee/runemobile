package dev.audaro.helloshell

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import org.json.JSONObject
import kotlin.math.hypot

/**
 * Phase-4 chat channel PILL (iOS parity, `bea94a90a0`…`9a8923a2c9`). At rest a single collapsed pill (one tab
 * wide) names the open channel over the relocated chat. A quick tap toggles the chat message-area collapse
 * (shared shove-off-canvas, never hide). Holding ~0.15 s fans 7 sprite-backed chips across the chat width;
 * dragging sideways highlights and a release switches the open channel (op-script replay — works collapsed);
 * pulling DOWN past a filterable chip's bottom edge opens that channel's engine filter menu at the finger
 * (the shared press-and-peek overlay), with a ▾ hint revealing as the finger nears the edge. The pull-down is
 * a DIRECTION, not a dwell — hesitating can't misfire, and it can't trigger during the horizontal drag-select.
 *
 * The chips are display-only: this view owns the whole gesture from the pill DOWN, so nothing else intercepts.
 * Geometry comes from HudVeneerBridge.getChatSnapshot each frame; the snapshot keeps reporting the CONFIGURED
 * chat origin while collapsed (the shove moves the widget, not the configured position), so the pill stays put.
 */
class ChatPillView(context: Context) : View(context) {

    companion object {
        const val TAB_COUNT = 7
        const val CHAT_W = 519          // engine chatbox width (canvas px) — the fan row spans this
        const val ROW_H_CANVAS = 32     // 2-line chip height (canvas px); pushed into the engine row height
        private val NAMES = arrayOf("All", "Game", "Public", "Private", "Channel", "Clan", "Trade")
        private val FILTERABLE = booleanArrayOf(false, false, true, true, true, true, true)
        private const val NAME_DEFAULT = 0xFFFFF7D9.toInt()   // off-white
        private const val FILTER_DEFAULT = 0xFF00E000.toInt() // green ("On")
        private const val HINT_COLOR = 0xFFFFEB59.toInt()     // the iOS ▾ yellow

        private const val HOLD_DELAY_MS = 150L   // kChatHoldDelay — press time before the chips fan out
        private const val FAN_ANIM_MS = 180L     // kChatFanAnim
        private const val PULL_DOWN_DP = 26f     // kChatPullDownPt — travel below a chip that opens its filter
        private const val HINT_START_DP = 10f    // kChatFilterHintPt — below mid-chip where the ▾ starts revealing
        private const val DRAG_MOVED_DP = 16f    // travel that marks a real drag (a release then switches)
    }

    /** Quick tap on the pill: the host toggles the chat collapse (shared setChatCollapsed). */
    var onPillTap: (() -> Unit)? = null

    /** Release on chip [index]: the host switches the open channel (switchChatTab; expand-first if collapsed). */
    var onSwitchChannel: ((index: Int) -> Unit)? = null

    /** Pull-down crossed below filterable chip [index]: the host opens the engine filter menu at the live
     *  button centre (engine canvas px) anchored at the finger (view px). Return true once the menu opened
     *  (the on-canvas gate passed); false = not yet (e.g. collapsed chat still expanding) — retried each move. */
    var onFilterOpen: ((index: Int, engineCx: Int, engineCy: Int, viewX: Float, viewY: Float) -> Boolean)? = null
    var onFilterMove: ((viewX: Float, viewY: Float) -> Unit)? = null
    var onFilterEnd: ((cancelled: Boolean) -> Unit)? = null

    /** Light haptic cue (fan-out, hover change, filter open). */
    var onHaptic: (() -> Unit)? = null

    private class Tab(
        val ex: Int, val ey: Int, val ew: Int, val eh: Int,
        val selected: Boolean, val hasNew: Boolean, val labelColor: Int, val filter: String, val filterColor: Int,
    )

    private var shown = true
    private var enabled = false
    private var tabs: List<Tab> = emptyList()
    private var rowX = 0f; private var rowY = 0f; private var tabW = 0f; private var rowH = 0f
    private var effSel = -1
    private var activeTab = -1       // last known selection — keeps the pill named while collapsed
    private var optimisticTab = -1   // hold a just-switched tab selected until the snapshot agrees (no timeout)

    // Gesture state. The view owns the stream from a DOWN on the pill; chips are display-only.
    private var tracking = false
    private var downX = 0f; private var downY = 0f
    private var dragMoved = false
    private var fanned = false
    private var fanFrac = 0f          // 0 = pill, 1 = fanned row (animated)
    private var fanAnim: ValueAnimator? = null
    private var hoverChip = -1
    private var filterChip = -1       // >= 0 while the pull-down filter menu is live (host owns the menu)
    private var hintChip = -1; private var hintReveal = 0f
    private val holdRunnable = Runnable { if (tracking && !fanned) fanOut() }

    private val dp = resources.displayMetrics.density
    private var btnNormal: Bitmap? = null
    private var btnSelected: Bitmap? = null
    private var btnNew: Bitmap? = null

    private val bgPaint = Paint().apply { isAntiAlias = true; isFilterBitmap = true; isDither = true }
    private val namePaint = Paint().apply { isAntiAlias = true; textAlign = Paint.Align.CENTER }
    private val statePaint = Paint().apply { isAntiAlias = true; textAlign = Paint.Align.CENTER }
    private val hintPaint = Paint().apply {
        isAntiAlias = true; textAlign = Paint.Align.CENTER; color = HINT_COLOR
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val borderPaint = Paint().apply {
        isAntiAlias = true; style = Paint.Style.STROKE; color = 0xE6FFFFFF.toInt()
    }
    private val srcRect = Rect(); private val dstRect = RectF()

    init {
        btnNormal = loadAsset("tabassets/chatbtn.png")
        btnSelected = loadAsset("tabassets/chatbtn-selected.png")
        btnNew = loadAsset("tabassets/chatbtn-new.png")
        val rsFont = try { Typeface.createFromAsset(context.assets, "tabassets/runescape.ttf") } catch (e: Exception) { null }
        rsFont?.let { namePaint.typeface = it; statePaint.typeface = it }
        Log.i("ChatPill", "p4: pill assets built (sprites ${ok(btnNormal)}/${ok(btnSelected)}/${ok(btnNew)} font ${if (rsFont != null) "ok" else "sys"})")
    }

    private fun ok(b: Bitmap?) = if (b != null) "ok" else "X"
    private fun loadAsset(path: String): Bitmap? = try {
        context.assets.open(path).use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) { null }

    fun setShown(on: Boolean) { if (shown != on) { shown = on; invalidate() } }

    /** Feed the latest chat snapshot + canvas→view scale each frame. Returns the tab count parsed. */
    fun update(json: String, sx: Float, sy: Float, on: Boolean): Int {
        val want = on && shown
        if (!want) {
            if (enabled || tabs.isNotEmpty()) { reset(); enabled = false; tabs = emptyList(); invalidate() }
            return 0
        }
        val o = try { JSONObject(json) } catch (e: Exception) { null }
        if (o == null || !o.optBoolean("loggedIn")) {
            if (tabs.isNotEmpty()) { reset(); tabs = emptyList(); invalidate() }
            enabled = true; return 0
        }
        val arr = o.optJSONArray("tabs") ?: run { reset(); tabs = emptyList(); invalidate(); enabled = true; return 0 }
        rowX = o.optInt("chatX", 0) * sx
        rowY = o.optInt("chatY", 0) * sy
        tabW = (CHAT_W * sx) / TAB_COUNT
        rowH = ROW_H_CANVAS * sy
        val list = ArrayList<Tab>(TAB_COUNT)
        var snapSel = -1
        val n = minOf(TAB_COUNT, arr.length())
        for (i in 0 until n) {
            val t = arr.optJSONObject(i) ?: continue
            val sel = t.optBoolean("selected")
            if (sel && snapSel < 0) snapSel = i
            list.add(Tab(
                t.optInt("x", -1), t.optInt("y", -1), t.optInt("w", 0), t.optInt("h", 0),
                sel, t.optBoolean("new"), t.optInt("labelColor", -1), t.optString("filter", ""), t.optInt("filterColor", -1)))
        }
        effSel = snapSel
        if (optimisticTab >= 0) {
            if (snapSel == optimisticTab) optimisticTab = -1 else effSel = optimisticTab
        }
        if (effSel >= 0) activeTab = effSel
        tabs = list
        enabled = true
        invalidate()
        return list.size
    }

    /** The engine chat-button centre (canvas px) for channel i, or null while it isn't built/on-canvas. */
    fun engineButtonCenter(i: Int): Pair<Int, Int>? {
        val t = tabs.getOrNull(i) ?: return null
        if (t.ew <= 0 || t.eh <= 0 || t.ex < 0 || t.ey < 0) return null
        return (t.ex + t.ew / 2) to (t.ey + t.eh / 2)
    }

    // --- geometry -------------------------------------------------------------------------------------

    private fun chipRect(i: Int): RectF =
        RectF(rowX + i * tabW, rowY, rowX + (i + 1) * tabW, rowY + rowH)

    /** Which fan column the finger is over (generous vertical band incl. the pull-down zone), or -1. */
    private fun chipColumnAt(x: Float, y: Float): Int {
        if (x < rowX || x >= rowX + tabW * TAB_COUNT) return -1
        val top = rowY - 44f * dp
        val bot = rowY + rowH + (PULL_DOWN_DP + 60f) * dp
        if (y < top || y > bot) return -1
        return ((x - rowX) / tabW).toInt().coerceIn(0, TAB_COUNT - 1)
    }

    // --- fan animation --------------------------------------------------------------------------------

    private fun fanOut() {
        fanned = true
        hoverChip = -1; filterChip = -1; hintChip = -1
        onHaptic?.invoke()
        fanAnim?.cancel()
        fanAnim = ValueAnimator.ofFloat(fanFrac, 1f).apply {
            duration = FAN_ANIM_MS
            interpolator = OvershootInterpolator(1.1f)
            addUpdateListener { fanFrac = it.animatedValue as Float; invalidate() }
            start()
        }
        Log.i("ChatPill", "p4: pill hold -> fan out")
    }

    private fun fanIn() {
        fanned = false
        hoverChip = -1; filterChip = -1; hintChip = -1; hintReveal = 0f
        fanAnim?.cancel()
        fanAnim = ValueAnimator.ofFloat(fanFrac, 0f).apply {
            duration = FAN_ANIM_MS
            interpolator = AccelerateInterpolator()
            addUpdateListener { fanFrac = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun reset() {
        removeCallbacks(holdRunnable)
        tracking = false; fanned = false; fanFrac = 0f
        hoverChip = -1; filterChip = -1; hintChip = -1; hintReveal = 0f
        fanAnim?.cancel(); fanAnim = null
    }

    // --- drawing --------------------------------------------------------------------------------------

    private fun colorOf(rgb: Int, default: Int) = if (rgb < 0) default else (0xFF000000.toInt() or (rgb and 0xFFFFFF))

    private fun drawChip(canvas: Canvas, r: RectF, i: Int, selected: Boolean, scale: Float, alpha: Int) {
        val t = tabs.getOrNull(i)
        val bg = when { selected -> btnSelected; t?.hasNew == true -> btnNew; else -> btnNormal }
        val cx = r.centerX(); val cy = r.centerY()
        val hw = r.width() / 2f * scale; val hh = r.height() / 2f * scale
        dstRect.set(cx - hw, cy - hh, cx + hw, cy + hh)
        bgPaint.alpha = alpha
        if (bg != null) {
            srcRect.set(0, 0, bg.width, bg.height)
            canvas.drawBitmap(bg, srcRect, dstRect, bgPaint)
        }
        namePaint.alpha = alpha; statePaint.alpha = alpha
        val nameSize = dstRect.height() * 0.40f
        val stateSize = dstRect.height() * 0.34f
        namePaint.textSize = nameSize
        namePaint.color = colorOf(t?.labelColor ?: -1, NAME_DEFAULT).withAlpha(alpha)
        val filter = t?.filter ?: ""
        if (filter.isNotEmpty()) {
            canvas.drawText(NAMES[i], cx, dstRect.top + dstRect.height() * 0.44f, namePaint)
            statePaint.textSize = stateSize
            statePaint.color = colorOf(t?.filterColor ?: -1, FILTER_DEFAULT).withAlpha(alpha)
            canvas.drawText(filter, cx, dstRect.top + dstRect.height() * 0.88f, statePaint)
        } else {
            canvas.drawText(NAMES[i], cx, dstRect.top + dstRect.height() * 0.64f, namePaint)
        }
        if (scale > 1f) {
            borderPaint.strokeWidth = 2f * dp
            borderPaint.alpha = alpha
            canvas.drawRoundRect(dstRect, 6f * dp, 6f * dp, borderPaint)
        }
    }

    private fun Int.withAlpha(a: Int): Int = (this and 0x00FFFFFF) or (a shl 24)

    override fun onDraw(canvas: Canvas) {
        if (!enabled || !shown || tabs.isEmpty() || tabW <= 0f) return
        if (fanFrac <= 0f) {
            // Collapsed pill: one tab wide at the chat origin, always painted selected with the open channel.
            val sel = if (activeTab >= 0) activeTab else 0
            drawChip(canvas, RectF(rowX, rowY, rowX + tabW, rowY + rowH), sel, selected = true, scale = 1f, alpha = 255)
            return
        }
        // Fanning / fanned: chips interpolate from the pill frame out to their row positions.
        val pill = RectF(rowX, rowY, rowX + tabW, rowY + rowH)
        val alpha = (fanFrac * 255f).toInt().coerceIn(0, 255)
        for (i in 0 until minOf(TAB_COUNT, tabs.size)) {
            if (i == hoverChip) continue   // draw the hovered chip last (on top)
            val c = chipRect(i)
            val r = RectF(
                pill.left + (c.left - pill.left) * fanFrac, c.top,
                pill.left + (c.right - pill.left) * fanFrac, c.bottom)
            drawChip(canvas, r, i, selected = i == effSel, scale = 1f, alpha = alpha)
        }
        if (hoverChip in 0 until minOf(TAB_COUNT, tabs.size)) {
            drawChip(canvas, chipRect(hoverChip), hoverChip, selected = hoverChip == effSel, scale = 1.06f, alpha = 255)
        }
        // ▾ pull-down hint under the hovered filterable chip, revealed by the finger's dip.
        if (hintChip >= 0 && hintReveal > 0.01f && filterChip < 0) {
            val c = chipRect(hintChip)
            hintPaint.textSize = 16f * dp
            hintPaint.alpha = (hintReveal * 255f).toInt().coerceIn(0, 255)
            canvas.drawText("▾", c.centerX(), c.bottom + 16f * dp, hintPaint)
        }
    }

    // --- gesture --------------------------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!enabled || !shown || tabs.isEmpty() || tabW <= 0f) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Only a DOWN on the resting pill starts the gesture; everything else falls through to the game.
                if (fanned || fanFrac > 0f) return false
                val pill = RectF(rowX, rowY, rowX + tabW, rowY + rowH)
                if (!pill.contains(event.x, event.y)) return false
                tracking = true; dragMoved = false
                downX = event.x; downY = event.y
                postDelayed(holdRunnable, HOLD_DELAY_MS)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                if (!dragMoved && hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()) > DRAG_MOVED_DP * dp) {
                    dragMoved = true
                }
                if (!fanned) return true   // still inside the hold window (or a pre-fan drift)
                if (fanAnim?.isRunning == true && fanFrac < 0.7f) return true   // let the fan mostly settle
                if (filterChip >= 0) {
                    // FILTER mode: back UP onto the chip row cancels; otherwise the finger drives the menu rows.
                    if (event.y < chipRect(filterChip).bottom) {
                        onFilterEnd?.invoke(true)
                        filterChip = -1
                        Log.i("ChatPill", "p4: pull-up -> filter cancelled, back to select")
                        invalidate()
                    } else {
                        onFilterMove?.invoke(event.x, event.y)
                    }
                    return true
                }
                val k = chipColumnAt(event.x, event.y)
                if (k != hoverChip) {
                    hoverChip = k
                    if (k >= 0) onHaptic?.invoke()
                    invalidate()
                }
                // ▾ hint reveal for a filterable chip as the finger dips toward its bottom edge.
                if (k >= 0 && FILTERABLE[k]) {
                    val c = chipRect(k)
                    hintChip = k
                    hintReveal = ((event.y - (c.centerY() + HINT_START_DP * dp)) / (PULL_DOWN_DP * dp)).coerceIn(0f, 1f)
                } else {
                    hintChip = -1; hintReveal = 0f
                }
                invalidate()
                // Pull-down escalation: crossed the threshold below a filterable chip.
                if (k >= 0 && FILTERABLE[k] && event.y > chipRect(k).bottom + PULL_DOWN_DP * dp) {
                    val centre = engineButtonCenter(k)
                    val opened = onFilterOpen?.invoke(
                        k, centre?.first ?: -1, centre?.second ?: -1, event.x, event.y) == true
                    if (opened) {
                        filterChip = k
                        hintChip = -1; hintReveal = 0f
                        onHaptic?.invoke()
                        Log.i("ChatPill", "p4: pull-down $k (${NAMES[k]}) -> filter menu")
                        invalidate()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!tracking) return false
                removeCallbacks(holdRunnable)
                if (filterChip >= 0) {
                    onFilterEnd?.invoke(false)   // release on a row commits the filter choice
                    filterChip = -1
                } else if (fanned) {
                    // Switch to the HIGHLIGHTED chip (what you see is what you get) — needs a real drag, so a
                    // hold-and-release peek doesn't change channels. Fast flick: hover may not be tracked yet.
                    var k = hoverChip
                    if (k < 0) k = chipColumnAt(event.x, event.y)
                    if (dragMoved && k >= 0 && k != activeTab) {
                        optimisticTab = k
                        activeTab = k
                        onSwitchChannel?.invoke(k)
                        Log.i("ChatPill", "p4: switch -> $k (${NAMES[k]})")
                    }
                } else {
                    onPillTap?.invoke()   // quick tap: toggle the chat collapse
                }
                if (fanned || fanFrac > 0f) fanIn()
                tracking = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!tracking) return false
                removeCallbacks(holdRunnable)
                if (filterChip >= 0) { onFilterEnd?.invoke(true); filterChip = -1 }
                if (fanned || fanFrac > 0f) fanIn()
                tracking = false
                return true
            }
        }
        return false
    }
}
