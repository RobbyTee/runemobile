package dev.audaro.runemobile

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
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

    /** HudVeneerBridge.TAB_SPRITES_VERSION this build draws. A set cached by an older build (the pre-2018 glyphs,
     *  no door or arrow) is older than this and is not applied. */
    const val VERSION = 2

    /** One decoded payload ([o] is its JSON object). */
    class Set(
        o: JSONObject,
    ) {
        val stoneInactive = bmp(o.optJSONObject("stoneInactive"))
        val stoneActive = bmp(o.optJSONObject("stoneActive"))
        val icons =
            arrayOfNulls<Bitmap>(14).also { icons ->
                o.optJSONArray("icons")?.let { arr ->
                    for (i in 0 until minOf(14, arr.length())) icons[i] = bmp(arr.optJSONObject(i))
                }
            }
        val chatbtn = bmp(o.optJSONObject("chatbtn"))
        val chatbtnSelected = bmp(o.optJSONObject("chatbtnSelected"))
        val chatbtnNew = bmp(o.optJSONObject("chatbtnNew"))

        /** The logout door's stones (601's 58x40 STONE10_BACKGROUND / STONE10); the door glyph is icons[10]. */
        val doorStone = bmp(o.optJSONObject("doorStone"))
        val doorStoneActive = bmp(o.optJSONObject("doorStoneActive"))

        /** The expand stone's arrow while both columns show / while collapsed. */
        val showLess = bmp(o.optJSONObject("showLess"))
        val showMore = bmp(o.optJSONObject("showMore"))

        /** The bridge captured the full grid set (not a deadline-partial) — only persist these to the boot cache. */
        val complete = o.optBoolean("complete", false)

        /** The payload's art version (see [VERSION]). */
        val version = o.optInt("v", 1)

        /** Enough to drive the grid: both stones + at least one glyph. */
        fun usableForGrid() = stoneInactive != null && stoneActive != null && icons.any { it != null }

        fun usableForChat() = chatbtn != null
    }

    fun parse(json: String?): Set? {
        if (json.isNullOrBlank()) return null
        return try {
            val set = Set(JSONObject(json))
            if (!set.usableForGrid() && !set.usableForChat()) {
                // non-empty payload that decoded to nothing usable — flag it (corrupt cache / decode mismatch)
                Log.w("TabSprites", "payload parsed but no usable bitmaps (${json.length} bytes) — check ARGB/Base64 decode")
            }
            set
        } catch (e: Exception) {
            Log.w("TabSprites", "failed to parse tab-sprite payload (${json.length} bytes): ${e.message}")
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
            // NO_WRAP matches the bridge's java.util.Base64 basic encoder (unwrapped, padded).
            val bytes = Base64.decode(px, Base64.NO_WRAP)
            if (bytes.size != w * h * 4) {
                Log.w("TabSprites", "sprite byte-count mismatch: got ${bytes.size}, expected ${w * h * 4} (${w}x$h)")
                return null
            }
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

    fun cache(
        filesDir: File,
        json: String,
    ) {
        try {
            File(filesDir, CACHE_NAME).writeText(json)
        } catch (e: Exception) {
            // best-effort — a missed cache just means the next boot waits for the post-login capture
        }
    }

    fun cached(filesDir: File): String? =
        try {
            File(filesDir, CACHE_NAME).let { if (it.exists()) it.readText() else null }
        } catch (e: Exception) {
            null
        }
}
