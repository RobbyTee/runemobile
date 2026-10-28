package dev.audaro.runemobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View

/**
 * A6 native Jagex-Mobile chrome: the official app's tab group and the column beside the chat, drawn over the live
 * SurfaceView with the OSRS sprites from the live cache, at the official layout's sizes (interface 601).
 *
 * The tab group (SIDE_RIGHT_TABS) is a framed 2-column x 7-row grid at the right, flush with the safe edge. Its 7th
 * inner stone collapses it to the inner column, the outer one sliding under it. The engine's own 14 tab stones are
 * HIDDEN (HudVeneerBridge.setStonesHidden) so these native cells ARE the tabs; a tap opens the tab by replaying the
 * engine stone's op script (HudVeneerBridge.openTab). No widget is moved or covered; the engine and every plugin
 * stay authoritative. The column (SIDE_LEFT_CHAT) sits left of the chat and moves with it: the logout door, the
 * speech bubbles that put the chat away and bring it back, and the keyboard. This mirrors the iOS layout
 * (ViewController.updateChrome / TAB_GRID_ORDER).
 *
 * Every size is the official one in game-canvas px, drawn at the host's canvas scale ([setCanvasScale]); nothing
 * draws until that is known. The host drives the active tab, the enable state and the column from the bridge
 * snapshots each frame; taps come back through [onTabTap], [onCollapseToggle], [onChatToggle] and [onKeyboard].
 */
