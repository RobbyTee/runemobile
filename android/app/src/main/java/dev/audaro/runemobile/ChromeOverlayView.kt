package dev.audaro.runemobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import android.view.MotionEvent
import android.view.View

/**
 * A6 native Jagex-Mobile chrome — the OSRS-mobile tab dock. A full-screen overlay over the live SurfaceView that
 * draws the side-panel tabs as a 2-column × 7-row grid at the right (the official mobile arrangement), using the
 * OSRS stone sprites + tab glyphs from the live cache. The 7th inner stone collapses the grid to its inner column,
 * and logout is a door the host places by the chat ([setDoorRect]), both as in the official app. The engine's own
 * 14 tab stones are HIDDEN (HudVeneerBridge.setStonesHidden) so these native cells ARE the tabs; a tap opens the
 * tab by replaying the engine stone's op script (HudVeneerBridge.openTab) — no widget is moved or covered, the
 * engine + every plugin stay authoritative. This mirrors the proven iOS layout (ViewController.updateChrome / TAB_GRID_ORDER).
 *
 * The grid is laid out in this view's pixels (the live frame blits full-stretch under it). The host drives the
 * active-tab highlight + enable state from the MobileChromeBridge snapshot each frame ([setActiveTab] /
 * [setGridEnabled]); a tap reports the engine tab index via [onTabTap]. Cells auto-fit the bottom ~56% of the
 * safe height so the grid never collides with the minimap (top-right).
 */
