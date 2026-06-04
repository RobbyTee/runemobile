package dev.audaro.helloshell

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Unpacks the embedded JRE tree + jars from APK assets to filesDir on first launch.
 *
 * Native .so libraries (libjvm.so and friends) are NOT here — Android forbids dlopen of libraries from
 * writable app storage (since API 29). Those live in jniLibs/arm64-v8a/ so the installer puts them in the
 * APK's native-lib dir, which is an allowed dlopen path. Only the JRE's non-native files (the modules
 * image, the lib jars, conf, fontconfig) and the client/probe/cacio jars are staged here.
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
