package dev.audaro.helloshell

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            "color", "set", "unknown" -> OutlinedTextField(   // read-only first cut (avoid corrupting complex types)
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
        )
    }
} catch (t: Throwable) {
    Log.e("MainActivity", "ANDROID_A4_CONFIG_UI_RED parse failed: $t")
    emptyList()
}
