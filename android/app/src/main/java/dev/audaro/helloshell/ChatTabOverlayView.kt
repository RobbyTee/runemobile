package dev.audaro.helloshell

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject

/**
 * W3 A3 — the enlarged native chat-tab veneer (bigger touch targets). The relocated engine chatbox keeps its own
 * tiny 23px tab row; this overlay draws BIGGER sprite-backed buttons over it — 7 equal-width tabs spanning the
 * chat width, two-line RuneScape-font labels (channel name + live filter state) like the official OSRS-mobile app.
 * A tap proxies a precise click onto the LIVE engine button (its canvas bounds come from
 * HudVeneerBridge.getChatSnapshot each frame), so the engine's own chat switching / filtering stays authoritative
 * — no widget is moved, only covered. Mirrors the iOS shell (ViewController.updateChatTabs).
 *
 * The engine is told (setChatTabRowHeight) to push its message area below this taller row, so the scrollback
 * clears the bigger buttons. The snapshot is the render-thread-published one (lock-free, no HotSpot attach).
 */
class ChatTabOverlayView(context: Context) : View(context) {

    companion object {
        const val TAB_COUNT = 7
        const val CHAT_W = 519          // the engine chatbox width (canvas px) — the row spans this
        const val ROW_H_CANVAS = 32     // the enlarged 2-line row height (canvas px); pushed into the engine
        private val NAMES = arrayOf("All", "Game", "Public", "Private", "Channel", "Clan", "Trade")
        private const val NAME_DEFAULT = 0xFFFFF7D9.toInt()   // off-white
        private const val FILTER_DEFAULT = 0xFF00E000.toInt() // green ("On")
    }

    /** Host callback: the user tapped native chat tab [index]; proxy a precise click on the live engine button
     *  centre (engine canvas px). */
    var onChatTab: ((index: Int, engineCx: Int, engineCy: Int) -> Unit)? = null

    // Long-press a tab -> the engine's chat filter menu (On / Filter / Off / Hide …), driven through the shared
    // press-and-peek menu overlay. Begin right-clicks the live engine button (engine px) + anchors the menu at the
    // finger (view px); Move drags the row highlight; End commits (or cancels). Mirrors the iOS onChatTabLongPress.
    var onChatTabLongPressBegin: ((index: Int, engineCx: Int, engineCy: Int, viewX: Float, viewY: Float) -> Unit)? = null
    var onChatTabLongPressMove: ((viewX: Float, viewY: Float) -> Unit)? = null
    var onChatTabLongPressEnd: ((cancelled: Boolean) -> Unit)? = null

