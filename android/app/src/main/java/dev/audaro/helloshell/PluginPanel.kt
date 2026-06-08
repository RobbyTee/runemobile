package dev.audaro.helloshell

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** One row of the A4 plugin list, parsed from MobileUiBridge.listPlugins(). */
data class PluginRow(
    val className: String,
    val name: String,
    val enabled: Boolean,
    val hasConfig: Boolean,
)

/**
 * A4 plugin list — the first native plugin-control screen. Reads the live plugin list from the engine over
 * the rl-bridge thread (JvmLauncher.onBridge -> MobileUiBridge.listPlugins) and renders a toggle per plugin;
 * flipping a switch calls setPluginEnabled on the bridge thread (optimistic UI). Compose for this data screen
 * per the hybrid UI decision; the live game overlays stay classic Views.
 */
@Composable
fun PluginPanel(onClose: () -> Unit) {
    var rows by remember { mutableStateOf<List<PluginRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var filter by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        // listPlugins() must run off the UI thread + on a HotSpot-attached thread -> the rl-bridge executor.
        val json = withContext(Dispatchers.IO) { JvmLauncher.onBridge { JvmLauncher.listPlugins() }.get() }
        rows = parsePlugins(json)
        loading = false
        Log.i("MainActivity", "ANDROID_A4_UI_GREEN panel opened rows=${rows.size}")
    }

    Surface(color = Color(0xF2121212), modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Plugins" + if (rows.isNotEmpty()) "  (${rows.count { it.enabled }}/${rows.size})" else "",
                    color = Color.White, fontSize = 20.sp, modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                )
                TextButton(onClick = onClose) { Text("Close") }
            }
            OutlinedTextField(
                value = filter, onValueChange = { filter = it },
                label = { Text("Filter") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            )
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                val shown = rows.filter { it.name.contains(filter, ignoreCase = true) }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.className }) { p ->
                        PluginRowItem(p) { checked ->
                            rows = rows.map { if (it.className == p.className) it.copy(enabled = checked) else it }
                            JvmLauncher.onBridge { JvmLauncher.setPluginEnabled(p.className, checked) }
                        }
                        HorizontalDivider(color = Color(0x22FFFFFF))
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginRowItem(p: PluginRow, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(p.name, color = Color.White, modifier = Modifier.weight(1f))
        Switch(checked = p.enabled, onCheckedChange = onToggle)
    }
}

private fun parsePlugins(json: String): List<PluginRow> = try {
    val arr = JSONArray(json)
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        PluginRow(
            className = o.getString("className"),
            name = o.optString("name", o.getString("className")),
            enabled = o.optBoolean("enabled"),
            hasConfig = o.optBoolean("hasConfig"),
        )
    }.sortedBy { it.name.lowercase() }
} catch (t: Throwable) {
    Log.e("MainActivity", "ANDROID_A4_UI_RED parse failed: $t")
    emptyList()
}
