package dev.audaro.helloshell

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Maps a pushed tokens.json -> the JX_* environment the OSRS gamepack reads when "launched from the Jagex
 * launcher", mirroring the proven iOS loadJagexTokens (JVMLauncher.m). tokens.json is a gitignored secret
 * (throwaway account), pushed into the app's files dir at test time, never bundled into the build.
 *
 * The JX_* env MUST be set before JNI_CreateJavaVM (HotSpot snapshots the environment at startup), so
 * [loadAndArm] runs on the boot thread before [JvmLauncher.startJvm] and sets each var via the native setenv
 * bridge. Returns true iff a usable session (session_id + chosen_account_id) was found, so autologin only arms
 * when we can authenticate. Logs redacted prefixes only — never the secret token values.
 *
 * Push at test time (debug build), e.g.:
 *   adb push tokens.json /sdcard/Android/data/dev.audaro.helloshell/files/tokens.json
 */
object JagexTokens {

    private const val TAG = "JagexTokens"

    /** Locate tokens.json: internal filesDir first (where the JRE lives), then the adb-pushable external dir. */
    private fun locate(ctx: Context): File? {
        val candidates = listOfNotNull(
            File(ctx.filesDir, "tokens.json"),
            ctx.getExternalFilesDir(null)?.let { File(it, "tokens.json") },
        )
        return candidates.firstOrNull { it.exists() && it.length() > 0 }
    }

    /** Parse tokens.json and set JX_* env. Returns true iff autologin was armed. */
    fun loadAndArm(ctx: Context): Boolean {
        val file = locate(ctx)
        if (file == null) {
            Log.i(TAG, "no tokens.json (filesDir or external files dir) — autologin DISABLED (login-screen render only)")
            return false
        }
        val t = try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            Log.w(TAG, "tokens.json parse failed: ${e.message} — autologin DISABLED")
            return false
        }
        val session = t.optString("session_id", "")
        val character = t.optString("chosen_account_id", "")
        val display = t.optString("chosen_display_name", "")
        val access = t.optString("access_token", "")
        val refresh = t.optString("refresh_token", "")
        if (session.isEmpty() || character.isEmpty()) {
            Log.i(TAG, "tokens.json missing session_id/chosen_account_id — autologin DISABLED")
            return false
        }

        JvmLauncher.setEnv("JX_SESSION_ID", session)
        JvmLauncher.setEnv("JX_CHARACTER_ID", character)
        if (display.isNotEmpty()) JvmLauncher.setEnv("JX_DISPLAY_NAME", display)
        if (access.isNotEmpty()) JvmLauncher.setEnv("JX_ACCESS_TOKEN", access)
        if (refresh.isNotEmpty()) JvmLauncher.setEnv("JX_REFRESH_TOKEN", refresh)

        val sessionPrefix = if (session.length >= 6) session.substring(0, 6) else session
        Log.i(
            TAG,
            "JX_* set from ${file.parent}: displayName='$display' characterId=$character session=$sessionPrefix… " +
                "access=${if (access.isNotEmpty()) "set" else "empty"} refresh=${if (refresh.isNotEmpty()) "set" else "empty"}",
        )
        return true
    }

    /**
     * The autologin VM opts armed once JX_* is set, mirroring iOS JVMLauncher.m. Coordinates are the fixed
     * 765x503 login layout (EULA Accept centre; Play Now centre, desktop-proven); device-tunable via the same
     * runelite.headless.autologin.* properties if a real device's login frame differs.
     */
    val autologinOpts: Array<String> = arrayOf(
        "-Drunelite.headless.autologin=true",
        "-Drunelite.headless.autologin.eula=true",
        "-Drunelite.headless.autologin.eula.point=302,312",
        "-Drunelite.headless.autologin.eula.loginIndex=12",
        "-Drunelite.headless.autologin.point=382,248",
        "-Drunelite.headless.autologin.world=301",
        "-Drunelite.headless.autologin.worldAddress=oldschool1.runescape.com",
    )
}
