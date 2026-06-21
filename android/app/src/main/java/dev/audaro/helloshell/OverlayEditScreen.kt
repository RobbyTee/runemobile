package dev.audaro.helloshell

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/** One movable overlay from OverlayBridge.getOverlaysJson. bx/by/bw/bh = current canvas-pixel bounds. */
data class OverlayRow(
    val name: String, val position: String, val movable: Boolean, val snappable: Boolean,
    val bx: Int, val by: Int, val bw: Int, val bh: Int,
)

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
 * Finding 15 — overlay edit mode: snap-to-corner + reset, plus free pixel-drag (the line-62 follow-up). The
 * desktop affordance (Alt-hold + free mouse drag) has no clean touch analogue over the live game; instead each
 * movable overlay gets a ✋ toggle that reveals a canvas-proportioned drag-pad — dragging in the pad places the
 * overlay at the mapped canvas pixel via OverlayBridge.setOverlayPreferredLocation, and the engine re-renders it
 * there (detached/free) on the next frame through the CPU blit. Snap corners + reset remain for the common case.
 */
@Composable
fun OverlayEditScreen(onBack: () -> Unit) {
    var overlays by remember { mutableStateOf<List<OverlayRow>>(emptyList()) }
    var firstLoad by remember { mutableStateOf(true) }
    var logged by remember { mutableStateOf(false) }
    var freeMoveFor by remember { mutableStateOf<String?>(null) }   // which overlay's drag-pad is open (one at a time)

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
            Text("Snap to a corner, or tap ✋ to drag an overlay anywhere on the screen.", color = Color(0xFF9E9E9E),
                fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
            when {
                firstLoad -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                overlays.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No movable overlays on screen.", color = Color(0xFF9E9E9E), fontSize = 14.sp)
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(overlays, key = { it.name }) { o ->
                        OverlayRowItem(o,
                            freeMove = freeMoveFor == o.name,
                            onToggleFreeMove = { freeMoveFor = if (freeMoveFor == o.name) null else o.name },
                            onSnap = { pos -> JvmLauncher.onBridgeTouch { JvmLauncher.overlaySnap(o.name, pos); JvmLauncher.flushConfig() } },
                            onReset = { JvmLauncher.onBridgeTouch { JvmLauncher.overlayReset(o.name); JvmLauncher.flushConfig() } },
                            onFreeDrop = { x, y -> Log.i(OE_TAG, "fix-overlay: free-drag ${o.name} -> ($x,$y)"); JvmLauncher.onBridgeTouch { JvmLauncher.overlaySetLocation(o.name, x, y); JvmLauncher.flushConfig() } })
                        HorizontalDivider(color = Color(0x22FFFFFF))
                    }
                }
            }
        }
    }
}

@Composable
private fun OverlayRowItem(
    o: OverlayRow, freeMove: Boolean, onToggleFreeMove: () -> Unit,
    onSnap: (String) -> Unit, onReset: () -> Unit, onFreeDrop: (Int, Int) -> Unit,
) {
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
            // Free pixel-drag toggle (works for any movable overlay, snappable or not).
            FilledTonalButton(
                onClick = onToggleFreeMove,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                modifier = Modifier.size(40.dp),
            ) { Text("✋", fontSize = 16.sp, color = if (freeMove) Color(0xFF66BB6A) else Color.White) }
            OutlinedButton(onClick = onReset, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp)) {
                Text("Reset", fontSize = 12.sp)
            }
        }
        if (freeMove) {
            Text("Drag on the pad to position (pad = the game screen).", color = Color(0xFF777777),
                fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            OverlayDragPad(o, onFreeDrop)
        }
    }
}

/** A canvas-proportioned pad: a drag maps to the overlay's top-left in canvas pixels (committed on release). */
@Composable
private fun OverlayDragPad(o: OverlayRow, onDrop: (Int, Int) -> Unit) {
    val cw = MainActivity.canvasW.takeIf { it > 0 } ?: 1200
    val ch = MainActivity.canvasH.takeIf { it > 0 } ?: 540
    val maxFx = (cw - o.bw).coerceAtLeast(0).toFloat() / cw   // keep the overlay's footprint on-canvas
    val maxFy = (ch - o.bh).coerceAtLeast(0).toFloat() / ch
    var fracX by remember(o.name) { mutableStateOf((o.bx.toFloat() / cw).coerceIn(0f, maxFx)) }
    var fracY by remember(o.name) { mutableStateOf((o.by.toFloat() / ch).coerceIn(0f, maxFy)) }
    var padW by remember(o.name) { mutableStateOf(1f) }
    var padH by remember(o.name) { mutableStateOf(1f) }
    val density = LocalDensity.current

    Box(
        Modifier.fillMaxWidth().aspectRatio(cw.toFloat() / ch).padding(top = 4.dp)
            .border(1.dp, Color(0x55FFFFFF)).background(Color(0x14FFFFFF))
            .onSizeChanged { padW = it.width.toFloat().coerceAtLeast(1f); padH = it.height.toFloat().coerceAtLeast(1f) }
            .pointerInput(o.name) {
                detectDragGestures(
                    onDragStart = { off ->
                        fracX = (off.x / padW).coerceIn(0f, maxFx)
                        fracY = (off.y / padH).coerceIn(0f, maxFy)
                    },
                    onDragEnd = {
                        onDrop(
                            (fracX * cw).roundToInt().coerceIn(0, (cw - o.bw).coerceAtLeast(0)),
                            (fracY * ch).roundToInt().coerceIn(0, (ch - o.bh).coerceAtLeast(0)),
                        )
                    },
                ) { change, drag ->
                    change.consume()
                    fracX = (fracX + drag.x / padW).coerceIn(0f, maxFx)
                    fracY = (fracY + drag.y / padH).coerceIn(0f, maxFy)
                }
            },
    ) {
        // The overlay's footprint as a draggable thumb (min size so a tiny overlay is still grabbable).
        val thumbW = with(density) { (o.bw.toFloat() / cw * padW).coerceAtLeast(14.dp.toPx()).toDp() }
        val thumbH = with(density) { (o.bh.toFloat() / ch * padH).coerceAtLeast(10.dp.toPx()).toDp() }
        Box(
            Modifier.offset { IntOffset((fracX * padW).roundToInt(), (fracY * padH).roundToInt()) }
                .size(thumbW, thumbH).background(Color(0xCCFFD54F)).border(1.dp, Color(0xFF8C8066)),
        )
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
            val b = ov.optJSONArray("bounds")
            OverlayRow(
                name = ov.optString("name", "?"),
                position = ov.optString("position", ""),
                movable = ov.optBoolean("movable"),
                snappable = ov.optBoolean("snappable"),
                bx = b?.optInt(0) ?: 0, by = b?.optInt(1) ?: 0,
                bw = b?.optInt(2) ?: 0, bh = b?.optInt(3) ?: 0,
            )
        }.filter { it.movable }.sortedBy { it.name.lowercase() }
    }
} catch (t: Throwable) {
    Log.e(OE_TAG, "ANDROID_OVERLAY_EDIT_UI_RED parse: $t"); emptyList()
}
