package dev.audaro.helloshell

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** One row of the A4 plugin list, parsed from MobileUiBridge.listPlugins(). */
data class PluginRow(
    val className: String,
    val name: String,
    val description: String,   // for search parity with desktop (name + description + tags)
    val tags: String,          // space-joined PluginDescriptor.tags()
    val enabled: Boolean,
    val active: Boolean,       // engine actually started it; enabled && !active (after grace) = couldn't start
    val hasConfig: Boolean,
    val configGroup: String?,
    val pinned: Boolean,
    val configOnly: Boolean,   // desktop config-only fake-plugin (RuneLite / Chat Color): no enable toggle, tap = config
)

/**
 * A4 plugin list — the first native plugin-control screen. Reads the live plugin list from the engine over
 * the rl-bridge thread (JvmLauncher.onBridge -> MobileUiBridge.listPlugins) and renders a toggle per plugin;
 * flipping a switch calls setPluginEnabled on the bridge thread (optimistic UI). Compose for this data screen
 * per the hybrid UI decision; the live game overlays stay classic Views.
 */
/** Phase-2 renderer-as-plugins: the two renderer rows are special-cased — their switch shows the SAVED
 *  LAUNCH PREFERENCE (not live plugin state, which is meaningless pre-restart) and toggling writes the
 *  preference instead of setPluginEnabled. RendererPanelStarter loads both into the list without starting. */
private const val GPU_PLUGIN_CLASS = "net.runelite.client.plugins.gpu.GpuPlugin"
private const val HD_PLUGIN_CLASS = "rs117.hd.HdPlugin"

