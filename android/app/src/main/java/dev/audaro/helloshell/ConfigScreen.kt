package dev.audaro.helloshell

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** One typed config item from MobileUiBridge.getPluginConfig(group). */
data class ConfigItem(
    val key: String,
    val name: String,
    val description: String,
    val type: String,          // boolean | int | double | string | enum | color | set | unknown
    val value: String?,        // current value (string-serialized); null for secrets
    val choices: List<String>?,// enum / set element names
    val units: String?,
    val secret: Boolean,
    val alpha: Boolean,        // color items: true when the plugin's color exposes an editable alpha channel
)

/**
 * A4 per-plugin config screen: reads one plugin's typed config schema over the rl-bridge thread
 * (getPluginConfig) and renders a control per item — bool→Switch, enum→dropdown, int/double/string→field;
 * set/color/unknown render read-only for now (safe, no naive-string-write corruption). Each change writes
 * via setConfig on the bridge thread (the engine fires ConfigChanged as the ack).
 */
@Composable
fun ConfigScreen(group: String, pluginName: String, onBack: () -> Unit) {
    var items by remember { mutableStateOf<List<ConfigItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(group) {
        val json = withContext(Dispatchers.IO) { JvmLauncher.onBridge { JvmLauncher.getPluginConfig(group) }.get() }
        items = parseConfig(json)
        loading = false
        Log.i("MainActivity", "ANDROID_A4_CONFIG_UI_GREEN group=$group items=${items.size}")
    }

    Surface(color = Color(0xF2121212), modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ Back") }
                Text(pluginName, color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
            }
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (items.isEmpty()) {
                Text("No configurable options.", color = Color.Gray, modifier = Modifier.padding(8.dp))
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(items, key = { it.key }) { item ->
                        ConfigItemControl(item) { newVal ->
                            items = items.map { if (it.key == item.key) it.copy(value = newVal) else it }
                            JvmLauncher.onBridge { JvmLauncher.setConfig(group, item.key, newVal) }
                        }
                        HorizontalDivider(color = Color(0x22FFFFFF))
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigItemControl(item: ConfigItem, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.name.ifEmpty { item.key }, color = Color.White)
                if (item.description.isNotEmpty()) Text(item.description, color = Color.Gray, fontSize = 12.sp)
            }
            when {
                item.type == "boolean" ->
                    Switch(checked = item.value?.toBoolean() ?: false, onCheckedChange = { onChange(it.toString()) })
                item.type == "enum" && item.choices != null ->
                    EnumDropdown(item.choices, item.value, onChange)
                else -> {}   // text-ish controls render full-width below
            }
        }
        when (item.type) {
            "int", "double" -> OutlinedTextField(
                value = item.value ?: "", onValueChange = onChange, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                visualTransformation = if (item.secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                label = item.units?.let { u -> { Text(u) } }, modifier = Modifier.fillMaxWidth(),
            )
            "string" -> OutlinedTextField(
                value = item.value ?: "", onValueChange = onChange, singleLine = true,
                visualTransformation = if (item.secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
            "color" -> ColorConfigControl(item, onChange)
            "dimension" -> DimensionConfigControl(item, onChange)
            "set" -> SetConfigControl(item, onChange)
            "unknown" -> OutlinedTextField(   // genuinely opaque types stay read-only (no safe round-trip)
                value = item.value ?: "", onValueChange = {}, readOnly = true, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            else -> {}   // boolean / enum handled inline above
        }
    }
}

@Composable
private fun EnumDropdown(choices: List<String>, value: String?, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text(value ?: "—", color = Color.White) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { c ->
                DropdownMenuItem(text = { Text(c) }, onClick = { expanded = false; onSelect(c) })
            }
        }
    }
}

// ---- AP-2b Finding 10: native color picker for `color`-type config items -------------------------------
//
// The engine stores a color as String.valueOf(Color.getRGB()) — a DECIMAL signed ARGB int (e.g. -65536 =
// opaque red). getPluginConfig emits that string as `value` (+ `alpha:true` when the channel is editable);
// setConfig round-trips it through ColorUtil.fromString -> Integer.decode -> new Color(i, true). Integer.decode
// overflows on hex with the high alpha bit set, so we read AND write the plain decimal int the engine stores.

/** Parse the engine's stored color string (decimal ARGB; tolerate #/0x hex) into a packed ARGB int. */
private fun parseArgb(s: String?): Int {
    val t = s?.trim().orEmpty()
    if (t.isEmpty()) return 0xFFFFFFFF.toInt()
    t.toIntOrNull()?.let { return it }                     // the common case: decimal getRGB()
    val hex = t.removePrefix("#").removePrefix("0x").removePrefix("0X")
    return hex.toLongOrNull(16)?.toInt() ?: 0xFFFFFFFF.toInt()
}

/** The row control for a color item: a swatch + hex label that opens the picker dialog. */
@Composable
private fun ColorConfigControl(item: ConfigItem, onChange: (String) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val argb = parseArgb(item.value)
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(6.dp))
            .clickableNoRipple { picking = true },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(6.dp))
                .checkerboard().background(Color(argb)),
        )
        Spacer(Modifier.size(12.dp))
        Text("#%08X".format(argb), color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = { picking = true }) { Text("Edit") }
    }
    if (picking) {
        ColorPickerDialog(
            initialArgb = argb,
            showAlpha = item.alpha,
            onDismiss = { picking = false },
            onPick = { picked -> picking = false; onChange(picked.toString()) },   // decimal ARGB, engine format
        )
    }
}

