package dev.audaro.runemobile

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The phone-side game settings behind the launcher's Game settings screen. They live in their own
 * SharedPreferences file and MainActivity reads them live, so a change applies without a restart.
 */
object GameSettings {
    private const val PREFS = "game_settings"
    private const val KEY_HAPTIC_STRENGTH = "haptic_strength"

    /** The four haptic triggers, each with its own switch. All default on. */
    enum class Haptic(
        val key: String,
        val label: String,
        val detail: String,
    ) {
        OP("haptic_op", "Actions", "Tapping something you can interact with"),
        DRAG("haptic_drag", "Drag start", "Picking up an item to move it"),
        MENU_OPEN("haptic_menu_open", "Menu open", "The long-press menu appearing"),
        MENU_HOVER("haptic_menu_hover", "Menu rows", "Sliding over the menu's options"),
    }

    val STRENGTH_LABELS = listOf("Light", "Medium", "Heavy")
    private val STRENGTH_MS = longArrayOf(5, 20, 50) // one-shot length per strength
    private const val STRENGTH_DEFAULT = 1

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hapticOn(
        ctx: Context,
        h: Haptic,
    ): Boolean = prefs(ctx).getBoolean(h.key, true)

    fun setHapticOn(
        ctx: Context,
        h: Haptic,
        on: Boolean,
    ) = prefs(ctx).edit().putBoolean(h.key, on).apply()

    fun hapticStrength(ctx: Context): Int = prefs(ctx).getInt(KEY_HAPTIC_STRENGTH, STRENGTH_DEFAULT).coerceIn(0, STRENGTH_MS.size - 1)

    fun setHapticStrength(
        ctx: Context,
        s: Int,
    ) = prefs(ctx).edit().putInt(KEY_HAPTIC_STRENGTH, s.coerceIn(0, STRENGTH_MS.size - 1)).apply()

    /** How long one haptic buzz lasts at the chosen strength. */
    fun hapticMs(ctx: Context): Long = STRENGTH_MS[hapticStrength(ctx)]

    /** One buzz at the chosen strength. */
    fun buzz(
        ctx: Context,
        v: Vibrator?,
    ) {
        if (v == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // Guard the vibrate: it needs the VIBRATE permission and some devices restrict the vibrator outright — a
        // SecurityException here must never take down the UI thread (it once did, on the menu-highlight haptic).
        try {
            v.vibrate(VibrationEffect.createOneShot(hapticMs(ctx), VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
            // haptics are best-effort; ignore
        }
    }
}

private val GS_DIM = Color(0xFF9E9E9E)
private val GS_ACCENT = Color(0xFFF29E1C)

/** The native Game settings screen, reached from the launcher's More tiles. */
@Composable
fun GameSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    Surface(color = Color(0xFF121212), modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back" }) { Text("‹ Back") }
                Text("Game settings", color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
            }

            GsSectionHeader("Haptics")
            for (h in GameSettings.Haptic.entries) {
                var on by remember { mutableStateOf(GameSettings.hapticOn(ctx, h)) }
                GsSwitchRow(h.label, h.detail, on) {
                    on = it
                    GameSettings.setHapticOn(ctx, h, it)
                }
                HorizontalDivider(color = Color(0x18FFFFFF))
            }
            var strength by remember { mutableIntStateOf(GameSettings.hapticStrength(ctx)) }
            Text("Strength", color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                GameSettings.STRENGTH_LABELS.forEachIndexed { i, label ->
                    SegmentedButton(
                        selected = strength == i,
                        onClick = {
                            strength = i
                            GameSettings.setHapticStrength(ctx, i)
                            GameSettings.buzz(ctx, ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator) // a sample
                        },
                        shape = SegmentedButtonDefaults.itemShape(i, GameSettings.STRENGTH_LABELS.size),
                    ) { Text(label) }
                }
            }
            Spacer(Modifier.heightIn(min = 16.dp))
        }
    }
}

@Composable
private fun GsSectionHeader(title: String) {
    Text(
        title.uppercase(),
        color = GS_ACCENT,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun GsSwitchRow(
    label: String,
    detail: String,
    on: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!on) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 14.sp)
            Text(detail, color = GS_DIM, fontSize = 12.sp)
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
}
