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

    /**
     * Set a process environment variable. MUST be called before [startJvm] — HotSpot snapshots the
     * environment at VM startup, and the OSRS gamepack reads the JX_* launcher handoff from it.
     */
    external fun nativeSetEnv(key: String, value: String)

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

    // ---- A1 input parity ----------------------------------------------------------------------------
    /** Mouse-wheel notch at game-canvas (x,y); rotation <0 = up/zoom-in, >0 = down/zoom-out (pinch). */
    external fun nativeWheel(x: Int, y: Int, rotation: Int)

    /** Precise tap at game-canvas (x,y) with NO world snap — for UI/widget hits and chrome proxying. */
    external fun nativeClickPrecise(x: Int, y: Int, button: Int)

    /** Move the engine pointer to game-canvas (x,y) without a click (hover / menu-track). */
    external fun nativeMove(x: Int, y: Int)

    /** Commit a string of text to the focused engine field (soft-keyboard input). */
    external fun nativeText(s: String)

    /** A special key by AWT virtual-key code (backspace, enter, arrows, …). */
    external fun nativeKeyStroke(vk: Int)

    /** Does the engine currently have a text field focused? Poll to auto-raise/dismiss the soft keyboard. */
    external fun nativeIsTextInputOpen(): Boolean

    /** Monotonic counter the engine bumps on each committed interact-tap — advance => fire a haptic. */
    external fun nativeInteractTapSeq(): Int

    /** Finger snap radius (game px) the touch-forgiveness uses. */
    external fun nativeSnapRadius(): Int

    /** Latest composited frame dimensions (game-canvas pixels), or 0 before the first frame. */
    external fun nativeFrameWidth(): Int
    external fun nativeFrameHeight(): Int

    // Kotlin-friendly aliases.
    fun setEnv(key: String, value: String) = nativeSetEnv(key, value)
    fun setSurface(surface: Surface?) = nativeSetSurface(surface)
    fun click(x: Int, y: Int, button: Int) = nativeClick(x, y, button)
    fun touch(type: Int, x: Int, y: Int, button: Int) = nativeTouch(type, x, y, button)
    fun camera(dx: Int, dy: Int) = nativeCamera(dx, dy)
    fun clearHover() = nativeClearHover()
    fun wheel(x: Int, y: Int, rotation: Int) = nativeWheel(x, y, rotation)
    fun clickPrecise(x: Int, y: Int, button: Int) = nativeClickPrecise(x, y, button)
    fun move(x: Int, y: Int) = nativeMove(x, y)
    fun text(s: String) = nativeText(s)
    fun keyStroke(vk: Int) = nativeKeyStroke(vk)
    fun isTextInputOpen() = nativeIsTextInputOpen()
    fun interactTapSeq() = nativeInteractTapSeq()
    fun snapRadius() = nativeSnapRadius()
    fun frameWidth() = nativeFrameWidth()
    fun frameHeight() = nativeFrameHeight()
}