class ChromeOverlayView(
    context: Context,
) : View(context) {
    /** Host callback: the user tapped the tab whose engine stone index is [engineIndex] — replay its op script. */
    var onTabTap: ((engineIndex: Int) -> Unit)? = null

    /** Host callback: the expand stone collapsed ([collapsed] = true) or expanded the grid; the host keeps it. */
    var onCollapseToggle: ((collapsed: Boolean) -> Unit)? = null

    private var active = -1 // engine tab index of the open tab (red stone), -1 = none
    private var gridEnabled = false // true only in Resizable - Modern (PRE_EOC, toplevel 164), logged in
    private var shown = true // A/B debug toggle (▦ native grid vs ▤ raw engine stones)

    // Composited tab images: stone background + centred glyph, built once. [0] inactive (grey), [1] active (red).
    private val tabInactive = arrayOfNulls<Bitmap>(14)
    private val tabActive = arrayOfNulls<Bitmap>(14)
    private var showLessImg: Bitmap? = null // the expand stone while both columns show
    private var showMoreImg: Bitmap? = null // and while collapsed
    private var doorImg: Bitmap? = null
    private var doorActiveImg: Bitmap? = null
    private var assetsBuilt = false

    // Collapsed: only the inner column shows, at the right edge where the outer one was (601's expand stone).
    private var collapsed = false

    // The logout door's rect in view px, beside the chat (the host owns the chat's position); empty = not shown.
    // doorHit is the same rect stretched up to the screen's top edge (nothing else lives above it).
    private val doorRect = RectF()
    private val doorHit = RectF()

    // Last computed layout (view px), so onTouchEvent + the host's side-panel relocation read the same geometry.
    private var lay = Layout(0f, 0f, 0f)

    private class Layout(
        val gx0: Float,
        val gy0: Float,
        val cell: Float,
    )

    // UI-1: when > 0, shrink the 7-row grid to this view-px height so it matches the open side panel's height (the
    // panel is a fixed engine raster we can't grow); cell is floored so tabs stay tappable and capped so it only
    // ever shrinks below today's size.
    private var targetGridHpx = 0f

    // Canvas px per view px, for the official vertical anchor (computeLayout); 0 = not known yet, so bottom-anchor.
    private var canvasPerPx = 0f

    private val bmpPaint =
        Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            isDither = true
        }
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

    /** Build the 14 active + 14 inactive tab images from a runtime sprite set (the real OSRS cache sprites the
     *  engine loaded after login — stone-active/inactive + icon-0..13), composited stone-then-centred-glyph at the
     *  stone's native size. Idempotent; re-applies if a fresher set arrives. */
    fun setSprites(set: TabSprites.Set) {
        if (!set.usableForGrid()) return
        for (i in 0 until 14) {
            val icon = set.icons.getOrNull(i)
            tabInactive[i] = composite(set.stoneInactive, icon)
            tabActive[i] = composite(set.stoneActive, icon)
        }
        set.showLess?.let { showLessImg = composite(set.stoneInactive, it) }
        set.showMore?.let { showMoreImg = composite(set.stoneInactive, it) }
        val door = set.icons.getOrNull(LOGOUT)
        if (door != null && set.doorStone != null && set.doorStoneActive != null) {
            doorImg = composite(set.doorStone, door)
            doorActiveImg = composite(set.doorStoneActive, door)
        }
        assetsBuilt = true
        Log.i("ChromeOverlay", "a6: tab assets built from runtime cache sprites")
        invalidate()
    }

    /** Composite a stone background with a centred glyph at the stone's native size (no scaling), like the iOS
     *  compositeStone:icon:. Returns the stone alone if there's no icon, or the icon alone if there's no stone. */
    private fun composite(
        stone: Bitmap?,
        icon: Bitmap?,
    ): Bitmap? {
        if (stone == null) return icon
        val out = Bitmap.createBitmap(stone.width, stone.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(stone, 0f, 0f, null)
        if (icon != null) {
            val ix = ((stone.width - icon.width) / 2f)
            val iy = ((stone.height - icon.height) / 2f)
            c.drawBitmap(icon, ix, iy, null)
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

    // The visible grid's left/top edge and height in view px (for the host's side-panel relocation). Valid after a
    // draw/layout pass.
    val gridLeftPx: Float get() = lay.gx0
    val gridTopPx: Float get() = lay.gy0
    val gridHeightPx: Float get() = 7 * lay.cell

    /** Host: restore the persisted collapse state (the expand stone itself toggles it and reports [onCollapseToggle]). */
    fun setCollapsed(on: Boolean) {
        if (collapsed != on) {
            collapsed = on
            computeLayout()
            invalidate()
        }
    }

    /** Host: the logout door's rect in view px, beside the chat; null hides it. */
    fun setDoorRect(r: RectF?) {
        if (r == null) {
            if (!doorRect.isEmpty) {
                doorRect.setEmpty()
                doorHit.setEmpty()
                invalidate()
            }
        } else if (r != doorRect) {
            doorRect.set(r)
            doorHit.set(r.left, 0f, r.right, r.bottom)
            invalidate()
        }
    }

    /** Host: canvas px per view px, so the grid can sit on the official app's anchor (see computeLayout). */
    fun setCanvasScale(canvasPerViewPx: Float) {
        if (canvasPerPx != canvasPerViewPx) {
            canvasPerPx = canvasPerViewPx
            computeLayout()
            invalidate()
        }
    }

    /** UI-1: ask the grid to match the open side-panel height (view px; 0 = natural size). Recomputes the layout
     *  immediately so the host can read the new gridLeftPx/gridTopPx in the same pass (no one-frame jitter). */
    fun setTargetGridHeight(viewPx: Float) {
        if (targetGridHpx != viewPx) {
            targetGridHpx = viewPx
            computeLayout()
            invalidate()
        }
    }

    /** [top, right, bottom] safe-area insets (display cutout + system bars), so the grid clears a landscape
     *  notch / nav bar. Defaults to 0 (the full-bleed emulator case). */
    private fun safeInsets(): IntArray = safeInsetsIn(this).let { intArrayOf(it[1], it[2], it[3]) }

    /** Recompute + cache the grid geometry from the current view size + insets (matches iOS updateChrome and
     *  tabGridTopForHeight). Vertically it takes the official app's anchor (interface 601, script 919): once the
     *  safe area is at least 503 game px tall the tab group (and the panel, which the host centres on it) is centred
     *  87 game px below its middle, and below that it sits 4 px off its bottom; never past the bottom margin. */
    private fun computeLayout(): Layout {
        val ins = safeInsets()
        val cap = dp(48f)
        val margin = dp(6f)
        val availH = (height - ins[0] - ins[2]) * 0.56f
        // UI-1: when a target height is set, size the 7 rows to it (floored at dp(24) — TOUCH-1 raised the
        // tappability floor from dp(20) — so the cell can approach the panel height the grid is matching),
        // still capped so the grid only ever shrinks to meet the panel, never grows past today.
        val cell =
            if (targetGridHpx > 0f) {
                minOf(cap, maxOf(targetGridHpx / 7f, dp(24f)))
            } else {
                minOf(cap, if (availH > 0f) availH / 7f else cap)
            }
        val gridW = (if (collapsed) 1 else 2) * cell
        val gridH = 7 * cell
        val gx0 = width - ins[1] - margin - gridW
        val safeTop = ins[0].toFloat()
        val safeBottom = (height - ins[2]).toFloat()
        val lowest = safeBottom - margin - gridH
        val k = canvasPerPx
        val gy0 =
            if (k <= 0f) {
                lowest
            } else {
                val top =
                    if ((safeBottom - safeTop) * k >= 503f) {
                        (safeTop + safeBottom) / 2f + 87f / k - gridH / 2f
                    } else {
                        safeBottom - 4f / k - gridH
                    }
                top.coerceIn(minOf(safeTop + margin, lowest), lowest)
            }
        lay = Layout(gx0, gy0, cell)
        return lay
    }

    override fun onDraw(canvas: Canvas) {
        if (!shown || !gridEnabled) return
        val l = computeLayout()
        if (l.cell <= 0f) return
        for (slot in 0 until 14) {
            val i = order[slot]
            val col = slot % 2
            if (collapsed && col == 1) continue
            val row = slot / 2
            val left = l.gx0 + (if (collapsed) 0f else col * l.cell)
            val top = l.gy0 + row * l.cell
            dstRect.set(left, top, left + l.cell, top + l.cell)
            drawCell(canvas, cellImage(i), i == active)
        }
        if (!doorRect.isEmpty) {
            dstRect.set(doorRect)
            drawCell(canvas, if (active == LOGOUT) doorActiveImg else doorImg, active == LOGOUT)
        }
    }

    /** The image for grid entry [i] (an engine tab index or [EXPAND]); null until the sprites are captured. */
    private fun cellImage(i: Int): Bitmap? =
        when {
            i == EXPAND -> if (collapsed) showMoreImg else showLessImg
            i == active -> tabActive[i]
            else -> tabInactive[i]
        }

    /** Draw [bmp] into dstRect, or a stone-toned cell while the sprites aren't captured yet (still reads + taps). */
    private fun drawCell(
        canvas: Canvas,
        bmp: Bitmap?,
        isActive: Boolean,
    ) {
        if (bmp != null) {
            srcRect.set(0, 0, bmp.width, bmp.height)
            canvas.drawBitmap(bmp, srcRect, dstRect, bmpPaint)
        } else {
            canvas.drawRoundRect(dstRect, dp(4f), dp(4f), if (isActive) fallbackActivePaint else fallbackPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!shown || !gridEnabled) return false
        val l = lay
        if (l.cell <= 0f) return false
        when (event.actionMasked) {
            // Consume only if the gesture STARTS inside the grid (or on the door), so other taps fall through to the
            // game below.
            MotionEvent.ACTION_DOWN -> {
                return onDoor(event.x, event.y) || slotAt(event.x, event.y) != NONE
            }

            MotionEvent.ACTION_UP -> {
                if (onDoor(event.x, event.y)) {
                    onTabTap?.invoke(LOGOUT)
                    return true
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

    /** The door, with its outward slop up to the screen's top edge ([doorHit]). */
    private fun onDoor(
        x: Float,
        y: Float,
    ): Boolean = doorHit.contains(x, y)

    /** Engine tab (stone) index under view-px (x,y), [EXPAND] for the expand stone, or [NONE] outside the grid. */
    private fun slotAt(
        x: Float,
        y: Float,
    ): Int {
        val l = lay
        if (l.cell <= 0f) return NONE
        // TOUCH-1: 48dp cells can't fit (7 rows must clear the top-right minimap — the 0.56 band IS that
        // clearance), so near-miss taps just OUTSIDE the grid snap to the nearest edge cell instead: clamp
        // the point into the grid when it lands within the outward slop (left into the scene, right/bottom
        // through the screen margin, top into the minimap gap). Interior precision is unchanged.
        val slopL = dp(10f)
        val slopT = dp(4f)
        val cols = if (collapsed) 1 else 2
        val gridW = cols * l.cell
        val gridH = 7 * l.cell
        if (x < l.gx0 - slopL || y < l.gy0 - slopT) return NONE
        // No right/bottom bound check beyond the view itself — the grid runs to the screen margin there, so
        // anything past the last cell (still inside this view) snaps back to it.
        val col = (((x - l.gx0).coerceAtMost(gridW - 1f)) / l.cell).toInt()
        val row = (((y - l.gy0).coerceAtMost(gridH - 1f)) / l.cell).toInt()
        if (col !in 0 until cols || row !in 0..6) return NONE
        val slot = row * 2 + col
        if (slot < 0 || slot >= order.size) return NONE
        return order[slot]
    }

    private companion object {
        const val EXPAND = -1 // order's entry for the expand stone (not an engine tab)
        const val NONE = -2 // slotAt: outside the grid
        const val LOGOUT = 10 // the engine tab index the door opens
    }
}
