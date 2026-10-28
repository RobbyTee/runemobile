package dev.audaro.runemobile

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
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
 * Game settings > Screen: landscape (the default), portrait, or Auto (turns with the phone), kept in the Game settings
 * file. MainActivity and LoginActivity hold their windows to the setting; MainActivity's root layout listener then
 * resizes the canvas to the turned window (FOLD-3), so a change, or under Auto a turn, applies without a restart.
 */
object ScreenSetting {
    private const val KEY_PORTRAIT = "portrait"
    private const val KEY_AUTO = "auto_rotate"

    /** The side the setting holds the game to; under Auto, the side picked before it (see [portraitNow]). */
    fun portrait(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_PORTRAIT, false)

    fun autoRotate(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_AUTO, false)

    /** Whether the game is portrait now: the setting's side, or under Auto the way the screen stands. */
    fun portraitNow(ctx: Context): Boolean =
        if (autoRotate(ctx)) ctx.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT else portrait(ctx)

    /** Hold the game to portrait or landscape, or ([auto]) let it turn with the phone. */
    fun set(
        ctx: Context,
        portrait: Boolean,
        auto: Boolean,
    ) = prefs(ctx)
        .edit()
        .putBoolean(KEY_PORTRAIT, portrait)
        .putBoolean(KEY_AUTO, auto)
        .apply()

    /** The requestedOrientation for the setting: its side either way up, or under Auto whatever the phone's
     *  auto-rotate allows (locked, it stays as it stands). */
    fun screenOrientation(ctx: Context): Int =
        when {
            autoRotate(ctx) -> ActivityInfo.SCREEN_ORIENTATION_USER
            portrait(ctx) -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

    /** Calls [onChange] whenever the setting changes. The prefs hold listeners weakly, so the caller keeps the
     *  returned one for as long as it wants the calls. */
    fun onChanged(
        ctx: Context,
        onChange: () -> Unit,
    ): SharedPreferences.OnSharedPreferenceChangeListener {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, k -> if (k == KEY_PORTRAIT || k == KEY_AUTO) onChange() }
        prefs(ctx).registerOnSharedPreferenceChangeListener(l)
        return l
    }
}

/** The Game settings screen's first section. */
@Composable
internal fun GsScreenSection() {
    val ctx = LocalContext.current
    var choice by remember {
        mutableStateOf(
            when {
                ScreenSetting.autoRotate(ctx) -> 2
                ScreenSetting.portrait(ctx) -> 1
                else -> 0
            },
        )
    }
    GsSectionHeader("Screen")
    Text("Orientation", color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
    GsSegments(listOf("Landscape", "Portrait", "Auto"), choice) { i ->
        choice = i
        // Auto keeps the side picked before it, which a fixed choice replaces.
        ScreenSetting.set(ctx, if (i == 2) ScreenSetting.portrait(ctx) else i == 1, i == 2)
    }
    if (choice == 2) {
        Text(
            "Turns with the phone. With auto-rotate off in quick settings, it stays as it stands.",
            color = GS_DIM,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
