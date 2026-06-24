package dev.audaro.helloshell

import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import org.json.JSONObject

/**
 * PERF-MEMORY-THERMAL-01: protect sustained 117HD play from silent thermal throttling. Registers a PowerManager
 * thermal-status listener (API 29+) and, while the 117HD renderer is active, steps the 117HD sceneResolutionScale
 * DOWN under thermal pressure (MODERATE+) and restores the captured baseline once the device cools. CPU and
 * base-GPU renderers are light enough to leave untouched, so the governor no-ops unless 117HD is the live renderer.
 *
 * The lever is sceneResolutionScale — the project's established 117HD perf knob (cf. SET_HD_CONFIG / FIX-13) —
 * applied via JvmLauncher.setConfig on the bridge (client) thread. All work runs inside the bridge executor: the
 * listener callback fires on the main thread, so a direct getPluginConfig().get() there would block the UI; instead
 * every status change submits one bridge task that reads the baseline (lazily, once) and applies the new scale.
 * The baseline is read from the LIVE config so recovery restores the user's actual value, not a hardcoded default.
 */
class ThermalGovernor(
    private val pm: PowerManager,
    private val is117hd: Boolean,
) {
    // Touched only on the single-threaded bridge executor -> no synchronization needed.
    private var baselineScale = -1
    private var appliedScale = -1

    @Volatile private var forcedStatus = -1   // DEBUG override (-1 = follow the real sensor)
    private var listener: Any? = null         // PowerManager.OnThermalStatusChangedListener (API 29+); Any to keep the field API-safe

    fun start() {
        if (!is117hd || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        registerQ()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun registerQ() {
        val l = PowerManager.OnThermalStatusChangedListener { status -> onStatus(status) }
        pm.addThermalStatusListener(l)
        listener = l
        Log.i(TAG, "thermal governor armed (117HD); current status=${pm.currentThermalStatus}")
        onStatus(pm.currentThermalStatus)   // apply the starting status immediately
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (listener as? PowerManager.OnThermalStatusChangedListener)?.let { pm.removeThermalStatusListener(it) }
        }
        listener = null
        // Never let a throttle persist past the session — restore the captured baseline.
        JvmLauncher.onBridgeTouch {
            if (baselineScale > 0 && appliedScale != baselineScale) {
                appliedScale = baselineScale
                JvmLauncher.setConfig("hd", "sceneResolutionScale", baselineScale.toString())
            }
        }
    }

    /** DEBUG: force a thermal status for verification (SET_THERMAL_STATUS broadcast). Pass -1 to follow the sensor. */
    fun forceStatus(status: Int) {
        forcedStatus = status
        val effective = if (status >= 0) status else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) pm.currentThermalStatus else 0
        onStatus(effective)
    }

    private fun onStatus(rawStatus: Int) {
        if (!is117hd) return
        val status = forcedStatus.let { if (it >= 0) it else rawStatus }
        JvmLauncher.onBridgeTouch {
            if (baselineScale < 0) baselineScale = readBaselineScale()
            val target = targetScaleFor(status, baselineScale)
            if (target != appliedScale) {
                val prev = appliedScale
                appliedScale = target
                JvmLauncher.setConfig("hd", "sceneResolutionScale", target.toString())
                Log.i(TAG, "thermal status=$status -> sceneResolutionScale $prev->$target (baseline=$baselineScale)")
            }
        }
    }

    // Pressure -> scale. NONE/LIGHT keep the baseline; MODERATE and SEVERE+ cap progressively lower (never above
    // the baseline, so a low-res user config is honoured).
    private fun targetScaleFor(status: Int, baseline: Int): Int = when {
        status >= 3 -> minOf(baseline, SCALE_SEVERE)     // SEVERE / CRITICAL / EMERGENCY / SHUTDOWN
        status >= 2 -> minOf(baseline, SCALE_MODERATE)   // MODERATE
        else -> baseline                                 // NONE / LIGHT
    }

    /** Read the live 117HD sceneResolutionScale (already on the bridge thread). DEFAULT_SCALE on any failure. */
    private fun readBaselineScale(): Int = try {
        val arr = JSONObject(JvmLauncher.getPluginConfig("hd")).optJSONArray("items")
        var v = DEFAULT_SCALE
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("key") == "sceneResolutionScale") {
                    v = o.optString("value", "$DEFAULT_SCALE").toIntOrNull() ?: DEFAULT_SCALE
                    break
                }
            }
        }
        v
    } catch (e: Throwable) {
        DEFAULT_SCALE
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val DEFAULT_SCALE = 85    // the shipped 117HD baseline (the "new" preset)
        private const val SCALE_MODERATE = 65
        private const val SCALE_SEVERE = 50
    }
}
