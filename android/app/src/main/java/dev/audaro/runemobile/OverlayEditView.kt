package dev.audaro.runemobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot
import kotlin.math.roundToInt

// A grabbed handle released within this long (and under touchSlop) counts as a tap, not a drag-move.
private const val TAP_MAX_MS = 250L

// Strip a trailing "Overlay" and space out the camel-cased class name for a readable box label.
private fun prettyOverlayName(n: String): String {
    val base = n.removeSuffix("Overlay").ifEmpty { n }
    return base.replace(Regex("([a-z])([A-Z])"), "$1 $2").trim().ifEmpty { n }
}

/**
 * On-screen overlay-layout editor: draws each movable RuneLite overlay as a translucent, labelled box at its live
 * game-canvas bounds (scaled to view px) directly over the running game, and lets the user DRAG it to a new spot —
 * the mobile analogue of the desktop "overlay management" mode. The box being dragged follows the finger; on
 * release its new top-left (canvas px) is committed via {@code onDrop} (the host writes it through
 * OverlayBridge.setOverlayPreferredLocation). A floating toolbar offers Done + Reset-all. Non-dragged boxes track
 * the host-fed live bounds ({@link #setBoxes}); the dragged one is left alone until release.
 */
class OverlayEditView(
    context: Context,
) : View(context) {
    data class Box(
        val name: String,
        val cx: Int,
        val cy: Int,
        val cw: Int,
        val ch: Int,
    )

    /** New top-left in CANVAS px for [name] (clamped on-canvas). Host commits it on the bridge thread. */
    var onDrop: ((name: String, canvasX: Int, canvasY: Int) -> Unit)? = null
    var onDone: (() -> Unit)? = null
    var onResetAll: (() -> Unit)? = null

    /** A tap (no real drag) on [name]'s handle: host shows the snap-to-corner / reset menu. */
    var onHandleMenu: ((name: String) -> Unit)? = null

    /** Live drag of the synthetic "Chat" handle → new chatbox top-left in CANVAS px (host relocates the engine chat). */
    var onChatMove: ((canvasX: Int, canvasY: Int) -> Unit)? = null

    /** Chat handle released → host persists the override. */
    var onChatCommit: (() -> Unit)? = null

    /** Live drag of the synthetic "Minimap" handle -> new cluster top-left in CANVAS px (host relocates it). */
    var onMinimapMove: ((canvasX: Int, canvasY: Int) -> Unit)? = null

    /** Minimap handle released after a drag -> host persists the override. A tap goes to [onHandleMenu]. */
    var onMinimapCommit: (() -> Unit)? = null

    private var boxes: List<Box> = emptyList()

    // Synthetic draggable chatbox handle, present only while chat-at-top relocation is active (host feeds it). It
    // is dragged like the overlay boxes but moves the live engine chat instead of an overlay-preferred-location.
    private var chatBox: Box? = null

    // Synthetic minimap+orbs cluster handle, present only while the cluster is relocated (host feeds it). Moves
    // the live cluster the same way the chat handle moves the chat.
    private var minimapBox: Box? = null

    // Drag state. dragW/dragH (view px) drive the on-screen box; dragCw/dragCh are the box's ORIGINAL canvas-px
    // size, cached on grab so the commit clamp uses the exact integer footprint (no view-px round-trip drift).
    private var dragName: String? = null
    private var dragW = 0f
    private var dragH = 0f
    private var dragCw = 0
    private var dragCh = 0
    private var dragOrigCx = 0 // the grabbed box's canvas top-left, to undo a tap's sub-slop live move
    private var dragOrigCy = 0
    private var grabDX = 0f
    private var grabDY = 0f
    private var dragLeft = 0f
    private var dragTop = 0f

    // Tap-vs-drag discrimination on a grabbed handle: a release under touchSlop of the down point (and quick)
    // opens the snap/reset menu instead of committing a move.
    private var downX = 0f
    private var downY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val dp = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFD54F } // translucent amber
    private val fillActive = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFD54F }
    private val border =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * dp
            color = 0xFFFFD54F.toInt()
        }
    private val label =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 11f * dp
            typeface = Typeface.DEFAULT_BOLD
        }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAA000000.toInt() }
    private val btnBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xEE2A2A2A.toInt() }
    private val btnTxt =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 14f * dp
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
    private val hintTxt =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCCFFFFFF.toInt()
            textSize = 12f * dp
            textAlign =
                Paint.Align.CENTER
        }
    private val rrf = RectF()

    // Toolbar button rects (view px), computed in onDraw, hit-tested in onTouch.
    private val doneRect = RectF()
    private val resetRect = RectF()

    private fun sx() = if (MainActivity.canvasW > 0) width.toFloat() / MainActivity.canvasW else 1f

    private fun sy() = if (MainActivity.canvasH > 0) height.toFloat() / MainActivity.canvasH else 1f

    /** Feed live overlay bounds (host poll). The box currently being dragged is preserved. */
    fun setBoxes(list: List<Box>) {
        boxes = list
        invalidate()
    }

    /** Feed the live chatbox bounds (canvas px) while chat-at-top is active, or null to remove the handle. The
     *  box being dragged is preserved so the live relocate isn't fought by the poll. */
    fun setChatBox(box: Box?) {
        if (dragName == CHAT_HANDLE) return
        chatBox = box
        invalidate()
    }

    /** Feed the live minimap cluster bounds (canvas px) while it's relocated, or null to remove the handle. The box
     *  being dragged is preserved so the live relocate isn't fought by the poll. */
    fun setMinimapBox(box: Box?) {
        if (dragName == MINIMAP_HANDLE) return
        minimapBox = box
        invalidate()
    }

    /** boxes + the synthetic minimap and chat handles (when present) — the full set drawn and hit-tested. */
    private fun allBoxes(): List<Box> = boxes + listOfNotNull(minimapBox, chatBox)

    /** Names of the overlays currently shown (for Reset-all). */
    fun currentNames(): List<String> = boxes.map { it.name }

    private fun layoutToolbar() {
        val w = 96f * dp
        val h = 40f * dp
        val m = 10f * dp
        val top = m
        doneRect.set(width - m - w, top, width - m, top + h)
        resetRect.set(width - m - w - m - w, top, width - m - m - w, top + h)
    }

    override fun onDraw(canvas: Canvas) {
        if (visibility != VISIBLE) return
        val sx = sx()
        val sy = sy()
        // Movable-overlay boxes + the chat handle (the dragged one is drawn from its live drag rect, others fed).
        for (b in allBoxes()) {
            val dragging = b.name == dragName
            val l: Float
            val t: Float
            val r: Float
            val bot: Float
            if (dragging) {
                l = dragLeft
                t = dragTop
                r = dragLeft + dragW
                bot = dragTop + dragH
            } else {
                l = b.cx * sx
                t = b.cy * sy
                r = (b.cx + b.cw) * sx
                bot = (b.cy + b.ch) * sy
            }
            rrf.set(l, t, r, bot)
            canvas.drawRect(rrf, if (dragging) fillActive else fill)
            canvas.drawRect(rrf, border)
            val name =
                when (b.name) {
                    CHAT_HANDLE -> "Chat"
                    MINIMAP_HANDLE -> "Minimap"
                    else -> prettyOverlayName(b.name)
                }
            val tw = label.measureText(name)
            val lx = l + 3f * dp
            val ly = t + 13f * dp
            canvas.drawRect(lx - 2f * dp, t + 1f * dp, lx + tw + 2f * dp, t + 17f * dp, labelBg)
            canvas.drawText(name, lx, ly, label)
        }
        // Floating toolbar (Done / Reset all) + a one-line hint, drawn last so they sit above the boxes.
        layoutToolbar()
        drawButton(canvas, doneRect, "Done")
        drawButton(canvas, resetRect, "Reset all")
        canvas.drawText("Drag to move · tap for snap / reset", width / 2f, 26f * dp, hintTxt)
    }

    private fun drawButton(
        canvas: Canvas,
        r: RectF,
        text: String,
    ) {
        canvas.drawRoundRect(r, 10f * dp, 10f * dp, btnBg)
        canvas.drawText(text, r.centerX(), r.centerY() + 5f * dp, btnTxt)
    }

    private fun boxAt(
        x: Float,
        y: Float,
    ): Box? {
        val sx = sx()
        val sy = sy()
        // Topmost (last-drawn) box under the point wins; the chat handle is drawn last so it wins ties.
        val all = allBoxes()
        for (i in all.indices.reversed()) {
            val b = all[i]
            val l = b.cx * sx
            val t = b.cy * sy
            val r = (b.cx + b.cw) * sx
            val bot = (b.cy + b.ch) * sy
            if (x >= l && x <= r && y >= t && y <= bot) return b
        }
        return null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (visibility != VISIBLE) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (doneRect.contains(event.x, event.y)) {
                    onDone?.invoke()
                    return true
                }
                if (resetRect.contains(event.x, event.y)) {
                    onResetAll?.invoke()
                    return true
                }
                val b = boxAt(event.x, event.y) ?: return true // consume taps in edit mode (no game interaction)
                val sx = sx()
                val sy = sy()
                dragName = b.name
                dragCw = b.cw
                dragCh = b.ch
                dragOrigCx = b.cx
                dragOrigCy = b.cy
                dragW = b.cw * sx
                dragH = b.ch * sy
                dragLeft = b.cx * sx
                dragTop = b.cy * sy
                grabDX = event.x - dragLeft
                grabDY = event.y - dragTop
                downX = event.x
                downY = event.y
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragName == null) return true
                dragLeft = (event.x - grabDX).coerceIn(0f, (width - dragW).coerceAtLeast(0f))
                dragTop = (event.y - grabDY).coerceIn(0f, (height - dragH).coerceAtLeast(0f))
                if (dragName == CHAT_HANDLE) {
                    // Relocate the live engine chat as the finger moves (iOS overlayEditMoveChatToCanvasX parity).
                    onChatMove?.invoke(canvasXOf(dragLeft), canvasYOf(dragTop))
                } else if (dragName == MINIMAP_HANDLE) {
                    onMinimapMove?.invoke(canvasXOf(dragLeft), canvasYOf(dragTop))
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val name = dragName
                if (name != null && event.actionMasked == MotionEvent.ACTION_UP) {
                    val moved = hypot(event.x - downX, event.y - downY)
                    val quick = event.eventTime - event.downTime <= TAP_MAX_MS
                    release(name, tap = moved <= touchSlop && quick)
                }
                dragName = null
                invalidate()
                return true
            }
        }
        return false
    }

    /** A grabbed [name] was let go: commit where it was dragged, or for a [tap] open its menu. */
    private fun release(
        name: String,
        tap: Boolean,
    ) {
        when {
            name == CHAT_HANDLE -> {
                // The chat already tracked the finger via onChatMove; just persist the final spot. A pure
                // tap leaves the offset unchanged, so committing is harmless (no snap/reset menu here).
                onChatMove?.invoke(canvasXOf(dragLeft), canvasYOf(dragTop))
                onChatCommit?.invoke()
            }

            name == MINIMAP_HANDLE && tap -> {
                // Undo any sub-slop jitter the live move applied, then offer the reset.
                onMinimapMove?.invoke(dragOrigCx, dragOrigCy)
                onHandleMenu?.invoke(name)
            }

            name == MINIMAP_HANDLE -> {
                onMinimapMove?.invoke(canvasXOf(dragLeft), canvasYOf(dragTop))
                onMinimapCommit?.invoke()
            }

            tap -> {
                // A tap, not a drag: open the snap/reset menu (parity with iOS's per-handle action sheet).
                onHandleMenu?.invoke(name)
            }

            else -> {
                onDrop?.invoke(name, canvasXOf(dragLeft), canvasYOf(dragTop))
            }
        }
    }

    // View-px drag top-left → clamped canvas-px top-left, using the box's original canvas footprint.
    private fun canvasXOf(left: Float): Int = (left / sx()).roundToInt().coerceIn(0, (MainActivity.canvasW - dragCw).coerceAtLeast(0))

    private fun canvasYOf(top: Float): Int = (top / sy()).roundToInt().coerceIn(0, (MainActivity.canvasH - dragCh).coerceAtLeast(0))

    companion object {
        // Sentinel overlay name for the synthetic chat handle (mirrors iOS's kChatHandleKey).
        const val CHAT_HANDLE = "__chat__"

        // Sentinel overlay name for the synthetic minimap handle (mirrors iOS's kMinimapHandleKey).
        const val MINIMAP_HANDLE = "__minimap__"
    }
}