@Composable
fun PluginPanel(
    onClose: () -> Unit,
    rendererPref: String = "cpu",
    onRendererSelect: (String) -> Unit = {},
) {
    var rows by remember { mutableStateOf<List<PluginRow>>(emptyList()) }
    var renderer by remember { mutableStateOf(rendererPref) }
    var loading by remember { mutableStateOf(true) }
    var filter by remember { mutableStateOf("") }
    // §4 redesign (increment 2): the launcher LANDING is now a labelled tile grid (renderers / pinned / panels /
    // config) instead of a 131-row scroll. The full plugin list is one tile away ("All plugins") and is also
    // revealed automatically while the user is typing in the search field (config-search parity for the long tail).
    var listMode by remember { mutableStateOf(false) }
    // Panel data for the Panels section of the grid (lifted from PanelList so panels become first-class tiles).
    var panels by remember { mutableStateOf<List<PanelEntry>>(emptyList()) }
    var panelAvail by remember { mutableStateOf(BooleanArray(7)) }   // [quest,world,xp,loot,ge,overlay,infobox]
    var configFor by remember { mutableStateOf<Pair<String, String>?>(null) }   // (configGroup, pluginName)
    var panelListOpen by remember { mutableStateOf(false) }
    var panelOpen by remember { mutableStateOf<Pair<Int, String>?>(null) }       // (panelIndex, tooltip)
    var questOpen by remember { mutableStateOf(false) }                          // A5 Tier-2 native screens
    var worldOpen by remember { mutableStateOf(false) }
    var xpOpen by remember { mutableStateOf(false) }                             // Finding 14 XP Tracker reflow
    var lootOpen by remember { mutableStateOf(false) }                           // Finding 14 Loot Tracker reflow
    var geOpen by remember { mutableStateOf(false) }                             // Finding 14 Grand Exchange reflow
    var overlayOpen by remember { mutableStateOf(false) }                        // Finding 15 overlay edit
    var infoBoxOpen by remember { mutableStateOf(false) }                        // Finding 22 infobox menu
    var hubOpen by remember { mutableStateOf(false) }                            // Finding 17 hub browse/install
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    if (questOpen) { QuestHelperScreen(onBack = { questOpen = false }); return }
    if (worldOpen) { WorldSwitcherScreen(onBack = { worldOpen = false }); return }
    if (xpOpen) { XpTrackerScreen(onBack = { xpOpen = false }); return }
    if (lootOpen) { LootTrackerScreen(onBack = { lootOpen = false }); return }
    if (geOpen) { GrandExchangeScreen(onBack = { geOpen = false }); return }
    if (overlayOpen) { OverlayEditScreen(onBack = { overlayOpen = false }); return }
    if (infoBoxOpen) { InfoBoxScreen(onBack = { infoBoxOpen = false }); return }
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
            onOpenXp = { xpOpen = true },
            onOpenLoot = { lootOpen = true },
            onOpenGe = { geOpen = true },
            onOpenOverlay = { overlayOpen = true },
            onOpenInfoBox = { infoBoxOpen = true },
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

    // Finding 17: installing a hub plugin adds it to the engine's plugin set — re-fetch the toggle list on close.
    if (hubOpen) { PluginHubScreen(onBack = { hubOpen = false; scope.launch { reload() } }); return }

    LaunchedEffect(Unit) {
        reload()
        // Panel tiles for the grid: the same data PanelList consumes (listPanels + curated-screen availability).
        val pjson = withContext(Dispatchers.IO) { JvmLauncher.onBridge { JvmLauncher.listPanels() }.get() }
        panels = parsePanels(pjson)
        panelAvail = withContext(Dispatchers.IO) {
            JvmLauncher.onBridge {
                booleanArrayOf(JvmLauncher.questAvailable(), JvmLauncher.worldAvailable(), JvmLauncher.xpAvailable(),
                    JvmLauncher.lootAvailable(), JvmLauncher.geAvailable(), JvmLauncher.overlayAvailable(),
                    JvmLauncher.infoBoxAvailable())
            }.get()
        }
        Log.i("MainActivity", "ANDROID_LAUNCHER_GRID_GREEN rows=${rows.size} pinned=${rows.count { it.pinned }} " +
            "panels=${panels.size} avail=${panelAvail.count { it }}")
    }

    Surface(color = Color(0xF2121212), modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            val inList = listMode || filter.isNotEmpty()
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (inList) {
                    // Back to the landing grid (clears any active filter so the grid isn't masked by it).
                    TextButton(onClick = { listMode = false; filter = "" }) { Text("‹ Grid") }
                }
                Text(
                    "Plugins" + if (rows.isNotEmpty())
                        "  (${rows.count { it.enabled && !it.configOnly }}/${rows.count { !it.configOnly }})" else "",
                    color = Color.White, fontSize = 20.sp, modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                )
                TextButton(onClick = { hubOpen = true }) { Text("Hub") }
                TextButton(onClick = onClose) { Text("Close") }
            }
            OutlinedTextField(
                value = filter, onValueChange = { filter = it },
                label = { Text("Search plugins") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            )
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (inList) {
                PluginListBody(
                    rows = rows, filter = filter, pending = pending, renderer = renderer, listState = listState,
                    onToggleRow = { p, checked ->
                        rows = rows.map { if (it.className == p.className) it.copy(enabled = checked) else it }
                        JvmLauncher.onBridge { JvmLauncher.setPluginEnabled(p.className, checked) }
                        if (checked) {
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
                    onTogglePin = { p ->
                        val nowPinned = !p.pinned
                        rows = rows.map { if (it.className == p.className) it.copy(pinned = nowPinned) else it }
                            .sortedWith(compareByDescending<PluginRow> { it.pinned }.thenBy { it.name.lowercase() })
                        // Persist immediately so a deliberate pin survives even a hard kill. setPinned ENQUEUES a
                        // client-thread write and returns a writeId; flush only AFTER that write is processed (else
                        // sendConfig races ahead and flushes stale state).
                        JvmLauncher.onBridge {
                            val ack = JvmLauncher.setPinned(p.name, nowPinned)
                            val wid = Regex("\"writeId\":(\\d+)").find(ack)?.groupValues?.get(1)?.toLongOrNull()
                            var i = 0
                            while (wid != null && JvmLauncher.processedWriteId() < wid && i++ < 40) Thread.sleep(25)
                            JvmLauncher.flushConfig()
                        }
                        if (nowPinned) scope.launch { listState.animateScrollToItem(0) }
                    },
                    onRendererSelect = { mode, checked ->
                        renderer = if (checked) mode else "cpu"
                        onRendererSelect(renderer)
                    },
                    onOpenConfig = { p -> configFor = p.configGroup!! to p.name },
                )
            } else {
                LauncherGrid(
                    rows = rows, renderer = renderer, panels = panels, avail = panelAvail,
                    pending = pending,
                    onRendererSelect = { mode -> renderer = mode; onRendererSelect(mode) },
                    onOpenConfig = { p -> configFor = p.configGroup!! to p.name },
                    onOpenList = { listMode = true },
                    onOpenPanel = { i, t -> panelOpen = i to t },
                    onOpenQuest = { questOpen = true }, onOpenWorld = { worldOpen = true },
                    onOpenXp = { xpOpen = true }, onOpenLoot = { lootOpen = true }, onOpenGe = { geOpen = true },
                    onOpenOverlay = { overlayOpen = true }, onOpenInfoBox = { infoBoxOpen = true },
                    onOpenHub = { hubOpen = true },
                )
            }
        }
    }
}

/**
 * §4 increment 2 — the labelled tile grid landing. Sections: Renderers (CPU/GPU/117HD radio), Pinned ★ (quick
 * config access to favourites), Panels (curated native screens + rendered-image panels as labelled tiles), and
 * More (All plugins / Plugin Hub). Replaces the deep 131-row scroll as the first thing the launcher shows.
 */
@Composable
private fun LauncherGrid(
    rows: List<PluginRow>,
    renderer: String,
    panels: List<PanelEntry>,
    avail: BooleanArray,
    pending: Set<String>,
    onRendererSelect: (String) -> Unit,
    onOpenConfig: (PluginRow) -> Unit,
    onOpenList: () -> Unit,
    onOpenPanel: (Int, String) -> Unit,
    onOpenQuest: () -> Unit,
    onOpenWorld: () -> Unit,
    onOpenXp: () -> Unit,
    onOpenLoot: () -> Unit,
    onOpenGe: () -> Unit,
    onOpenOverlay: () -> Unit,
    onOpenInfoBox: () -> Unit,
    onOpenHub: () -> Unit,
) {
    // Native curated screens, gated on the curated bridge's availability (same gating PanelList uses). Each
    // borrows the matching engine panel's icon when one exists so the tile isn't a bare glyph.
    fun iconFor(tooltip: String): Bitmap? = panels.firstOrNull { it.tooltip == tooltip }?.icon
    val nativeTiles = buildList {
        if (avail.getOrElse(2) { false }) add(TileSpec("XP Tracker", iconFor("XP Tracker"), onClick = onOpenXp))
        if (avail.getOrElse(3) { false }) add(TileSpec("Loot Tracker", iconFor("Loot Tracker"), onClick = onOpenLoot))
        if (avail.getOrElse(4) { false }) add(TileSpec("Grand Exchange", iconFor("Grand Exchange"), onClick = onOpenGe))
        if (avail.getOrElse(1) { false }) add(TileSpec("World Switcher", iconFor("World Switcher"), onClick = onOpenWorld))
        if (avail.getOrElse(0) { false }) add(TileSpec("Quest Helper", iconFor("Quest Helper"), onClick = onOpenQuest))
        if (avail.getOrElse(5) { false }) add(TileSpec("Overlay Layout", glyph = "▦", onClick = onOpenOverlay))
        if (avail.getOrElse(6) { false }) add(TileSpec("Infoboxes", glyph = "◳", onClick = onOpenInfoBox))
    }
    // Rendered-image panels for everything not covered by an available native screen (so no panel is lost when a
    // native reflow exists; if the native screen is unavailable the rendered tile remains as the fallback).
    val covered = nativeTiles.map { it.label }.toSet()
    val renderedTiles = panels.filter { it.tooltip !in covered }
        .map { p -> TileSpec(p.tooltip, p.icon) { onOpenPanel(p.index, p.tooltip) } }

    val pinnedTiles = rows.filter { it.pinned && it.className != GPU_PLUGIN_CLASS && it.className != HD_PLUGIN_CLASS }
        .map { p ->
            TileSpec(
                p.name, glyph = "★", star = true,
                incompatible = p.enabled && !p.active && p.className !in pending,
            ) { if (p.hasConfig && p.configGroup != null) onOpenConfig(p) else onOpenList() }
        }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionLabel("Renderers")
        TileGrid(listOf(
            TileSpec("CPU", glyph = "▣", selected = renderer == "cpu") { onRendererSelect("cpu") },
            TileSpec("GPU", glyph = "▤", selected = renderer == "gpu") { onRendererSelect("gpu") },
            TileSpec("117HD", glyph = "✦", selected = renderer == "117hd") { onRendererSelect("117hd") },
        ))
        if (renderer != "cpu") {
            Text("Renderer change applies on next launch.", color = Color(0xFF9E9E9E), fontSize = 11.sp,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp))
        }

        if (pinnedTiles.isNotEmpty()) {
            SectionLabel("Pinned ★")
            TileGrid(pinnedTiles)
        }

        // Guard the header so the user never sees a bare "Panels" heading on the first frame (before the
        // LaunchedEffect bridge fetch returns panels/availability) or if there are genuinely no panels.
        val panelTiles = nativeTiles + renderedTiles
        if (panelTiles.isNotEmpty()) {
            SectionLabel("Panels")
            TileGrid(panelTiles)
        }

        SectionLabel("More")
        TileGrid(listOf(
            TileSpec("All plugins", glyph = "≡", onClick = onOpenList),
            TileSpec("Plugin Hub", glyph = "⬇", onClick = onOpenHub),
        ))
        Spacer(Modifier.heightIn(min = 12.dp))
    }
}

