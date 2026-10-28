package dev.audaro.runemobile

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Game settings > Screen: whether the game runs in portrait (default landscape), kept in the Game settings file.
 * MainActivity and LoginActivity hold their windows to the setting's side; MainActivity's root layout listener then
 * resizes the canvas to the turned window (FOLD-3), so a change applies without a restart.
 */
object ScreenSetting {
    private const val KEY_PORTRAIT = "portrait"

    fun portrait(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_PORTRAIT, false)

    fun setPortrait(
        ctx: Context,
        on: Boolean,
    ) = prefs(ctx).edit().putBoolean(KEY_PORTRAIT, on).apply()

    /** The requestedOrientation that holds a window to the setting's side, either way up. */
    fun screenOrientation(ctx: Context): Int =
        if (portrait(ctx)) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

    /** Calls [onChange] whenever the setting changes. The prefs hold listeners weakly, so the caller keeps the
     *  returned one for as long as it wants the calls. */
    fun onChanged(
        ctx: Context,
        onChange: () -> Unit,
    ): SharedPreferences.OnSharedPreferenceChangeListener {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, k -> if (k == KEY_PORTRAIT) onChange() }
        prefs(ctx).registerOnSharedPreferenceChangeListener(l)
        return l
    }
}

/** The Game settings screen's first section. */
@Composable
internal fun GsScreenSection() {
    val ctx = LocalContext.current
    var portrait by remember { mutableStateOf(ScreenSetting.portrait(ctx)) }
    GsSectionHeader("Screen")
    Text("Orientation", color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
    GsSegments(listOf("Landscape", "Portrait"), if (portrait) 1 else 0) { i ->
        portrait = i == 1
        ScreenSetting.setPortrait(ctx, portrait)
    }
}