// ---- AP-2b Finding 10: dimension (WxH) + set (multi-select) controls --------------------------------------
//
// dimension round-trips as "WIDTHxHEIGHT" (ConfigManager splits on 'x'); set as a gson JSON array of the
// element names (the schema's `choices`, in enum order). Both encodings are self-described by getPluginConfig.

/** Two numeric fields (W × H) writing back "WxH" only once both parse as ints (no partial corruption). */
@Composable
private fun DimensionConfigControl(item: ConfigItem, onChange: (String) -> Unit) {
    val parts = (item.value ?: "").split("x")
    var w by remember(item.key) { mutableStateOf(parts.getOrNull(0)?.trim() ?: "") }
    var h by remember(item.key) { mutableStateOf(parts.getOrNull(1)?.trim() ?: "") }
    fun commit() {
        val wi = w.trim().toIntOrNull(); val hi = h.trim().toIntOrNull()
        if (wi != null && hi != null) onChange("${wi}x${hi}")
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = w, onValueChange = { w = it; commit() }, singleLine = true, label = { Text("W") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
        )
        Text("  ×  ", color = Color.White)
        OutlinedTextField(
            value = h, onValueChange = { h = it; commit() }, singleLine = true, label = { Text("H") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
        )
    }
}

/** A summary row + a checklist dialog of `choices`; writes back a JSON array of selected names (choices order). */
@Composable
private fun SetConfigControl(item: ConfigItem, onChange: (String) -> Unit) {
    val choices = item.choices ?: emptyList()
    val selected = remember(item.value) { parseStringArray(item.value).toMutableSet() }
    var editing by remember { mutableStateOf(false) }
    val summary = when {
        choices.isEmpty() -> "(no options)"
        selected.isEmpty() -> "None"
        selected.size == choices.size -> "All (${choices.size})"
        else -> selected.filter { it in choices }.joinToString(", ").ifEmpty { "${selected.size} selected" }
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickableNoRipple { if (choices.isNotEmpty()) editing = true },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(summary, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        if (choices.isNotEmpty()) TextButton(onClick = { editing = true }) { Text("Edit") }
    }
    if (editing) {
        // Local working copy (immutable Set in state → reassign to recompose) so Cancel discards.
        var working by remember { mutableStateOf(selected.toSet()) }
        Dialog(onDismissRequest = { editing = false }) {
            Surface(color = Color(0xFF1B1B1B), shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(16.dp).fillMaxWidth()) {
                    Text(item.name.ifEmpty { item.key }, color = Color.White, fontSize = 16.sp)
                    Spacer(Modifier.size(8.dp))
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                        items(choices, key = { it }) { c ->
                            val on = c in working
                            Row(
                                Modifier.fillMaxWidth().clickableNoRipple {
                                    working = if (on) working - c else working + c
                                },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = on, onCheckedChange = { working = if (it) working + c else working - c })
                                Text(c, color = Color.White, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    Spacer(Modifier.size(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { editing = false }) { Text("Cancel") }
                        TextButton(onClick = {
                            editing = false
                            onChange(jsonStringArray(choices.filter { it in working }))   // choices/enum order
                        }) { Text("Select") }
                    }
                }
            }
        }
    }
}

/** Parse a gson/json string array (e.g. ["A","B"]) into element names; tolerant of empty/garbage. */
private fun parseStringArray(s: String?): List<String> = try {
    val a = JSONArray(s ?: "[]")
    (0 until a.length()).map { a.getString(it) }
} catch (t: Throwable) {
    emptyList()
}

/** Serialize element names to a JSON array string the engine's gson.fromJson accepts. */
private fun jsonStringArray(names: List<String>): String =
    JSONArray().apply { names.forEach { put(it) } }.toString()

@Composable
private fun ColorPickerDialog(initialArgb: Int, showAlpha: Boolean, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    // Work in HSV (so the square + hue strip behave) + a separate alpha byte; recombine on each change.
    val hsv = remember {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initialArgb or (0xFF shl 24), it) }
    }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }
    var alpha by remember { mutableIntStateOf((initialArgb ushr 24) and 0xFF) }

    val current = (android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)) and 0x00FFFFFF) or
        ((if (showAlpha) alpha else 0xFF) shl 24)
    val opaqueHue = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))

    Dialog(onDismissRequest = onDismiss) {
        Surface(color = Color(0xFF1B1B1B), shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(18.dp).fillMaxWidth()) {
                Text("Pick a colour", color = Color.White, fontSize = 16.sp)
                Spacer(Modifier.size(12.dp))
                // Saturation (x) × value (y) square for the chosen hue.
                Box(
                    Modifier.fillMaxWidth().aspectRatio(1.1f).clip(RoundedCornerShape(8.dp))
                        .background(Brush.horizontalGradient(listOf(Color.White, opaqueHue)))
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                        .pointerInput(Unit) {
                            detectTapGestures { o ->
                                sat = (o.x / size.width).coerceIn(0f, 1f)
                                value = (1f - o.y / size.height).coerceIn(0f, 1f)
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                sat = (change.position.x / size.width).coerceIn(0f, 1f)
                                value = (1f - change.position.y / size.height).coerceIn(0f, 1f)
                            }
                        },
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val cx = sat * size.width
                        val cy = (1f - value) * size.height
                        drawCircle(Color.White, radius = 10f, center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
                        drawCircle(Color.Black, radius = 13f, center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f))
                    }
                }
                Spacer(Modifier.size(14.dp))
                // Hue strip.
                HueStrip(hue) { hue = it }
                if (showAlpha) {
                    Spacer(Modifier.size(12.dp))
                    AlphaStrip(alpha, Color(current or (0xFF shl 24))) { alpha = it }
                }
                Spacer(Modifier.size(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).checkerboard().background(Color(current)))
                    Spacer(Modifier.size(10.dp))
                    Text("#%08X".format(current), color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.size(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = { onPick(current) }) { Text("Select") }
                }
            }
        }
    }
}