/** Section heading inside the launcher grid. */
@Composable
private fun SectionLabel(text: String) {
    Text(text, color = Color(0xFF80CBC4), fontSize = 12.sp,
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
}

/** One launcher tile: an icon (engine PNG) or a glyph, a label, and optional selected/star/incompatible state. */
private data class TileSpec(
    val label: String,
    val icon: Bitmap? = null,
    val glyph: String? = null,
    val selected: Boolean = false,
    val star: Boolean = false,
    val incompatible: Boolean = false,
    val onClick: () -> Unit,
)

/** Lay tiles out in fixed-width columns, padding the last row so widths stay even. */
@Composable
private fun TileGrid(tiles: List<TileSpec>, cols: Int = 3) {
    Column(Modifier.fillMaxWidth()) {
        tiles.chunked(cols).forEach { rowTiles ->
            Row(Modifier.fillMaxWidth()) {
                rowTiles.forEach { LaunchTile(it) }
                repeat(cols - rowTiles.size) { Spacer(Modifier.weight(1f).padding(4.dp)) }
            }
        }
    }
}

@Composable
private fun RowScope.LaunchTile(t: TileSpec) {
    Column(
        Modifier.weight(1f).padding(4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (t.selected) Color(0xFF24403D) else Color(0xFF1E1E1E))
            .border(
                width = if (t.selected) 1.5.dp else 0.5.dp,
                color = if (t.selected) Color(0xFF80CBC4) else Color(0x33FFFFFF),
                shape = RoundedCornerShape(10.dp),
            )
            .clickable { t.onClick() }
            .heightIn(min = 78.dp)
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            val ic = t.icon
            if (ic != null) {
                Image(ic.asImageBitmap(), null, Modifier.size(28.dp))
            } else {
                Text(t.glyph ?: t.label.take(1), color = Color(0xFFCCCCCC), fontSize = 20.sp)
            }
        }
        Spacer(Modifier.heightIn(min = 5.dp))
        Text(
            t.label,
            color = if (t.incompatible) Color(0xFF9E9E9E) else Color.White,
            fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        if (t.incompatible) Text("✕ can't start", color = Color(0xFFE57373), fontSize = 9.sp)
    }
}

/** The full searchable/toggleable plugin list — the long-tail config surface reached via search or "All plugins". */
@Composable
private fun PluginListBody(
    rows: List<PluginRow>,
    filter: String,
    pending: Set<String>,
    renderer: String,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onToggleRow: (PluginRow, Boolean) -> Unit,
    onTogglePin: (PluginRow) -> Unit,
    onRendererSelect: (String, Boolean) -> Unit,
    onOpenConfig: (PluginRow) -> Unit,
) {
    val q = filter.trim()
    val shown = rows.filter {
        q.isEmpty() || it.name.contains(q, ignoreCase = true) ||
            it.description.contains(q, ignoreCase = true) || it.tags.contains(q, ignoreCase = true)
    }
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        items(shown, key = { it.className }) { p ->
            val rendererMode = when (p.className) {
                GPU_PLUGIN_CLASS -> "gpu"
                HD_PLUGIN_CLASS -> "117hd"
                else -> null
            }
            if (rendererMode != null) {
                // Renderer row: switch state = the saved launch preference; toggle = save it.
                // Never marked incompatible (it is deliberately not running on this boot).
                PluginRowItem(
                    p.copy(enabled = renderer == rendererMode),
                    incompatible = false,
                    onToggle = { checked -> onRendererSelect(rendererMode, checked) },
                    onTogglePin = {},
                    onOpenConfig = if (p.hasConfig && p.configGroup != null) { { onOpenConfig(p) } } else null,
                )
                return@items
            }
            PluginRowItem(
                p,
                // enabled but the engine never started it (after the startup grace) = couldn't start.
                incompatible = p.enabled && !p.active && p.className !in pending,
                onToggle = { checked -> onToggleRow(p, checked) },
                onTogglePin = { onTogglePin(p) },
                onOpenConfig = if (p.hasConfig && p.configGroup != null) { { onOpenConfig(p) } } else null,
            )
            HorizontalDivider(color = Color(0x22FFFFFF))
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
        // Config-only fake-plugins (RuneLite core settings / Chat Color) have no enable state — desktop shows no
        // toggle, only a tap-to-configure row (the ⚙ above). Everything else gets the enable switch.
        if (!p.configOnly) Switch(checked = p.enabled, onCheckedChange = onToggle)
    }
}

private fun parsePlugins(json: String): List<PluginRow> = try {
    val arr = JSONArray(json)
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        PluginRow(
            className = o.getString("className"),
            name = o.optString("name", o.getString("className")),
            description = o.optString("description", ""),
            tags = o.optString("tags", ""),
            enabled = o.optBoolean("enabled"),
            active = o.optBoolean("active"),
            hasConfig = o.optBoolean("hasConfig"),
            configGroup = if (o.isNull("configGroup")) null else o.optString("configGroup", null),
            pinned = o.optBoolean("pinned"),
            configOnly = o.optBoolean("configOnly"),
        )
    }.sortedWith(compareByDescending<PluginRow> { it.pinned }.thenBy { it.name.lowercase() })
} catch (t: Throwable) {
    Log.e("MainActivity", "ANDROID_A4_UI_RED parse failed: $t")
    emptyList()
}
