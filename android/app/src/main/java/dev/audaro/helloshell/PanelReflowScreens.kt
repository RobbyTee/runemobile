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
import androidx.compose.material3.LinearProgressIndicator
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

private const val PR_TAG = "MainActivity"
private val PR_PANEL_BG = Color(0xF2121212)

// Shared compact value formatter (123 / 12.3K / 4.5M) for GE prices + loot values.
private fun gp(v: Long): String = when {
    v >= 10_000_000 -> "${v / 1_000_000}M"
    v >= 1_000_000 -> "%.1fM".format(v / 1_000_000.0)
    v >= 100_000 -> "${v / 1000}K"
    v >= 1000 -> "%.1fK".format(v / 1000.0)
    else -> v.toString()
}

// ====================================================================================================
// Finding 14 — Loot Tracker native reflow (read-only). getLootJson = {available, sources:[{name,subTitle,
// type,kills,totalValue,items:[{id,name,qty,ge,ha}]}]}.
// ====================================================================================================
data class LootItem(val name: String, val qty: Int, val ge: Long)
data class LootSource(val name: String, val subTitle: String, val kills: Int, val totalValue: Long, val items: List<LootItem>)

@Composable
fun LootTrackerScreen(onBack: () -> Unit) {
    var sources by remember { mutableStateOf<List<LootSource>>(emptyList()) }
    var firstLoad by remember { mutableStateOf(true) }
    var logged by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        JvmLauncher.onBridge { JvmLauncher.lootSetUiOpen(true) }
        onDispose { JvmLauncher.onBridge { JvmLauncher.lootSetUiOpen(false) } }
    }
    LaunchedEffect(Unit) {
        while (coroutineContext.isActive) {
            val json = withContext(Dispatchers.IO) { JvmLauncher.onBridge { JvmLauncher.lootJson() }.get() }
            sources = parseLoot(json)
            firstLoad = false
            if (!logged) { Log.i(PR_TAG, "ANDROID_A5_TIER2_LOOT_UI_GREEN sources=${sources.size}"); logged = true }
            delay(1500)
        }
    }

    Surface(color = PR_PANEL_BG, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ Back") }
                Text("Loot Tracker", color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
            }
            when {
                firstLoad -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                sources.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No loot tracked this session.", color = Color(0xFF9E9E9E), fontSize = 14.sp)
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(sources, key = { it.name + it.subTitle }) { s ->
                        LootSourceItem(s); HorizontalDivider(color = Color(0x22FFFFFF))
                    }
                }
            }
        }
    }
}

@Composable
private fun LootSourceItem(s: LootSource) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.name, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (s.kills > 0) Text("×${s.kills}", color = Color(0xFFB0BEC5), fontSize = 12.sp)
            Text("  ${gp(s.totalValue)} gp", color = Color(0xFFFFD54F), fontSize = 12.sp)
        }
        s.items.forEach { it ->
            Row(Modifier.fillMaxWidth().padding(start = 6.dp, top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (it.qty > 1) "${it.name} ×${it.qty}" else it.name, color = Color(0xFFCFD8DC), fontSize = 12.sp,
                    modifier = Modifier.weight(1f))
                if (it.ge > 0) Text("${gp(it.ge)} gp", color = Color(0xFF81C784), fontSize = 12.sp)
            }
        }
    }
}

private fun parseLoot(json: String): List<LootSource> = try {
    val o = JSONObject(json)
    if (!o.optBoolean("available", false)) emptyList()
    else {
        val arr = o.optJSONArray("sources") ?: return emptyList()
        (0 until arr.length()).map { i ->
            val s = arr.getJSONObject(i)
            val items = s.optJSONArray("items")
            LootSource(
                name = s.optString("name", "?"),
                subTitle = s.optString("subTitle", ""),
                kills = s.optInt("kills"),
                totalValue = s.optLong("totalValue"),
                items = if (items == null) emptyList() else (0 until items.length()).map { j ->
                    val it = items.getJSONObject(j)
                    LootItem(it.optString("name", "?"), it.optInt("qty"), it.optLong("ge"))
                },
            )
        }
    }
} catch (t: Throwable) {
    Log.e(PR_TAG, "ANDROID_A5_TIER2_LOOT_UI_RED parse: $t"); emptyList()
}

