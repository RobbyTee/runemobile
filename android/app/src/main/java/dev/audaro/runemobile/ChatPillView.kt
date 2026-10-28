package dev.audaro.runemobile

import android.content.Context
import android.util.Log
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject
import kotlin.math.hypot

/**
 * UI-3: tap targets over the engine's 7 filter stones (All..Trade), which the engine draws itself in the official
 * mobile layout (HudVeneerBridge.layOfficialChat), so this view draws nothing. A tap on the active stone collapses
 * the message area; a tap on another switches the channel; a long-press on a filterable one opens its engine filter
 * menu (the shared press-and-peek overlay). Taps snap to the nearest stone across the row, with a generous band
 * above it, so they are easy to hit. Geometry comes from getChatSnapshot each frame. Mirrors iOS updateChatTabs.
 *
 * The class name + the filter/haptic callbacks are kept so the host wiring + MenuOverlay path are unchanged.
 */
class ChatPillView(
    context: Context,
) : View(context) {
    companion object {
        const val TAB_COUNT = 7 // the filter stones (All..Trade); Report has none in the official layout

        // The official row (HudVeneerBridge.OFFICIAL_*), canvas px from the chat origin: seven 58x28 stones 65 apart,
        // 7 px in and 2 px down.
        private const val TAB_X = 7
        private const val TAB_PITCH = 65
        private const val TAB_W = 58
        private const val TAB_Y = 2
        private const val TAB_H = 28
        private val NAMES = arrayOf("All", "Game", "Public", "Private", "Channel", "Clan", "Trade")
        private val FILTERABLE = booleanArrayOf(false, false, true, true, true, true, true)

        // UI-3 tighten: ASYMMETRIC tappable band. Generous ABOVE the row (toward the screen top edge, where
        // nothing else lives, so a slightly-high tab tap still lands), but small BELOW — the chat-at-top message
        // area + the scene sit directly under the tabs, and a big below-band was eating those taps.
        private const val EXPAND_UP_DP = 15f
        private const val EXPAND_DOWN_DP = 4f
        private const val SLOP_DP = 12f // travel that turns a tap into a drag (cancels the long-press)
    }

    /** Tap a tab [index]: the host decides collapse (the active tab) vs switch (any other). */
    var onTabTap: ((index: Int) -> Unit)? = null

    /** Long-press a filterable tab: the host opens the engine filter menu at the live button centre (engine canvas
     *  px) anchored at the finger (view px). Returns true once the menu opened (on-canvas gate passed); false =
     *  not yet (e.g. a collapsed chat still expanding) — retried on the next move while held. */
    var onFilterOpen: ((index: Int, engineCx: Int, engineCy: Int, viewX: Float, viewY: Float) -> Boolean)? = null
    var onFilterMove: ((viewX: Float, viewY: Float) -> Unit)? = null
    var onFilterEnd: ((cancelled: Boolean) -> Unit)? = null

    /** Light haptic cue (filter open). */
    var onHaptic: (() -> Unit)? = null

    private class Tab(
        val ex: Int,
        val ey: Int,
        val ew: Int,
        val eh: Int,
    )

    private var shown = true
    private var enabled = false
    private var tabs: List<Tab> = emptyList()
    private var rowX = 0f // the first stone's left edge, view px
    private var rowY = 0f // the stones' top edge
    private var pitch = 0f // stone to stone
    private var tabW = 0f
    private var rowH = 0f
    private var effSel = -1
    private var activeTab = -1 // last known selection (kept across a collapse for the collapse/switch test)
    private var optimisticTab = -1 // hold a just-tapped tab selected until the snapshot agrees (no timeout)

    // Gesture: a DOWN in the row band tracks a tap or a filter long-press; nothing else.
    private var tracking = false
    private var downX = 0f
    private var downY = 0f
    private var downTab = -1
    private var moved = false
    private var filterArmed = false // long-press fired on a filterable tab; retry onFilterOpen until it lands
    private var filterTab = -1 // >= 0 while the filter menu is live (host owns the MenuOverlay)
    private val longPressRunnable = Runnable { onLongPress() }

    private val dp = resources.displayMetrics.density

    fun setShown(on: Boolean) {
        if (shown != on) {
            shown = on
            invalidate()
        }
    }

    /** Optimistically select a just-tapped tab so the highlight is instant (cleared once the snapshot agrees). */
    fun markOptimistic(k: Int) {
        optimisticTab = k
        activeTab = k
        invalidate()
    }

    /** The currently selected channel index (last known); 0 when nothing is reported yet. */
    val currentTab: Int get() = if (activeTab >= 0) activeTab else 0

    /** How much smaller than canvas px the chat shows (the large portrait UI shows it at 600/765); the stones follow. */
    var chatScale = 1f

    /** Feed the latest chat snapshot + canvas→view scale each frame. Returns the tab count parsed. */
    fun update(
        json: String,
        sx: Float,
        sy: Float,
        on: Boolean,
    ): Int {
        val want = on && shown
        if (!want) {
            if (enabled || tabs.isNotEmpty()) {
                reset()
                enabled = false
                tabs = emptyList()
                invalidate()
            }
            return 0
        }
        val o =
            try {
                JSONObject(json)
            } catch (e: Exception) {
                null
            }
        if (o == null || !o.optBoolean("loggedIn")) {
            if (tabs.isNotEmpty()) {
                reset()
                tabs = emptyList()
                invalidate()
            }
            enabled = true
            return 0
        }
        val arr =
            o.optJSONArray("tabs") ?: run {
                reset()
                tabs = emptyList()
                invalidate()
                enabled = true
                return 0
            }
        rowX = (o.optInt("chatX", 0) + TAB_X * chatScale) * sx
        rowY = (o.optInt("chatY", 0) + TAB_Y * chatScale) * sy
        pitch = TAB_PITCH * chatScale * sx
        tabW = TAB_W * chatScale * sx
        rowH = TAB_H * chatScale * sy
        val list = ArrayList<Tab>(TAB_COUNT)
        var snapSel = -1
        val n = minOf(TAB_COUNT, arr.length())
        for (i in 0 until n) {
            val t = arr.optJSONObject(i) ?: continue
            val sel = t.optBoolean("selected")
            if (sel && snapSel < 0) snapSel = i
            list.add(
                Tab(
                    t.optInt("x", -1),
                    t.optInt("y", -1),
                    t.optInt("w", 0),
                    t.optInt("h", 0),
                ),
            )
        }
        effSel = snapSel
        if (optimisticTab >= 0) {
            if (snapSel == optimisticTab) optimisticTab = -1 else effSel = optimisticTab
        }
        if (effSel >= 0) activeTab = effSel
        tabs = list
        enabled = true
        return list.size
    }

    /** The engine chat-button centre (canvas px) for channel i, or null while it isn't built/on-canvas. */
    fun engineButtonCenter(i: Int): Pair<Int, Int>? {
        val t = tabs.getOrNull(i) ?: return null
        if (t.ew <= 0 || t.eh <= 0 || t.ex < 0 || t.ey < 0) return null
        return (t.ex + t.ew / 2) to (t.ey + t.eh / 2)
    }

    /** Snap a touch to the nearest stone across the row (the gaps split between neighbours) + an asymmetric
     *  vertical band; -1 if outside. */
    private fun tabAt(
        x: Float,
        y: Float,
    ): Int {
        if (y < rowY - EXPAND_UP_DP * dp || y > rowY + rowH + EXPAND_DOWN_DP * dp) return -1
        val gap = pitch - tabW
        if (x < rowX - gap / 2f || x >= rowX + (TAB_COUNT - 1) * pitch + tabW + gap / 2f) return -1
        return ((x - rowX + gap / 2f) / pitch).toInt().coerceIn(0, TAB_COUNT - 1)
    }

    private fun reset() {
        removeCallbacks(longPressRunnable)
        tracking = false
        moved = false
        downTab = -1
        filterArmed = false
        filterTab = -1
    }

    // --- gesture ----------------------------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!enabled || !shown || tabs.isEmpty() || tabW <= 0f) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val k = tabAt(event.x, event.y)
                if (k < 0) return false // outside the row band -> fall through to the game (camera/scroll/etc.)
                tracking = true
                moved = false
                filterArmed = false
                filterTab = -1
                downX = event.x
                downY = event.y
                downTab = k
                if (FILTERABLE[k]) postDelayed(longPressRunnable, GameSettings.longPressMs(context))
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                if (!moved && hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()) > SLOP_DP * dp) {
                    moved = true
                    removeCallbacks(longPressRunnable) // a drag isn't a long-press
                }
                if (filterTab >= 0) {
                    onFilterMove?.invoke(event.x, event.y)
                } else if (filterArmed) {
                    // The chat was collapsed when the long-press fired; the host expanded it. Retry until the
                    // engine button is on-canvas and the menu opens.
                    val centre = engineButtonCenter(downTab)
                    if (onFilterOpen?.invoke(downTab, centre?.first ?: -1, centre?.second ?: -1, event.x, event.y) == true) {
                        filterTab = downTab
                        onHaptic?.invoke()
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!tracking) return false
                removeCallbacks(longPressRunnable)
                if (filterTab >= 0) {
                    onFilterEnd?.invoke(false) // release on a menu row commits the filter choice
                    filterTab = -1
                } else if (!moved && !filterArmed) {
                    val k = tabAt(event.x, event.y).let { if (it >= 0) it else downTab }
                    if (k >= 0) onTabTap?.invoke(k)
                }
                tracking = false
                filterArmed = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (!tracking) return false
                removeCallbacks(longPressRunnable)
                if (filterTab >= 0) {
                    onFilterEnd?.invoke(true)
                    filterTab = -1
                }
                tracking = false
                filterArmed = false
                return true
            }
        }
        return false
    }

    private fun onLongPress() {
        if (!tracking || moved || downTab < 0 || !FILTERABLE[downTab]) return
        filterArmed = true
        val centre = engineButtonCenter(downTab)
        if (onFilterOpen?.invoke(downTab, centre?.first ?: -1, centre?.second ?: -1, downX, downY) == true) {
            filterTab = downTab
            onHaptic?.invoke()
            Log.i("ChatPill", "ui3: long-press $downTab (${NAMES[downTab]}) -> filter menu")
        }
    }
}
