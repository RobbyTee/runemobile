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
    private const val STAMP = ".installed-v1"

    /** Returns the JAVA_HOME path (filesDir/jvm/jre). */
    fun ensureInstalled(ctx: Context): File {
        val root = File(ctx.filesDir, "jvm")
        val stamp = File(root, STAMP)
        if (!stamp.exists()) {
            Log.i(TAG, "first run: unpacking embedded JVM assets to $root")
            copyAssetDir(ctx, "jvm", root)
            stamp.writeText(System.currentTimeMillis().toString())
        }
        return File(root, "jre")
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
