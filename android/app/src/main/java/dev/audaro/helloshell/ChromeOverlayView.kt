package dev.audaro.helloshell

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * A6 native Jagex-Mobile chrome — cover-and-proxy tab-rails. A full-screen overlay over the live SurfaceView
 * that draws a native tab stone over each of the engine's 14 tab stones (their canvas bounds come from
 * MobileChromeBridge.getSnapshot each frame), highlights the active tab, and proxies a tap on a native stone to
 * a precise click on the live engine stone centre (JavaBridge.onClickPrecise) — so the engine + every plugin's
 * tab behaviour stays authoritative and no widget is moved or hidden (the proven iOS cover-and-proxy primitive;
 * the engine-side "flip to mobile toplevel 601" path is RED, and mobilechrome.repos was reverted on iOS).
 *
 * Stone bounds arrive in GAME-CANVAS px; the host supplies the game->view scale (the blit full-stretches the
 * frame to this view) via [setStones]. The view is interactive ONLY on the stones — a touch that doesn't start
 * on a stone returns false so it falls through to the game SurfaceView below.
 */
class ChromeOverlayView(context: Context) : View(context) {

    /** One tab stone: native-view-space rect + the game-canvas centre to proxy a click onto. */
    private class Stone(val rect: RectF, val gameCx: Int, val gameCy: Int, val index: Int)

    private var stones: List<Stone> = emptyList()
    private var activeTab = -1
    private var shown = true

    /** Host callback: the user tapped native tab [index]; proxy onClickPrecise on its live stone centre. */
    var onTabTap: ((index: Int, gameCx: Int, gameCy: Int) -> Unit)? = null

    private val stonePaint = Paint().apply { color = Color.argb(235, 60, 50, 38); isAntiAlias = true }
    private val activePaint = Paint().apply { color = Color.argb(245, 104, 86, 62); isAntiAlias = true }
    private val borderPaint = Paint().apply {
        color = Color.argb(255, 140, 128, 102); style = Paint.Style.STROKE; strokeWidth = dp(1.5f); isAntiAlias = true
    }
    private val activeBorderPaint = Paint().apply {
        color = Color.argb(255, 255, 214, 130); style = Paint.Style.STROKE; strokeWidth = dp(2f); isAntiAlias = true
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    /**
     * Feed the latest chrome geometry. [tabs] is 14 entries of game-canvas [x,y,w,h] (null where a stone isn't
     * built); [sx]/[sy] map game-canvas px -> this view's px (the blit full-stretch). Returns the count of
     * stones laid out (for the GREEN gate / logging).
     */
    fun setStones(tabs: List<IntArray?>, activeTab: Int, sx: Float, sy: Float): Int {
        val list = ArrayList<Stone>(tabs.size)
        for (i in tabs.indices) {
            val b = tabs[i] ?: continue
            if (b.size < 4 || b[2] <= 0 || b[3] <= 0) continue
            val l = b[0] * sx; val t = b[1] * sy
            val r = (b[0] + b[2]) * sx; val btm = (b[1] + b[3]) * sy
            list.add(Stone(RectF(l, t, r, btm), b[0] + b[2] / 2, b[1] + b[3] / 2, i))
        }
        stones = list
        this.activeTab = activeTab
        invalidate()
        return list.size
    }

    fun setShown(on: Boolean) { if (shown != on) { shown = on; invalidate() } }
    val railsShown: Boolean get() = shown
    fun stoneCount(): Int = stones.size

    override fun onDraw(canvas: Canvas) {
        if (!shown) return
        val rad = dp(4f)
        for (s in stones) {
            val active = s.index == activeTab
            canvas.drawRoundRect(s.rect, rad, rad, if (active) activePaint else stonePaint)
            canvas.drawRoundRect(s.rect, rad, rad, if (active) activeBorderPaint else borderPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!shown || stones.isEmpty()) return false
        when (event.actionMasked) {
            // Consume only if the gesture STARTS on a stone, so non-stone taps fall through to the game below.
            MotionEvent.ACTION_DOWN -> return stoneAt(event.x, event.y) != null
            MotionEvent.ACTION_UP -> {
                val s = stoneAt(event.x, event.y) ?: return false
                onTabTap?.invoke(s.index, s.gameCx, s.gameCy)
                return true
            }
        }
        return false
    }

    private fun stoneAt(x: Float, y: Float): Stone? = stones.firstOrNull { it.rect.contains(x, y) }
}
