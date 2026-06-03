package dev.audaro.helloshell

import android.view.Surface

/**
 * Thin Kotlin facade over the native JNI Invocation-API bootstrap + render/input host.
 *
 * The app process is hosted by ART, but the game runs on a *second*, in-process HotSpot VM that the
 * native layer brings up with JNI_CreateJavaVM (dlopen of the embedded JRE's libjvm.so). This mirrors
 * the proven iOS bootstrap (JVMLauncher.m) minus every iOS-JIT workaround — Android grants JIT for free.
 *
 * The HotSpot VM lives for the whole process: [startJvm] creates it on a dedicated, never-exiting native
 * thread and returns once it is up (it does NOT block on the game). Frames are pulled from the
 * net.runelite.client.headless.JavaBridge facade by a native render thread and blitted to the SurfaceView
 * window set via [setSurface]; touches are enqueued via [click]/[touch]/[camera] and drained by that same
 * render thread (so the ART UI thread never attaches to HotSpot).
 */
object JvmLauncher {

    init {
        System.loadLibrary("helloshell")
    }

    /**
     * Bring up the embedded HotSpot VM and start running [mainClass].main(args) on its own thread. Returns
     * once the VM is created (0 == JNI_OK) — the game keeps running after this returns.
     *
     * @param javaHome    filesDir path of the unpacked JRE (contains lib/server/libjvm.so).
     * @param libjvmPath  absolute path to libjvm.so (javaHome/lib/server/libjvm.so).
     * @param libraryPath ':'-joined java.library.path (dirs holding the JRE .so libs).
     * @param classpath   ':'-joined jar list on the embedded VM's classpath.
     * @param mainClass   binary name of the entrypoint class to invoke.
     * @param extraOpts   additional -X / -D / --add-* VM options.
     */
    external fun startJvm(
        javaHome: String,
        libjvmPath: String,
        libraryPath: String,
        classpath: String,
        mainClass: String,
        extraOpts: Array<String>,
    ): Int

    /** Hand the SurfaceView's surface (or null to clear) to the native render thread for blitting. */
    external fun nativeSetSurface(surface: Surface?)

    /** A discrete tap at game-canvas (x,y); button 1 = left (walk/interact), 3 = long-press menu. */
    external fun nativeClick(x: Int, y: Int, button: Int)

    /** One phase of a drag stream (type 0=down/1=move/2=up) at game-canvas (x,y) — no synthesized click. */
    external fun nativeTouch(type: Int, x: Int, y: Int, button: Int)

    /** Rotate the camera by a raw pixel delta (two-finger / one-finger pan). */
    external fun nativeCamera(dx: Int, dy: Int)

    /** Make the engine pointer "absent" (call when a camera/pan gesture begins). */
    external fun nativeClearHover()

    /** Latest composited frame dimensions (game-canvas pixels), or 0 before the first frame. */
    external fun nativeFrameWidth(): Int
    external fun nativeFrameHeight(): Int

    // Kotlin-friendly aliases.
    fun setSurface(surface: Surface?) = nativeSetSurface(surface)
    fun click(x: Int, y: Int, button: Int) = nativeClick(x, y, button)
    fun touch(type: Int, x: Int, y: Int, button: Int) = nativeTouch(type, x, y, button)
    fun camera(dx: Int, dy: Int) = nativeCamera(dx, dy)
    fun clearHover() = nativeClearHover()
    fun frameWidth() = nativeFrameWidth()
    fun frameHeight() = nativeFrameHeight()
}