/** A rainbow hue bar; the thumb sets hue 0..360. */
@Composable
private fun HueStrip(hue: Float, onHue: (Float) -> Unit) {
    val hues = listOf(0f, 60f, 120f, 180f, 240f, 300f, 360f)
        .map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it, 1f, 1f))) }
    Box(
        Modifier.fillMaxWidth().height(26.dp).clip(RoundedCornerShape(13.dp))
            .background(Brush.horizontalGradient(hues))
            .pointerInput(Unit) { detectTapGestures { o -> onHue((o.x / size.width).coerceIn(0f, 1f) * 360f) } }
            .pointerInput(Unit) { detectDragGestures { c, _ -> onHue((c.position.x / size.width).coerceIn(0f, 1f) * 360f) } },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val x = (hue / 360f) * size.width
            drawCircle(Color.White, radius = size.height / 2 - 2, center = Offset(x.coerceIn(8f, size.width - 8f), size.height / 2), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
        }
    }
}

/** A transparent→opaque bar for the current colour; the thumb sets alpha 0..255. */
@Composable
private fun AlphaStrip(alpha: Int, rgb: Color, onAlpha: (Int) -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(26.dp).clip(RoundedCornerShape(13.dp)).checkerboard()
            .background(Brush.horizontalGradient(listOf(rgb.copy(alpha = 0f), rgb.copy(alpha = 1f))))
            .pointerInput(Unit) { detectTapGestures { o -> onAlpha(((o.x / size.width).coerceIn(0f, 1f) * 255).toInt()) } }
            .pointerInput(Unit) { detectDragGestures { c, _ -> onAlpha(((c.position.x / size.width).coerceIn(0f, 1f) * 255).toInt()) } },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val x = (alpha / 255f) * size.width
            drawCircle(Color.White, radius = size.height / 2 - 2, center = Offset(x.coerceIn(8f, size.width - 8f), size.height / 2), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
        }
    }
}

/** A grey checkerboard under translucent swatches so alpha is visible. */
private fun Modifier.checkerboard(): Modifier = this.background(Color(0xFF777777)).drawBehind {
    val c = 8f
    var y = 0f; var row = 0
    while (y < size.height) {
        var x = if (row % 2 == 0) 0f else c
        while (x < size.width) {
            drawRect(Color(0xFFBBBBBB), topLeft = Offset(x, y), size = androidx.compose.ui.geometry.Size(c, c))
            x += 2 * c
        }
        y += c; row++
    }
}

/** Click without the Material ripple (the row is its own surface). */
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.pointerInput(Unit) { detectTapGestures { onClick() } }

private fun parseConfig(json: String): List<ConfigItem> = try {
    val arr: JSONArray = JSONObject(json).optJSONArray("items") ?: JSONArray()
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        val choices = o.optJSONArray("choices")?.let { c -> (0 until c.length()).map { c.getString(it) } }
        ConfigItem(
            key = o.getString("key"),
            name = o.optString("name", o.getString("key")),
            description = o.optString("description", ""),
            type = o.optString("type", "unknown"),
            value = if (o.isNull("value")) null else o.optString("value", null),
            choices = choices,
            units = if (o.isNull("units")) null else o.optString("units", null),
            secret = o.optBoolean("secret", false),
            alpha = o.optBoolean("alpha", false),
        )
    }
} catch (t: Throwable) {
    Log.e("MainActivity", "ANDROID_A4_CONFIG_UI_RED parse failed: $t")
    emptyList()
}
