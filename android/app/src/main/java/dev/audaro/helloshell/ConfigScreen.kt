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
    val type: String,          // boolean | int | double | string | enum | color | set | font | notification | unknown
    val value: String?,        // current value (string-serialized); null for secrets
    val choices: List<String>?,// enum / set element names, or friendly font preset labels
    val choiceValues: List<String>?, // font: the constant to WRITE for each label (REGULAR/BOLD/SMALL); null otherwise
    val units: String?,
    val secret: Boolean,
    val alpha: Boolean,        // color items: true when the plugin's color exposes an editable alpha channel
    val min: Int?,             // @Range min for int/double items (null = unbounded) — clamps writes
    val max: Int?,             // @Range max for int/double items (null = unbounded)
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
    var reloadKey by remember { mutableIntStateOf(0) }
    var confirmReset by remember { mutableStateOf(false) }

    LaunchedEffect(group, reloadKey) {
        loading = true
        val json = withContext(Dispatchers.IO) { JvmLauncher.onBridge { JvmLauncher.getPluginConfig(group) }.get() }
        items = parseConfig(json)
        loading = false
        Log.i("MainActivity", "ANDROID_A4_CONFIG_UI_GREEN group=$group items=${items.size}")
    }

    Surface(color = Color(0xF2121212), modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    // AP-0: persist pending edits on leave — config otherwise only flushes on app background/onStop.
                    JvmLauncher.onBridge { JvmLauncher.flushConfig() }
                    onBack()
                }) { Text("‹ Back") }
                Text(pluginName, color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { confirmReset = true }) { Text("Reset", color = Color(0xFFFF8A80)) }
            }
            if (confirmReset) {
                Dialog(onDismissRequest = { confirmReset = false }) {
                    Surface(color = Color(0xFF1B1B1B), shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.padding(18.dp).fillMaxWidth()) {
                            Text("Reset all settings?", color = Color.White, fontSize = 16.sp)
                            Spacer(Modifier.size(6.dp))
                            Text("Restores $pluginName to its defaults.", color = Color.Gray, fontSize = 13.sp)
                            Spacer(Modifier.size(12.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { confirmReset = false }) { Text("Cancel") }
                                TextButton(onClick = {
                                    confirmReset = false
                                    // Reset enqueues a client-thread write; wait for it to be processed (poll the
                                    // bridge's writeId) before re-pulling the schema, so the screen shows defaults.
                                    JvmLauncher.onBridgeTouch {
                                        val ack = JvmLauncher.resetPluginConfig(group)
                                        val wid = parseWriteId(ack)
                                        var tries = 0
                                        while (wid > 0 && JvmLauncher.processedWriteId() < wid && tries++ < 60) {
                                            Thread.sleep(10)
                                        }
                                        reloadKey++   // snapshot-state write off the bridge thread schedules recompose
                                    }
                                }) { Text("Reset", color = Color(0xFFFF8A80)) }
                            }
                        }
                    }
                }
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
                            JvmLauncher.onBridgeTouch { JvmLauncher.setConfig(group, item.key, newVal) }
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
                item.type == "font" && item.choices != null ->
                    FontDropdown(item, onChange)
                // A notification config item: a plain enable toggle writes the engine's "true"/"false" sentinel
                // (NotificationSerializer.deserialize accepts it). An enabled-but-uninitialized notification
                // inherits the global RuneLite defaults on first fire — exactly desktop's uncustomized behavior.
                item.type == "notification" ->
                    Switch(checked = isNotifEnabled(item.value), onCheckedChange = { onChange(if (it) "true" else "false") })
                else -> {}   // text-ish controls render full-width below
            }
        }
        when (item.type) {
            "int" -> NumericConfigControl(item, isInt = true, onChange = onChange)
            "double" -> NumericConfigControl(item, isInt = false, onChange = onChange)
            "string" -> OutlinedTextField(
                value = item.value ?: "", onValueChange = onChange, singleLine = true,
                visualTransformation = if (item.secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
            "color" -> ColorConfigControl(item, onChange)
            "dimension" -> DimensionConfigControl(item, onChange)
            "set" -> SetConfigControl(item, onChange)
            "notification" -> Text(
                "Uses the global notification defaults (sound / flash / colour). Edit those in RuneLite settings.",
                color = Color.Gray, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
            // AP-0: keybind items previously rendered as a blank row (no control, not even the value). There is
            // no hardware-key capture on a touch app, so show the current binding read-only instead of nothing.
            "keybind" -> Text(
                "Binding: " + (item.value?.takeIf { it.isNotBlank() } ?: "Not set") + "  ·  edit on desktop",
                color = Color(0xFF9E9E9E), fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
            "unknown" -> OutlinedTextField(   // genuinely opaque types stay read-only (no safe round-trip)
                value = item.value ?: "", onValueChange = {}, readOnly = true, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            else -> {}   // boolean / enum / font handled inline above
        }
    }
}

/** Font preset picker: shows the friendly labels (Regular/Bold/Small) but WRITES the matching engine constant
 *  (REGULAR/BOLD/SMALL) — FontTypeSerializer.deserialize accepts those legacy strings. The stored value is an
 *  opaque serialized FontType, so the current selection is matched best-effort against the choiceValues. */
@Composable
private fun FontDropdown(item: ConfigItem, onChange: (String) -> Unit) {
    val labels = item.choices ?: emptyList()
    val values = item.choiceValues ?: labels
    val curLabel = values.indexOf(item.value).let { if (it >= 0) labels.getOrNull(it) else null }
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text(curLabel ?: "—", color = Color.White) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            labels.forEachIndexed { i, l ->
                DropdownMenuItem(text = { Text(l) }, onClick = { expanded = false; onChange(values.getOrElse(i) { l }) })
            }
        }
    }
}

/**
 * AP-0: int/double field that clamps to the schema @Range and only WRITES a parseable value — fixes the prior
 * per-keystroke raw write (an out-of-range or non-numeric value corrupted the config). Local text state mirrors
 * the keystrokes so the user can type/clear freely; an over-max value snaps the display down, an under-min value
 * snaps up, and an unparseable intermediate (empty / "-" / "1.2.3") updates the display only — no write.
 */
@Composable
private fun NumericConfigControl(item: ConfigItem, isInt: Boolean, onChange: (String) -> Unit) {
    var text by remember(item.key, item.value) { mutableStateOf(item.value ?: "") }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            val parsed: Double? = if (isInt) t.trim().toIntOrNull()?.toDouble() else t.trim().toDoubleOrNull()
            if (parsed != null) {
                val lo = item.min?.toDouble()
                val hi = item.max?.toDouble()
                val clamped = when {
                    hi != null && parsed > hi -> hi
                    lo != null && parsed < lo -> lo
                    else -> parsed
                }
                if (clamped != parsed) {
                    val s = if (isInt) clamped.toInt().toString() else clamped.toString()
                    text = s
                    onChange(s)
                } else {
                    onChange(t)   // in range: write exactly what was typed (preserves "5." / "1.50")
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        visualTransformation = if (item.secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        label = item.units?.let { u -> { Text(u) } },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The writeId from a bridge ack ({"ok":true,"writeId":n}), or -1 if absent/unparseable. */
private fun parseWriteId(ack: String?): Long = try {
    JSONObject(ack ?: "{}").optLong("writeId", -1L)
} catch (t: Throwable) {
    -1L
}

/** True if a notification config value is enabled — tolerates the "true"/"false" sentinel or a full JSON object. */
private fun isNotifEnabled(v: String?): Boolean {
    val t = v?.trim().orEmpty()
    if (t == "true") return true
    if (t.isEmpty() || t == "false") return false
    return try { JSONObject(t).optBoolean("enabled", false) } catch (e: Throwable) { false }
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
        val choiceValues = o.optJSONArray("choiceValues")?.let { c -> (0 until c.length()).map { c.getString(it) } }
        ConfigItem(
            key = o.getString("key"),
            name = o.optString("name", o.getString("key")),
            description = o.optString("description", ""),
            type = o.optString("type", "unknown"),
            value = if (o.isNull("value")) null else o.optString("value", null),
            choices = choices,
            choiceValues = choiceValues,
            units = if (o.isNull("units")) null else o.optString("units", null),
            secret = o.optBoolean("secret", false),
            alpha = o.optBoolean("alpha", false),
            min = if (o.has("min")) o.optInt("min") else null,
            max = if (o.has("max")) o.optInt("max") else null,
        )
    }
} catch (t: Throwable) {
    Log.e("MainActivity", "ANDROID_A4_CONFIG_UI_RED parse failed: $t")
    emptyList()
}
