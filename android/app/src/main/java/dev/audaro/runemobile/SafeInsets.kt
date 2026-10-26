package dev.audaro.runemobile

import android.os.Build
import android.view.View
import android.view.WindowInsets

/**
 * [left, top, right, bottom] safe-area insets (display cutout + system bars) as they reach [v], in its own px.
 * The window reports them against its edges; FOLD-2 can letterbox the game stage away from those edges, and a
 * view inside it is already that far clear, so each inset shrinks by the gap (a bar wider than the cutout clears
 * it entirely). With the stage filling the window the gaps are 0 and this is the raw window insets. [v] must be
 * untransformed: its offset is read with getLocationInWindow. 0 on the full-bleed emulator.
 */
internal fun safeInsetsIn(v: View): IntArray {
    val wi = v.rootWindowInsets ?: return intArrayOf(0, 0, 0, 0)
    val raw =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val i = wi.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            intArrayOf(i.left, i.top, i.right, i.bottom)
        } else {
            @Suppress("DEPRECATION")
            intArrayOf(wi.stableInsetLeft, wi.stableInsetTop, wi.stableInsetRight, wi.stableInsetBottom)
        }
    val loc = IntArray(2)
    v.getLocationInWindow(loc)
    val win = v.rootView
    val gaps = intArrayOf(loc[0], loc[1], win.width - loc[0] - v.width, win.height - loc[1] - v.height)
    return IntArray(4) { maxOf(0, raw[it] - maxOf(0, gaps[it])) }
}
