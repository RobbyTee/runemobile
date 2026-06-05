package dev.audaro.helloshell

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Unpacks the embedded JRE tree + jars from APK assets to filesDir on first launch.
 *
 * Staging is ASSETS-ONLY: the whole bionic JRE — including its native libs under jre/lib/ (libjvm.so and its
 * siblings) — plus the client/probe jars ship as APK assets and are unpacked here. The native host then
 * dlopens libjvm.so by absolute path out of this filesDir tree (see MainActivity.bootJvm and
 * jvm_bootstrap.cpp's preloadJreLibs). Only our own JNI lib (libhelloshell.so) lives in jniLibs and is loaded
 * via System.loadLibrary. NB: dlopen from writable app storage is nominally restricted for API 29+ targets —
 * it works on the emulator; this must be confirmed on a real device (PORT-PHASING §5 Q11), moving the JRE .so
 * set into a native-lib dir if a hardened device rejects it.
 */
object AssetInstaller {

    private const val TAG = "AssetInstaller"

    // Re-unpack key = "<BASE>-<runtime.tag>". BASE covers the embedded JRE / native libs (bump by hand
    // when those change — rare). The runtime.tag suffix is the staged-jar content hash that
    // tools/stage-mobile-runtime.sh writes into the bundle, so a jar update invalidates old installs
    // automatically — no hand-typed bump for the common case.
    private const val BASE = "v5"

    /** Returns the JAVA_HOME path (filesDir/jvm/jre). */
    fun ensureInstalled(ctx: Context): File {
        val root = File(ctx.filesDir, "jvm")
        val want = "$BASE-${bundledTag(ctx)}"
        val marker = File(root, ".installed-tag")
        val have = if (marker.exists()) marker.readText().trim() else null
        if (have != want) {
            Log.i(TAG, "runtime changed ($have -> $want): re-unpacking embedded JVM assets to $root")
            root.deleteRecursively()   // wipe the stale tree so old and new jars never mix
            copyAssetDir(ctx, "jvm", root)
            marker.writeText(want)
        }
        return File(root, "jre")
    }

    /** The staged content tag shipped in the bundle (jvm/runtime.tag); "untagged" if staging didn't write it. */
    private fun bundledTag(ctx: Context): String =
        runCatching { ctx.assets.open("jvm/runtime.tag").bufferedReader().use { it.readText().trim() } }
            .getOrElse {
                Log.w(TAG, "jvm/runtime.tag missing from bundle — run tools/stage-mobile-runtime.sh")
                "untagged"
            }

    private fun copyAssetDir(ctx: Context, assetPath: String, dest: File) {
        val children = ctx.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            // leaf: a file
            dest.parentFile?.mkdirs()
            ctx.assets.open(assetPath).use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
            return
        }
        dest.mkdirs()
        for (child in children) {
            copyAssetDir(ctx, "$assetPath/$child", File(dest, child))
        }
    }
}