// ====================================================================================================
// Finding 14 — Grand Exchange native reflow (read-only). getGeJson = {available, offers:[{slot,state,itemId,
// name,qty,total,price,spent,progress}], history:[{buy,itemId,qty,price,time}]}.
// ====================================================================================================
data class GeOffer(val slot: Int, val state: String, val name: String, val qty: Int, val total: Int,
                   val price: Int, val progress: Double)

@Composable
fun GrandExchangeScreen(onBack: () -> Unit) {
    var offers by remember { mutableStateOf<List<GeOffer>>(emptyList()) }
    var firstLoad by remember { mutableStateOf(true) }
    var logged by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        JvmLauncher.onBridge { JvmLauncher.geSetUiOpen(true) }
        onDispose { JvmLauncher.onBridge { JvmLauncher.geSetUiOpen(false) } }
    }
    LaunchedEffect(Unit) {
        while (coroutineContext.isActive) {
            val json = withContext(Dispatchers.IO) { JvmLauncher.onBridge { JvmLauncher.geJson() }.get() }
            offers = parseGe(json)
            firstLoad = false
            if (!logged) { Log.i(PR_TAG, "ANDROID_A5_TIER2_GE_UI_GREEN offers=${offers.size}"); logged = true }
            delay(1500)
        }
    }

    Surface(color = PR_PANEL_BG, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ Back") }
                Text("Grand Exchange", color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
                if (offers.isNotEmpty()) Text("${offers.size}/8", color = Color(0xFFFFD54F), fontSize = 13.sp)
            }
            when {
                firstLoad -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                offers.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No active Grand Exchange offers.", color = Color(0xFF9E9E9E), fontSize = 14.sp)
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(offers, key = { it.slot }) { ofr ->
                        GeOfferItem(ofr); HorizontalDivider(color = Color(0x22FFFFFF))
                    }
                }
            }
        }
    }
}

@Composable
private fun GeOfferItem(o: GeOffer) {
    val buying = o.state.contains("BUY", ignoreCase = true) || o.state == "BUYING" || o.state == "BOUGHT"
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (buying) "Buy" else "Sell", color = if (buying) Color(0xFF64B5F6) else Color(0xFFFFB74D),
                fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text("  ${o.name}", color = Color.White, modifier = Modifier.weight(1f))
            Text(prettyState(o.state), color = Color(0xFF9E9E9E), fontSize = 11.sp)
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${o.qty}/${o.total}", color = Color(0xFFBDBDBD), fontSize = 12.sp)
            Text("@ ${gp(o.price.toLong())} gp", color = Color(0xFF81C784), fontSize = 12.sp)
        }
        if (o.progress in 0.0..1.0)
            LinearProgressIndicator(
                progress = { o.progress.toFloat() },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                color = if (buying) Color(0xFF42A5F5) else Color(0xFFFFA726), trackColor = Color(0x33FFFFFF),
            )
    }
}

private fun prettyState(s: String): String =
    s.split('_').joinToString(" ") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }

private fun parseGe(json: String): List<GeOffer> = try {
    val o = JSONObject(json)
    if (!o.optBoolean("available", false)) emptyList()
    else {
        val arr = o.optJSONArray("offers") ?: return emptyList()
        (0 until arr.length()).map { i ->
            val g = arr.getJSONObject(i)
            GeOffer(
                slot = g.optInt("slot"),
                state = g.optString("state", ""),
                name = g.optString("name", "?"),
                qty = g.optInt("qty"),
                total = g.optInt("total"),
                price = g.optInt("price"),
                progress = g.optDouble("progress", 0.0),
            )
        }
    }
} catch (t: Throwable) {
    Log.e(PR_TAG, "ANDROID_A5_TIER2_GE_UI_RED parse: $t"); emptyList()
}
