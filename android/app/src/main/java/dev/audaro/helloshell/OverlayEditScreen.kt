package dev.audaro.helloshell

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

/** One movable overlay from OverlayBridge.getOverlaysJson. */
data class OverlayRow(val name: String, val position: String, val movable: Boolean, val snappable: Boolean)

// Snap targets the desktop renderer uses (label → OverlayPosition name): the five corners plus the two extra
// edge anchors the engine supports — CANVAS_TOP_RIGHT (the full-canvas top-right, above the relocated minimap)
// and ABOVE_CHATBOX_RIGHT (just above the chatbox, right side). The bridge already accepts every OverlayPosition
// name (parsePosition → valueOf) + prettyCorner already labels these two; they only needed offering here.
private val SNAP_CORNERS = listOf(
    "↖" to "TOP_LEFT", "↑" to "TOP_CENTER", "↗" to "TOP_RIGHT",
    "↙" to "BOTTOM_LEFT", "↘" to "BOTTOM_RIGHT",
    "◥" to "CANVAS_TOP_RIGHT", "◢" to "ABOVE_CHATBOX_RIGHT",
)

private const val OE_TAG = "MainActivity"
private val OE_PANEL_BG = Color(0xF2121212)

/**
 * Finding 15 — overlay edit mode (first cut: snap-to-corner + reset). The desktop affordance (Alt-hold + free
 * mouse drag + hover-cursor) has no clean touch analogue; on a small phone the signature need is just moving an
 * overlay off the relocated HUD, which the five engine snap corners cover. This Compose screen lists every
 * movable overlay and snaps/resets it via OverlayBridge — the engine re-renders it at the new corner on the
 * next frame, visible through the CPU blit. (Free pixel-drag via setOverlayPreferredLocation is a follow-up.)
 */
@Composable
fun OverlayEditScreen(onBack: () -> Unit) {
    var overlays by remember { mutableStateOf<List<OverlayRow>>(emptyList()) }
    var firstLoad by remember { mutableStateOf(true) }
    var logged by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        JvmLauncher.onBridge { JvmLauncher.overlaySetUiOpen(true) }
        onDispose { JvmLauncher.onBridge { JvmLauncher.overlaySetUiOpen(false) } }
    }
    LaunchedEffect(Unit) {
        while (coroutineContext.isActive) {
            val json = withContext(Dispatchers.IO) { JvmLauncher.onBridge { JvmLauncher.overlaysJson() }.get() }
            overlays = parseOverlays(json)
            firstLoad = false
            if (!logged) { Log.i(OE_TAG, "ANDROID_OVERLAY_EDIT_UI_GREEN overlays=${overlays.size}"); logged = true }
            delay(1200)
        }
    }

    Surface(color = OE_PANEL_BG, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ Back") }
                Text("Overlay Layout", color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
            }
            Text("Snap an overlay to a corner to move it off the HUD.", color = Color(0xFF9E9E9E),
                fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
            when {
                firstLoad -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                overlays.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No movable overlays on screen.", color = Color(0xFF9E9E9E), fontSize = 14.sp)
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(overlays, key = { it.name }) { o ->
                        OverlayRowItem(o,
                            onSnap = { pos -> JvmLauncher.onBridge { JvmLauncher.overlaySnap(o.name, pos); JvmLauncher.flushConfig() } },
                            onReset = { JvmLauncher.onBridge { JvmLauncher.overlayReset(o.name); JvmLauncher.flushConfig() } })
                        HorizontalDivider(color = Color(0x22FFFFFF))
                    }
                }
            }
        }
    }
}

@Composable
private fun OverlayRowItem(o: OverlayRow, onSnap: (String) -> Unit, onReset: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(prettyOverlayName(o.name), color = Color.White, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f))
            Text(prettyCorner(o.position), color = Color(0xFFFFD54F), fontSize = 11.sp)
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (o.snappable) {
                SNAP_CORNERS.forEach { (label, pos) ->
                    val active = o.position == pos
                    FilledTonalButton(
                        onClick = { onSnap(pos) },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                        modifier = Modifier.size(40.dp),
                    ) { Text(label, fontSize = 16.sp, color = if (active) Color(0xFF66BB6A) else Color.White) }
                }
            } else {
                Text("not snappable", color = Color(0xFF777777), fontSize = 11.sp, modifier = Modifier.weight(1f))
            }
            OutlinedButton(onClick = onReset, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp)) {
                Text("Reset", fontSize = 12.sp)
            }
        }
    }
}

private fun prettyCorner(pos: String): String = when (pos) {
    "TOP_LEFT" -> "Top-left"; "TOP_CENTER" -> "Top-center"; "TOP_RIGHT" -> "Top-right"
    "BOTTOM_LEFT" -> "Bottom-left"; "BOTTOM_RIGHT" -> "Bottom-right"
    "ABOVE_CHATBOX_RIGHT" -> "Above chat"; "CANVAS_TOP_RIGHT" -> "Canvas top-right"
    "DYNAMIC", "DETACHED" -> "Free"
    else -> pos.split('_').joinToString(" ") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
}

// Strip a trailing "Overlay"/"InfoBoxOverlay" and space out the camel-cased class name for display.
private fun prettyOverlayName(n: String): String {
    val base = n.removeSuffix("Overlay").ifEmpty { n }
    return base.replace(Regex("([a-z])([A-Z])"), "$1 $2").trim().ifEmpty { n }
}

private fun parseOverlays(json: String): List<OverlayRow> = try {
    val o = JSONObject(json)
    if (!o.optBoolean("available", false)) emptyList()
    else {
        val arr = o.optJSONArray("overlays") ?: return emptyList()
        (0 until arr.length()).map { i ->
            val ov = arr.getJSONObject(i)
            OverlayRow(
                name = ov.optString("name", "?"),
                position = ov.optString("position", ""),
                movable = ov.optBoolean("movable"),
                snappable = ov.optBoolean("snappable"),
            )
        }.filter { it.movable }.sortedBy { it.name.lowercase() }
    }
} catch (t: Throwable) {
    Log.e(OE_TAG, "ANDROID_OVERLAY_EDIT_UI_RED parse: $t"); emptyList()
}
