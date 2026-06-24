package dev.audaro.helloshell

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONObject
import java.io.File

/**
 * Decodes the runtime tab/stone/chat sprite payload that the engine bridge (HudVeneerBridge.tabSprites) loads
 * from the live OSRS cache — so the native chrome composites the REAL art with nothing committed to the repo,
 * exactly how the desktop client renders its tab stones. Each sprite arrives as {w,h,px} where px is Base64 of
 * raw big-endian ARGB bytes (no PNG/ImageIO dependency). A one-shot JSON copy is cached to filesDir so later
 * boots apply it before the post-login capture lands.
 */
object TabSprites {
    private const val CACHE_NAME = "tabsprites.json"

    class Set(
        val stoneInactive: Bitmap?,
        val stoneActive: Bitmap?,
        val icons: Array<Bitmap?>,
        val chatbtn: Bitmap?,
        val chatbtnSelected: Bitmap?,
        val chatbtnNew: Bitmap?,
    ) {
        /** Enough to drive the grid: both stones + at least one glyph. */
        fun usableForGrid() = stoneInactive != null && stoneActive != null && icons.any { it != null }
        fun usableForChat() = chatbtn != null
    }

    fun parse(json: String?): Set? {
        if (json.isNullOrBlank()) return null
        return try {
            val o = JSONObject(json)
            val icons = arrayOfNulls<Bitmap>(14)
            o.optJSONArray("icons")?.let { arr ->
                for (i in 0 until minOf(14, arr.length())) icons[i] = bmp(arr.optJSONObject(i))
            }
            Set(
                bmp(o.optJSONObject("stoneInactive")),
                bmp(o.optJSONObject("stoneActive")),
                icons,
                bmp(o.optJSONObject("chatbtn")),
                bmp(o.optJSONObject("chatbtnSelected")),
                bmp(o.optJSONObject("chatbtnNew")),
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun bmp(o: JSONObject?): Bitmap? {
        if (o == null) return null
        val w = o.optInt("w", 0)
        val h = o.optInt("h", 0)
        val px = o.optString("px", "")
        if (w <= 0 || h <= 0 || px.isEmpty()) return null
        return try {
            val bytes = Base64.decode(px, Base64.DEFAULT)
            if (bytes.size != w * h * 4) return null
            val argb = IntArray(w * h)
            for (i in argb.indices) {
                val a = bytes[i * 4].toInt() and 0xFF
                val r = bytes[i * 4 + 1].toInt() and 0xFF
                val g = bytes[i * 4 + 2].toInt() and 0xFF
                val b = bytes[i * 4 + 3].toInt() and 0xFF
                argb[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) {
            null
        }
    }

    fun cache(filesDir: File, json: String) {
        try {
            File(filesDir, CACHE_NAME).writeText(json)
        } catch (e: Exception) {
            // best-effort — a missed cache just means the next boot waits for the post-login capture
        }
    }

    fun cached(filesDir: File): String? = try {
        File(filesDir, CACHE_NAME).let { if (it.exists()) it.readText() else null }
    } catch (e: Exception) {
        null
    }
}