    private var downTab = -1
    private var longPressActive = false
    private val gesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            val i = downTab
            if (i < 0 || i >= tabs.size) return
            val t = tabs[i]
            if (t.ew <= 0 || t.eh <= 0 || t.ex < 0 || t.ey < 0) return   // engine button not built this frame
            longPressActive = true
            onChatTabLongPressBegin?.invoke(i, t.ex + t.ew / 2, t.ey + t.eh / 2, e.x, e.y)
        }
    })

    private var shown = true
    private var enabled = false

    private class Tab(
        val name: String, val ex: Int, val ey: Int, val ew: Int, val eh: Int,
        val selected: Boolean, val hasNew: Boolean, val labelColor: Int, val filter: String, val filterColor: Int,
    )

    private var tabs: List<Tab> = emptyList()
    private var rowX = 0f; private var rowY = 0f; private var tabW = 0f; private var rowH = 0f
    private var effSel = -1
    // The engine's own tab sprite only updates on a redraw, so hold the just-tapped tab selected until the
    // snapshot agrees (no timeout — a timeout would flip back to the stale sprite). Like iOS optimisticChatTab.
    private var optimisticTab = -1

    private var btnNormal: Bitmap? = null
    private var btnSelected: Bitmap? = null
    private var btnNew: Bitmap? = null
    private var rsFont: Typeface? = null

    private val bgPaint = Paint().apply { isAntiAlias = true; isFilterBitmap = true; isDither = true }
    private val namePaint = Paint().apply { isAntiAlias = true; textAlign = Paint.Align.CENTER }
    private val statePaint = Paint().apply { isAntiAlias = true; textAlign = Paint.Align.CENTER }
    private val srcRect = Rect(); private val dstRect = RectF()

    init {
        btnNormal = loadAsset("tabassets/chatbtn.png")
        btnSelected = loadAsset("tabassets/chatbtn-selected.png")
        btnNew = loadAsset("tabassets/chatbtn-new.png")
        rsFont = try { Typeface.createFromAsset(context.assets, "tabassets/runescape.ttf") } catch (e: Exception) { null }
        rsFont?.let { namePaint.typeface = it; statePaint.typeface = it }
        Log.i("ChatTabOverlay", "a3: chat-tab assets built (sprites ${ok(btnNormal)}/${ok(btnSelected)}/${ok(btnNew)} font ${if (rsFont != null) "ok" else "sys"})")
    }

    private fun ok(b: Bitmap?) = if (b != null) "ok" else "X"
    private fun loadAsset(path: String): Bitmap? = try {
        context.assets.open(path).use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) { null }

    fun setShown(on: Boolean) { if (shown != on) { shown = on; invalidate() } }

    /**
     * Feed the latest chat-tab snapshot (HudVeneerBridge.getChatSnapshot JSON) + the game-canvas→view scale.
     * [on] = the veneer should be visible (logged in, Classic/164, chat relocated). Returns the tab count laid out.
     */
    fun update(json: String, sx: Float, sy: Float, on: Boolean): Int {
        val want = on && shown
        if (!want) {
            if (enabled || tabs.isNotEmpty()) { enabled = false; tabs = emptyList(); invalidate() }
            return 0
        }
        val o = try { JSONObject(json) } catch (e: Exception) { null }
        if (o == null || !o.optBoolean("loggedIn")) { if (tabs.isNotEmpty()) { tabs = emptyList(); invalidate() }; enabled = true; return 0 }
        val arr = o.optJSONArray("tabs") ?: run { tabs = emptyList(); invalidate(); enabled = true; return 0 }
        val chatX = o.optInt("chatX", 0); val chatY = o.optInt("chatY", 0)
        rowX = chatX * sx; rowY = chatY * sy
        tabW = (CHAT_W * sx) / TAB_COUNT; rowH = ROW_H_CANVAS * sy
        val list = ArrayList<Tab>(TAB_COUNT)
        var snapSel = -1
        val n = minOf(TAB_COUNT, arr.length())
        for (i in 0 until n) {
            val t = arr.optJSONObject(i) ?: continue
            val sel = t.optBoolean("selected")
            if (sel && snapSel < 0) snapSel = i
            list.add(Tab(
                NAMES[i], t.optInt("x", -1), t.optInt("y", -1), t.optInt("w", 0), t.optInt("h", 0),
                sel, t.optBoolean("new"), t.optInt("labelColor", -1), t.optString("filter", ""), t.optInt("filterColor", -1)))
        }
        // Reconcile the optimistic highlight with the snapshot's reported selection.
        effSel = snapSel
        if (optimisticTab >= 0) {
            if (snapSel == optimisticTab) optimisticTab = -1 else effSel = optimisticTab
        }
        tabs = list
        enabled = true
        invalidate()
        return list.size
    }

    private fun colorOf(rgb: Int, default: Int) = if (rgb < 0) default else (0xFF000000.toInt() or (rgb and 0xFFFFFF))

    override fun onDraw(canvas: Canvas) {
        if (!enabled || !shown || tabs.isEmpty() || tabW <= 0f) return
        val nameSize = rowH * 0.40f
        val stateSize = rowH * 0.34f
        for (i in tabs.indices) {
            val t = tabs[i]
            val left = rowX + i * tabW
            dstRect.set(left, rowY, left + tabW, rowY + rowH)
            val selected = i == effSel
            val bg = when { selected -> btnSelected; t.hasNew -> btnNew; else -> btnNormal }
            if (bg != null) {
                srcRect.set(0, 0, bg.width, bg.height)
                canvas.drawBitmap(bg, srcRect, dstRect, bgPaint)
            }
            val cx = left + tabW / 2f
            namePaint.textSize = nameSize
            namePaint.color = colorOf(t.labelColor, NAME_DEFAULT)
            if (t.filter.isNotEmpty()) {
                // Two lines: channel name on top, filter state ("On"/"Off"/…) below in the engine's state colour.
                canvas.drawText(t.name, cx, rowY + rowH * 0.44f, namePaint)
                statePaint.textSize = stateSize
                statePaint.color = colorOf(t.filterColor, FILTER_DEFAULT)
                canvas.drawText(t.filter, cx, rowY + rowH * 0.88f, statePaint)
            } else {
                canvas.drawText(t.name, cx, rowY + rowH * 0.64f, namePaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!enabled || !shown || tabs.isEmpty() || tabW <= 0f) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Consume only if the gesture STARTS on a tab, so taps below the row fall through to the game.
                val i = tabAt(event.x, event.y)
                if (i < 0) return false
                downTab = i; longPressActive = false
                gesture.onTouchEvent(event)   // arm the long-press timer
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                gesture.onTouchEvent(event)
                if (longPressActive) onChatTabLongPressMove?.invoke(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP -> {
                gesture.onTouchEvent(event)
                if (longPressActive) {
                    onChatTabLongPressEnd?.invoke(false)   // release on a row -> commit the filter choice
                } else {
                    val i = tabAt(event.x, event.y)
                    if (i >= 0 && i == downTab) commitTap(i)   // a plain tap -> switch to that chat tab
                }
                longPressActive = false; downTab = -1
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                gesture.onTouchEvent(event)
                if (longPressActive) onChatTabLongPressEnd?.invoke(true)
                longPressActive = false; downTab = -1
                return true
            }
        }
        return false
    }

    private fun commitTap(i: Int) {
        val t = tabs[i]
        if (t.ew > 0 && t.eh > 0 && t.ex >= 0 && t.ey >= 0) {
            onChatTab?.invoke(i, t.ex + t.ew / 2, t.ey + t.eh / 2)
            optimisticTab = i   // instant highlight until the engine sprite + snapshot agree
            invalidate()
        }
    }

    private fun tabAt(x: Float, y: Float): Int {
        if (y < rowY || y > rowY + rowH) return -1
        val i = ((x - rowX) / tabW).toInt()
        if (i < 0 || i >= tabs.size) return -1
        return i
    }
}
