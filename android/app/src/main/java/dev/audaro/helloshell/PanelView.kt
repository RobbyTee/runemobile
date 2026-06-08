package dev.audaro.helloshell

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import kotlin.coroutines.coroutineContext

/** One side-panel nav entry from MobileUiBridge.listPanels(). */
data class PanelEntry(val index: Int, val tooltip: String, val icon: Bitmap?)

private class PanelSnap(val w: Int, val h: Int, val seq: Int, val px: IntArray?)

/** A5 Tier-1: the list of plugin side-panels (icon + tooltip). Tap one to open the rendered-image viewer. */
@Composable
fun PanelList(onOpen: (Int, String) -> Unit, onBack: () -> Unit) {
    var panels by remember { mutableStateOf<List<PanelEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        val json = withContext(Dispatchers.IO) { JvmLauncher.onBridge { JvmLauncher.listPanels() }.get() }
        panels = parsePanels(json)
        loading = false
        Log.i("MainActivity", "ANDROID_A5_PANELLIST count=${panels.size}")
    }
    Surface(color = Color(0xF2121212), modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ Back") }
                Text("Panels", color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
            }
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(panels, key = { it.index }) { p ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpen(p.index, p.tooltip) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            p.icon?.let { Image(it.asImageBitmap(), null, Modifier.size(28.dp)); Spacer(Modifier.width(12.dp)) }
                            Text(p.tooltip, color = Color.White)
                        }
                        HorizontalDivider(color = Color(0x22FFFFFF))
                    }
                }
            }
        }
    }
}

/**
 * A5 Tier-1: the rendered-image panel viewer. Opens the panel (off-screen under CACIO), polls its supersampled
 * ARGB image into a Bitmap (refreshing on frame-seq change for live updates), shows it fit-to-width with
 * vertical scroll, and maps taps back to buffer coords via panelClick. Closes the panel on dispose.
 */
@Composable
fun PanelView(index: Int, tooltip: String, onBack: () -> Unit) {
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var pw by remember { mutableStateOf(0) }
    var viewW by remember { mutableStateOf(1) }
    var logged by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()

    DisposableEffect(index) {
        onDispose { JvmLauncher.onBridge { JvmLauncher.closePanel() } }
    }
    LaunchedEffect(index) {
        JvmLauncher.onBridge { JvmLauncher.openPanel(index) }
        var lastSeq = -1
        while (coroutineContext.isActive) {
            val s = withContext(Dispatchers.IO) {
                JvmLauncher.onBridge {
                    PanelSnap(JvmLauncher.panelWidth(), JvmLauncher.panelHeight(), JvmLauncher.panelFrameSeq(), JvmLauncher.panelPixels())
                }.get()
            }
            if (s.seq != lastSeq && s.w > 0 && s.h > 0 && s.px != null && s.px.size >= s.w * s.h) {
                bmp = Bitmap.createBitmap(s.px, 0, s.w, s.w, s.h, Bitmap.Config.ARGB_8888)
                pw = s.w
                lastSeq = s.seq
                if (!logged) { Log.i("MainActivity", "ANDROID_A5_UI_GREEN panel='$tooltip' ${s.w}x${s.h}"); logged = true }
            }
            delay(400)
        }
    }
    Surface(color = Color(0xF2121212), modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                TextButton(onClick = onBack) { Text("‹ Panels") }
                Text(tooltip, color = Color.White, modifier = Modifier.weight(1f))
            }
            val img = bmp
            if (img == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                Box(
                    Modifier.fillMaxSize().verticalScroll(scroll)
                        .onGloballyPositioned { viewW = it.size.width.coerceAtLeast(1) }
                        .pointerInput(pw, viewW) {
                            detectTapGestures { off ->
                                if (pw > 0 && viewW > 0) {
                                    // fit-width => uniform scale viewW/pw; add the scroll offset for buffer-y.
                                    val bx = (off.x * pw / viewW)
                                    val by = ((off.y + scroll.value) * pw / viewW)
                                    JvmLauncher.onBridge { JvmLauncher.panelClick(bx.toInt(), by.toInt()) }
                                }
                            }
                        },
                ) {
                    Image(img.asImageBitmap(), tooltip, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                }
            }
        }
    }
}

private fun parsePanels(json: String): List<PanelEntry> = try {
    val arr = JSONArray(json)
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        val icon = o.optString("iconPng", "").takeIf { it.isNotEmpty() }?.let {
            val bytes = Base64.decode(it, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
        PanelEntry(o.optInt("index", i), o.optString("tooltip", "panel $i"), icon)
    }
} catch (t: Throwable) {
    Log.e("MainActivity", "ANDROID_A5_UI_RED parse: $t")
    emptyList()
}
