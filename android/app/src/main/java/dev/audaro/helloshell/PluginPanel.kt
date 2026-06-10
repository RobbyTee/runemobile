package dev.audaro.helloshell

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
    val active: Boolean,       // engine actually started it; enabled && !active (after grace) = couldn't start
    val hasConfig: Boolean,
    val configGroup: String?,
    val pinned: Boolean,
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
    var configFor by remember { mutableStateOf<Pair<String, String>?>(null) }   // (configGroup, pluginName)
    var panelListOpen by remember { mutableStateOf(false) }
    var panelOpen by remember { mutableStateOf<Pair<Int, String>?>(null) }       // (panelIndex, tooltip)
    var questOpen by remember { mutableStateOf(false) }                          // A5 Tier-2 native screens
    var worldOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    if (questOpen) { QuestHelperScreen(onBack = { questOpen = false }); return }
    if (worldOpen) { WorldSwitcherScreen(onBack = { worldOpen = false }); return }
    val po = panelOpen
    if (po != null) {
        PanelView(po.first, po.second, onBack = { panelOpen = null })
        return
    }
    if (panelListOpen) {
        PanelList(
            onOpen = { i, t -> panelOpen = i to t },
            onOpenQuest = { questOpen = true },
            onOpenWorld = { worldOpen = true },
            onBack = { panelListOpen = false },
        )
        return
    }
    val cf = configFor
    if (cf != null) {
        ConfigScreen(cf.first, cf.second, onBack = { configFor = null })
        return
    }

    // Finding 8: a plugin just toggled ON is starting — suppress the "couldn't start" badge during a grace
    // window, then re-fetch the true active state. A row is incompatible = enabled && !active && not pending.
    var pending by remember { mutableStateOf(setOf<String>()) }

    suspend fun reload() {
        // listPlugins() must run off the UI thread + on a HotSpot-attached thread -> the rl-bridge executor.
        val json = withContext(Dispatchers.IO) { JvmLauncher.onBridge { JvmLauncher.listPlugins() }.get() }
        rows = parsePlugins(json)
        loading = false
    }

    LaunchedEffect(Unit) {
        reload()
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
                TextButton(onClick = { panelListOpen = true }) { Text("Panels") }
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
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    items(shown, key = { it.className }) { p ->
                        PluginRowItem(
                            p,
                            // enabled but the engine never started it (after the startup grace) = couldn't start.
                            incompatible = p.enabled && !p.active && p.className !in pending,
                            onToggle = { checked ->
                                rows = rows.map { if (it.className == p.className) it.copy(enabled = checked) else it }
                                JvmLauncher.onBridge { JvmLauncher.setPluginEnabled(p.className, checked) }
                                if (checked) {
                                    // Grace the badge while it starts, then re-fetch the real active state.
                                    pending = pending + p.className
                                    scope.launch {
                                        kotlinx.coroutines.delay(4000)
                                        reload()
                                        pending = pending - p.className
                                    }
                                } else {
                                    pending = pending - p.className
                                }
                            },
                            onTogglePin = {
                                val nowPinned = !p.pinned
                                rows = rows.map { if (it.className == p.className) it.copy(pinned = nowPinned) else it }
                                    .sortedWith(compareByDescending<PluginRow> { it.pinned }.thenBy { it.name.lowercase() })
                                // Persist immediately so a deliberate pin survives even a hard kill. setPinned
                                // ENQUEUES a client-thread write and returns a writeId; flush only AFTER that
                                // write is processed (else sendConfig races ahead and flushes stale state).
                                JvmLauncher.onBridge {
                                    val ack = JvmLauncher.setPinned(p.name, nowPinned)
                                    val wid = Regex("\"writeId\":(\\d+)").find(ack)?.groupValues?.get(1)?.toLongOrNull()
                                    var i = 0
                                    while (wid != null && JvmLauncher.processedWriteId() < wid && i++ < 40) Thread.sleep(25)
                                    JvmLauncher.flushConfig()
                                }
                                // The row jumps to/from the pinned group; reveal it (LazyColumn keeps the old
                                // scroll anchor otherwise, hiding a just-pinned item above the fold).
                                if (nowPinned) scope.launch { listState.animateScrollToItem(0) }
                            },
                            onOpenConfig = if (p.hasConfig && p.configGroup != null) {
                                { configFor = p.configGroup!! to p.name }
                            } else null,
                        )
                        HorizontalDivider(color = Color(0x22FFFFFF))
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginRowItem(
    p: PluginRow,
    incompatible: Boolean,
    onToggle: (Boolean) -> Unit,
    onTogglePin: () -> Unit,
    onOpenConfig: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Star toggle: pin a favourite to the top of the list (shared pinnedPlugins CSV).
        Text(
            if (p.pinned) "★" else "☆",
            color = if (p.pinned) Color(0xFFFFC107) else Color(0xFF777777),
            fontSize = 20.sp,
            modifier = Modifier.clickable { onTogglePin() }.padding(end = 10.dp),
        )
        val nameMod = if (onOpenConfig != null) Modifier.weight(1f).clickable { onOpenConfig() } else Modifier.weight(1f)
        Column(nameMod) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, color = if (incompatible) Color(0xFF9E9E9E) else Color.White)
                if (onOpenConfig != null) Text("  ⚙", color = Color(0xFF9E9E9E))   // tap the name to configure
            }
            // Finding 8: the toggle is on but the engine couldn't start it on this platform (GPU, Discord,
            // Developer Tools, …) — tell the user instead of leaving a dead toggle.
            if (incompatible) Text("✕ couldn't start (incompatible)", color = Color(0xFFE57373), fontSize = 11.sp)
        }
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
            active = o.optBoolean("active"),
            hasConfig = o.optBoolean("hasConfig"),
            configGroup = if (o.isNull("configGroup")) null else o.optString("configGroup", null),
            pinned = o.optBoolean("pinned"),
        )
    }.sortedWith(compareByDescending<PluginRow> { it.pinned }.thenBy { it.name.lowercase() })
} catch (t: Throwable) {
    Log.e("MainActivity", "ANDROID_A4_UI_RED parse failed: $t")
    emptyList()
}