class ChromeOverlayView(
    context: Context,
) : View(context) {
    /** Host callback: the user tapped the tab whose engine stone index is [engineIndex] — replay its op script. */
    var onTabTap: ((engineIndex: Int) -> Unit)? = null

    /** Host callback: the expand stone collapsed ([collapsed] = true) or expanded the grid; the host keeps it. */
    var onCollapseToggle: ((collapsed: Boolean) -> Unit)? = null

    /** Host callback: the speech bubbles were tapped (put the chat away, or bring it back). */
    var onChatToggle: (() -> Unit)? = null

    /** Host callback: the keyboard stone was tapped. */
    var onKeyboard: (() -> Unit)? = null

    private var active = -1 // engine tab index of the open tab (red stone), -1 = none
    private var gridEnabled = false // true only in Resizable - Modern (PRE_EOC, toplevel 164), logged in
    private var shown = true // A/B debug toggle (▦ native grid vs ▤ raw engine stones)

    // Composited images at the sprites' native px: [i] each tab glyph on the grey stone, and on the red one over it.
    private val tabInactive = arrayOfNulls<Bitmap>(14)
    private val tabActive = arrayOfNulls<Bitmap>(14)
    private var showLessImg: Bitmap? = null // the expand stone while both columns show
    private var showMoreImg: Bitmap? = null // and while collapsed
    private var doorImg: Bitmap? = null
    private var doorActiveImg: Bitmap? = null
    private var toggleOpenImg: Bitmap? = null // the bubbles on the lighter stone while the chat shows
    private var toggleClosedImg: Bitmap? = null // on the door's stone while it is put away
    private var toggleFlashImg: Bitmap? = null // and on its red one, flashing, while a message waits
    private var keyboardImg: Bitmap? = null
    private var frameImg: Bitmap? = null // the group's frame, both columns
    private var frameCollapsedImg: Bitmap? = null // and the inner one only

    // Collapsed: the group narrows to the inner column and the outer one slides under it (601's expand stone).
    private var collapsed = false

    // The column's top-left in view px (the chat origin less its width; the host owns the chat's position), or
    // NaN while the chat isn't placed. With the chat put away the keyboard goes; the bubbles flash on unread.
    private var columnX = Float.NaN
    private var columnY = Float.NaN
    private var columnScale = 1f // the chat's own scale, which the column keeps (the large portrait UI shows it smaller)
    private var chatPutAway = false
    private var chatUnread = false

    // Canvas px per view px, both ways; 0 = not known yet, and nothing draws.
    private var kx = 0f
    private var ky = 0f

    // Last computed group geometry (view px), so onTouchEvent + the host's side-panel relocation read the same one.
    private var lay = Layout(0f, 0f, 0f, 0f)

    private class Layout(
        val gx0: Float,
        val gy0: Float,
        val w: Float,
        val h: Float,
    )

    // Pixel art, scaled nearest-neighbour as the engine scales it.
    private val bmpPaint = Paint()
    private val fallbackPaint =
        Paint().apply {
            color = Color.argb(235, 60, 50, 38)
            isAntiAlias = true
        }
    private val fallbackActivePaint =
        Paint().apply {
            color = Color.argb(245, 150, 60, 50)
            isAntiAlias = true
        }
    private val srcRect = Rect()
    private val dstRect = RectF()

    private fun dp(v: Float) = v * resources.displayMetrics.density

    // Tab images are built from runtime cache sprites (setSprites), not bundled assets — so nothing Jagex ships in
    // the repo. Until the first set arrives (one-shot, after login) onDraw uses the stone-toned fallback cells.

    /** TAB_GRID_ORDER[slot] = engine tab (stone) index, row-major (slot 0 = top-left, 1 = top-right, …), in the
     *  official app's order (interface 601): the inner column, next to the panel, is inventory, equipment, prayer,
     *  magic, combat, quests and the expand stone; the outer one is skills, emotes, music, clan, friends, account
     *  and settings. Logout (10) is the door by the chat. Identical to the iOS shell's TAB_GRID_ORDER. */
    private val order = intArrayOf(3, 1, 4, 12, 5, 13, 6, 7, 0, 9, 2, 8, EXPAND, 11)

    /** Build every image from a runtime sprite set (the real OSRS cache sprites the engine loaded after login). Each
     *  icon sits where the official layout puts it: the tab glyphs and arrows in a 33x36 box 3 px in (the arrows a
     *  pixel higher), the door and keyboard in one 12 px in, the bubbles in a 32x32 one at (13,4). Idempotent;
     *  re-applies if a fresher set arrives. */
    fun setSprites(set: TabSprites.Set) {
        val grey = set.stoneInactive
        val red = set.stoneActive
        if (!set.usableForGrid() || grey == null || red == null) return
        for (i in 0 until 14) {
            val icon = set.icons.getOrNull(i)
            tabInactive[i] = composite(listOf(grey), icon, TAB_BOX)
            tabActive[i] = composite(listOf(grey, red), icon, TAB_BOX)
        }
        set.showLess?.let { showLessImg = composite(listOf(grey), it, ARROW_BOX) }
        set.showMore?.let { showMoreImg = composite(listOf(grey), it, ARROW_BOX) }
        setColumnSprites(set)
        set.frame?.let {
            frameImg = nineSlice(it, GROUP_W, GROUP_H)
            frameCollapsedImg = nineSlice(it, GROUP_W_COLLAPSED, GROUP_H)
        }
        Log.i("ChromeOverlay", "a6: tab assets built from runtime cache sprites")
        invalidate()
    }

    /** The column's images: the door both ways, the bubbles on each of their three stones, and the keyboard. */
    private fun setColumnSprites(set: TabSprites.Set) {
        val door = set.icons.getOrNull(LOGOUT)
        val doorStone = set.doorStone
        val doorStoneActive = set.doorStoneActive
        val chatStone = set.chatStoneOpen
        if (door != null && doorStone != null && doorStoneActive != null) {
            doorImg = composite(listOf(doorStone), door, DOOR_BOX)
            doorActiveImg = composite(listOf(doorStoneActive), door, DOOR_BOX)
            set.chatToggle?.let {
                toggleClosedImg = composite(listOf(doorStone), it, BUBBLES_BOX)
                toggleFlashImg = composite(listOf(doorStoneActive), it, BUBBLES_BOX)
                if (chatStone != null) toggleOpenImg = composite(listOf(chatStone), it, BUBBLES_BOX)
            }
        }
        if (chatStone != null) set.keyboard?.let { keyboardImg = composite(listOf(chatStone), it, KEYBOARD_BOX) }
    }

    /** Composite [stones] (each at the origin) and [icon] at native px, the icon the way the engine draws a sprite
     *  in a widget: at its offset inside [box] (x, y, w, h), scaled when the box isn't the sprite's own size, like
     *  the iOS compositeStones:icon:box:. */
    private fun composite(
        stones: List<Bitmap>,
        icon: TabSprites.Sprite?,
        box: IntArray,
    ): Bitmap {
        val base = stones[0]
        val out = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        for (stone in stones) c.drawBitmap(stone, 0f, 0f, null)
        if (icon != null) {
            val sx = if (icon.bw > 0) box[2].toFloat() / icon.bw else 1f
            val sy = if (icon.bh > 0) box[3].toFloat() / icon.bh else 1f
            val left = box[0] + icon.ox * sx
            val top = box[1] + icon.oy * sy
            srcRect.set(0, 0, icon.bmp.width, icon.bmp.height)
            dstRect.set(left, top, left + icon.bmp.width * sx, top + icon.bmp.height * sy)
            c.drawBitmap(icon.bmp, srcRect, dstRect, null)
        }
        return out
    }

    /** The group's frame at [w] x [h] canvas px from its nine [pieces] (row by row): corners at their own size,
     *  edges and middle stretched between them, as the engine lays SIDE_RIGHT_TABS_BACKGROUND. */
    private fun nineSlice(
        pieces: List<Bitmap>,
        w: Int,
        h: Int,
    ): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val xs = intArrayOf(0, pieces[0].width, w - pieces[8].width, w)
        val ys = intArrayOf(0, pieces[0].height, h - pieces[8].height, h)
        val dst = Rect()
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                val p = pieces[row * 3 + col]
                srcRect.set(0, 0, p.width, p.height)
                dst.set(xs[col], ys[row], xs[col + 1], ys[row + 1])
                c.drawBitmap(p, srcRect, dst, null)
            }
        }
        return out
    }

    /** Host: the engine's currently-open tab (stone index), for the red/grey highlight. -1 = none. */
    fun setActiveTab(activeTab: Int) {
        if (active != activeTab) {
            active = activeTab
            invalidate()
        }
    }

    /** Host: enable the native grid only in the supported layout (logged in + Resizable - Modern, PRE_EOC 164).
     *  When disabled the grid draws nothing and consumes no touches (the engine stones are shown instead). */
    fun setGridEnabled(on: Boolean) {
        if (gridEnabled != on) {
            gridEnabled = on
            invalidate()
        }
    }

    fun setShown(on: Boolean) {
        if (shown != on) {
            shown = on
            invalidate()
        }
    }

    val railsShown: Boolean get() = shown
    val isGridActive: Boolean get() = shown && gridEnabled

    // The group's left/top edge and height in view px (for the host's side-panel relocation). Valid after
    // setCanvasScale.
    val gridLeftPx: Float get() = lay.gx0
    val gridTopPx: Float get() = lay.gy0
    val gridHeightPx: Float get() = lay.h

    /** Host: restore the persisted collapse state (the expand stone itself toggles it and reports [onCollapseToggle]). */
    fun setCollapsed(on: Boolean) {
        if (collapsed != on) {
            collapsed = on
            computeLayout()
            invalidate()
        }
    }

    /** Host: the column's top-left in view px, beside the chat; NaN hides it. [putAway] = the chat is put away (the
     *  keyboard goes, the bubbles sit on the door's stone); [unread] = a message waits (they flash). [scale] = how much
     *  smaller than canvas px the chat shows, which the column follows. */
    fun setChatColumn(
        x: Float,
        y: Float,
        putAway: Boolean,
        unread: Boolean,
        scale: Float = 1f,
    ) {
        if (x.isNaN() && columnX.isNaN()) return
        val moved = x != columnX || y != columnY || scale != columnScale
        val restated = putAway != chatPutAway || unread != chatUnread
        if (moved || restated) {
            columnX = x
            columnY = y
            columnScale = scale
            chatPutAway = putAway
            chatUnread = unread
            invalidate()
        }
    }

    /** Host: canvas px per view px, both ways, so every stone is the official size (see computeLayout). */
    fun setCanvasScale(
        canvasPerViewPxX: Float,
        canvasPerViewPxY: Float,
    ) {
        if (kx != canvasPerViewPxX || ky != canvasPerViewPxY) {
            kx = canvasPerViewPxX
            ky = canvasPerViewPxY
            computeLayout()
            invalidate()
        }
    }

    /** [top, right, bottom] safe-area insets (display cutout + system bars), so the grid clears a landscape
     *  notch / nav bar. Defaults to 0 (the full-bleed emulator case). */
    private fun safeInsets(): IntArray = safeInsetsIn(this).let { intArrayOf(it[1], it[2], it[3]) }

    /** Recompute + cache the group's geometry from the current view size + insets (matches iOS updateChrome and
     *  tabGridTopForHeight): 85x280 canvas px (58 wide collapsed), flush with the safe right edge. Vertically it takes
     *  the official app's anchor (interface 601, script 919): once the safe area is at least 503 game px tall the
     *  group (and the panel, which the host centres on it) is centred 87 game px below its middle, and below that it
     *  sits 4 px off its bottom; never past the bottom margin. In portrait the official app has no layout, so the group
     *  keeps to the bottom corner, in thumb reach, and the screen's middle stays the world's. */
    private fun computeLayout(): Layout {
        if (kx <= 0f || ky <= 0f) {
            lay = Layout(0f, 0f, 0f, 0f)
            return lay
        }
        val ins = safeInsets()
        val w = (if (collapsed) GROUP_W_COLLAPSED else GROUP_W) / kx
        val h = GROUP_H / ky
        val margin = 4f / ky
        val gx0 = width - ins[1] - w
        val safeTop = ins[0].toFloat()
        val safeBottom = (height - ins[2]).toFloat()
        val lowest = safeBottom - margin - h
        val top =
            if (height <= width && (safeBottom - safeTop) * ky >= 503f) {
                (safeTop + safeBottom) / 2f + 87f / ky - h / 2f
            } else {
                lowest
            }
        lay = Layout(gx0, top.coerceIn(minOf(safeTop + margin, lowest), lowest), w, h)
        return lay
    }

    /** The left edge of grid column [col], in canvas px from the group's left: each column keeps its own edge of the
     *  group, so the outer one stays right-aligned as the group narrows and slides under the inner one. */
    private fun columnLeft(col: Int): Int =
        if (col == 0) FRAME_INSET else (if (collapsed) GROUP_W_COLLAPSED else GROUP_W) - FRAME_INSET - STONE

    override fun onDraw(canvas: Canvas) {
        if (!shown || !gridEnabled) return
        val l = computeLayout()
        if (l.w <= 0f) return
        (if (collapsed) frameCollapsedImg else frameImg)?.let {
            dstRect.set(l.gx0, l.gy0, l.gx0 + l.w, l.gy0 + l.h)
            drawBitmap(canvas, it)
        }
        // The outer column first, so the inner one sits over it when collapsed.
        for (col in intArrayOf(1, 0)) {
            for (row in 0 until 7) {
                val i = order[row * 2 + col]
                val left = l.gx0 + columnLeft(col) / kx
                val top = l.gy0 + (FRAME_INSET + row * PITCH) / ky
                dstRect.set(left, top, left + STONE / kx, top + STONE / ky)
                drawCell(canvas, cellImage(i), i == active)
            }
        }
        if (!columnX.isNaN()) drawColumn(canvas)
    }

    /** The door, the speech bubbles and (while the chat shows) the keyboard, each over the one above it. */
    private fun drawColumn(canvas: Canvas) {
        dstRect.set(columnStone(0))
        drawCell(canvas, if (active == LOGOUT) doorActiveImg else doorImg, active == LOGOUT)
        // Put away, the bubbles flash to the door's red stone twice a second while a message waits.
        val flash = chatPutAway && chatUnread && (SystemClock.uptimeMillis() / 500L) % 2L == 1L
        dstRect.set(columnStone(1))
        val bubbles =
            when {
                !chatPutAway -> toggleOpenImg
                flash -> toggleFlashImg
                else -> toggleClosedImg
            }
        drawCell(canvas, bubbles, false)
        if (!chatPutAway) {
            dstRect.set(columnStone(2))
            drawCell(canvas, keyboardImg, false)
        }
        if (chatPutAway && chatUnread) postInvalidateDelayed(250L)
    }

    /** Column stone [c] (0 door, 1 bubbles, 2 keyboard) in view px. */
    private fun columnStone(c: Int): RectF {
        val top = columnY + (COLUMN_Y + c * PITCH) * columnScale / ky
        return RectF(columnX, top, columnX + COLUMN_W * columnScale / kx, top + STONE * columnScale / ky)
    }

    /** The image for grid entry [i] (an engine tab index or [EXPAND]); null until the sprites are captured. */
    private fun cellImage(i: Int): Bitmap? =
        when {
            i == EXPAND -> if (collapsed) showMoreImg else showLessImg
            i == active -> tabActive[i]
            else -> tabInactive[i]
        }

    private fun drawBitmap(
        canvas: Canvas,
        bmp: Bitmap,
    ) {
        srcRect.set(0, 0, bmp.width, bmp.height)
        canvas.drawBitmap(bmp, srcRect, dstRect, bmpPaint)
    }

    /** Draw [bmp] into dstRect, or a stone-toned cell while the sprites aren't captured yet (still reads + taps). */
    private fun drawCell(
        canvas: Canvas,
        bmp: Bitmap?,
        isActive: Boolean,
    ) {
        if (bmp != null) {
            drawBitmap(canvas, bmp)
        } else {
            canvas.drawRoundRect(dstRect, dp(4f), dp(4f), if (isActive) fallbackActivePaint else fallbackPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!shown || !gridEnabled || lay.w <= 0f) return false
        when (event.actionMasked) {
            // Consume only if the gesture STARTS on the group or the column, so other taps fall through to the game.
            MotionEvent.ACTION_DOWN -> {
                return columnAt(event.x, event.y) != NONE || slotAt(event.x, event.y) != NONE
            }

            MotionEvent.ACTION_UP -> {
                when (columnAt(event.x, event.y)) {
                    0 -> {
                        onTabTap?.invoke(LOGOUT)
                        return true
                    }

                    1 -> {
                        onChatToggle?.invoke()
                        return true
                    }

                    2 -> {
                        onKeyboard?.invoke()
                        return true
                    }
                }
                val i = slotAt(event.x, event.y)
                if (i == NONE) return false
                if (i == EXPAND) {
                    collapsed = !collapsed
                    computeLayout()
                    invalidate()
                    onCollapseToggle?.invoke(collapsed)
                } else {
                    onTabTap?.invoke(i)
                }
                return true
            }
        }
        return false
    }

    /** The column stone under view-px (x,y): 0 door, 1 bubbles, 2 keyboard, or [NONE]. While the column sits by the
     *  screen's left edge each stone reaches out to it, and the door up to the top edge; a chat dragged away from
     *  them keeps only a small slop, so the column never takes the world's taps. */
    private fun columnAt(
        x: Float,
        y: Float,
    ): Int {
        if (columnX.isNaN()) return NONE
        val ins = safeInsetsIn(this)
        val inColumn = x >= columnX - edgeReach(columnX, ins[0].toFloat()) && x <= columnX + COLUMN_W * columnScale / kx
        return (0 until (if (chatPutAway) 2 else 3)).firstOrNull { c ->
            val r = columnStone(c)
            val top = if (c == 0) r.top - edgeReach(r.top, ins[1] + COLUMN_Y * columnScale / ky) else r.top
            inColumn && y >= top && y < r.bottom
        } ?: NONE
    }

    /** How far a stone [gap] view px from a screen edge takes taps beyond it: the whole gap while the stone sits at
     *  its home by the edge ([home] px in, plus the slop), else the slop alone. */
    private fun edgeReach(
        gap: Float,
        home: Float,
    ): Float = if (gap <= home + dp(COLUMN_SLOP_DP)) gap else dp(COLUMN_SLOP_DP)

    /** Engine tab (stone) index under view-px (x,y), [EXPAND] for the expand stone, or [NONE] outside the group. */
    private fun slotAt(
        x: Float,
        y: Float,
    ): Int {
        val l = lay
        if (l.w <= 0f) return NONE
        // TOUCH-1: the stones are small, so near-miss taps just OUTSIDE the group snap to the nearest edge stone:
        // left into the scene, up into the minimap gap, and right/bottom out to the screen's edge. Interior
        // precision is unchanged. Collapsed, the outer column is tucked under the inner one and takes no taps.
        if (x < l.gx0 - dp(10f) || y < l.gy0 - dp(4f)) return NONE
        val cx = (x - l.gx0) * kx
        val cy = (y - l.gy0) * ky
        val col = if (collapsed || cx < columnLeft(0) + STONE) 0 else 1
        val row = ((cy - FRAME_INSET) / PITCH).toInt().coerceIn(0, 6)
        return order[row * 2 + col]
    }

    private companion object {
        const val EXPAND = -1 // order's entry for the expand stone (not an engine tab)
        const val NONE = -2 // slotAt/columnAt: outside
        const val LOGOUT = 10 // the engine tab index the door opens

        // The official group (601's SIDE_RIGHT_TABS), canvas px: 40-px stones 39 apart (they overlap by a pixel),
        // 3 px inside the frame.
        const val STONE = 40
        const val PITCH = 39
        const val FRAME_INSET = 3
        const val GROUP_W = 85
        const val GROUP_W_COLLAPSED = 58
        const val GROUP_H = 280

        // The column (SIDE_LEFT_CHAT): 58-px stones, 4 px below the chat's top, at the grid's pitch.
        const val COLUMN_W = 58
        const val COLUMN_Y = 4
        const val COLUMN_SLOP_DP = 10f // the column's touch slop away from the screen's edges (columnAt)

        // Each icon's widget box on its stone: x, y, w, h.
        val TAB_BOX = intArrayOf(3, 2, 33, 36)
        val ARROW_BOX = intArrayOf(3, 1, 33, 36)
        val DOOR_BOX = intArrayOf(12, 2, 33, 36)
        val BUBBLES_BOX = intArrayOf(13, 4, 32, 32)
        val KEYBOARD_BOX = intArrayOf(12, 1, 33, 36)
    }
}
