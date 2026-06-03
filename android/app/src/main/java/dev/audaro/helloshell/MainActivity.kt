package dev.audaro.helloshell

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Milestone B host: brings up the embedded HotSpot VM running headless RuneLite (DeviceHeadlessBoot) and
 * blits its composited frames to a SurfaceView, feeding touches back through the JavaBridge facade.
 *
 * HotSpot-in-process is effectively one VM per process, so VM bringup is guarded by a process-wide
 * singleton and uses the application context — the boot survives Activity recreation (rotation etc.).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var surfaceView: SurfaceView
    private lateinit var gestures: GestureDetector
    private var cameraGestureActive = false

    companion object {
        private const val TAG = "MainActivity"
        private val booted = AtomicBoolean(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        surfaceView = SurfaceView(this)
        setContentView(surfaceView)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                JvmLauncher.setSurface(holder.surface)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                JvmLauncher.setSurface(holder.surface)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                JvmLauncher.setSurface(null)
            }
        })

        gestures = GestureDetector(this, GestureListener())

        // Boot the VM once per process, off the UI thread and on the application context.
        if (booted.compareAndSet(false, true)) {
            val app = applicationContext
            thread(name = "jvm-boot") { bootJvm(app) }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            cameraGestureActive = false
        }
        return true
    }

    /** Map a screen (view) coordinate to game-canvas pixels using the current frame size. */
    private fun toGameX(screenX: Float): Int {
        val fw = JvmLauncher.frameWidth()
        val vw = surfaceView.width
        if (fw <= 0 || vw <= 0) return screenX.toInt()
        return (screenX * fw / vw).toInt()
    }
    private fun toGameY(screenY: Float): Int {
        val fh = JvmLauncher.frameHeight()
        val vh = surfaceView.height
        if (fh <= 0 || vh <= 0) return screenY.toInt()
        return (screenY * fh / vh).toInt()
    }
    private fun scaleX(px: Float): Int {
        val fw = JvmLauncher.frameWidth(); val vw = surfaceView.width
        return if (fw <= 0 || vw <= 0) px.toInt() else (px * fw / vw).toInt()
    }
    private fun scaleY(px: Float): Int {
        val fh = JvmLauncher.frameHeight(); val vh = surfaceView.height
        return if (fh <= 0 || vh <= 0) px.toInt() else (px * fh / vh).toInt()
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val gx = toGameX(e.x); val gy = toGameY(e.y)
            Log.i(TAG, "tap screen=(${e.x},${e.y}) view=(${surfaceView.width}x${surfaceView.height}) frame=(${JvmLauncher.frameWidth()}x${JvmLauncher.frameHeight()}) -> game=($gx,$gy)")
            JvmLauncher.click(gx, gy, 1)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            JvmLauncher.click(toGameX(e.x), toGameY(e.y), 3)   // right-click menu
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (!cameraGestureActive) {
                cameraGestureActive = true
                JvmLauncher.clearHover()
            }
            // Android distanceX/Y = old - new; negate so dragging moves the world the natural way.
            JvmLauncher.camera(-scaleX(distanceX).toInt(), -scaleY(distanceY).toInt())
            return true
        }
    }

    private fun bootJvm(app: android.content.Context) {
        val javaHome = AssetInstaller.ensureInstalled(app)
        val libjvm = File(javaHome, "lib/server/libjvm.so")
        if (!libjvm.exists()) {
            Log.e(TAG, "embedded JRE not installed; expected $libjvm")
            return
        }

        val jvmDir = File(app.filesDir, "jvm")
        val classpath = listOf("client-1.12.28-SNAPSHOT-shaded.jar", "probes.jar")
            .joinToString(":") { File(jvmDir, it).absolutePath }
        val libraryPath = File(javaHome, "lib").absolutePath

        // A writable RuneLite home + headless capture dir (the gamepack cache + PNG fallback evidence).
        val home = app.filesDir.absolutePath
        val captureDir = File(app.filesDir, "rl-capture").apply { mkdirs() }.absolutePath
        // Android's default java.io.tmpdir is /tmp (absent in the app sandbox); Font.createFont stages a
        // temp file there and fails with "Problem reading font data", poisoning FontManager.<clinit>. Point
        // it at a writable dir so the gamepack fonts load.
        val tmpDir = File(app.filesDir, "tmp").apply { mkdirs() }.absolutePath

        // iOS's headless/CACIO opt set, minus the iOS-JIT workarounds (Android grants JIT for free):
        // dropped -XX:+MirrorMappedCodeCache / DisablePrimordialThreadGuardPages / -UseCompressedClassPointers /
        // ReservedCodeCacheSize. Kept: the CACIO JPMS exports/opens, headless render + bridge props.
        val opts = arrayOf(
            "-Djava.awt.headless=false",
            "--add-exports=java.desktop/java.awt=ALL-UNNAMED",
            "--add-exports=java.desktop/java.awt.peer=ALL-UNNAMED",
            "--add-exports=java.desktop/sun.awt.image=ALL-UNNAMED",
            "--add-exports=java.desktop/sun.java2d=ALL-UNNAMED",
            "--add-exports=java.desktop/java.awt.dnd.peer=ALL-UNNAMED",
            "--add-exports=java.desktop/sun.awt=ALL-UNNAMED",
            "--add-exports=java.desktop/sun.awt.event=ALL-UNNAMED",
            "--add-exports=java.desktop/sun.awt.datatransfer=ALL-UNNAMED",
            "--add-exports=java.base/sun.security.action=ALL-UNNAMED",
            "--add-opens=java.base/java.util=ALL-UNNAMED",
            "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.java2d=ALL-UNNAMED",
            "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
            "-Xmx512m",
            "-Duser.home=$home",
            "-Djava.io.tmpdir=$tmpDir",
            "-Drunelite.headless.capture.dir=$captureDir",
            "-Drunelite.bridge=true",
            "-Drunelite.headless.capture.interval=10",
            "-Drunelite.headless.capture.preLoginMax=40",
            "-Dheadless.runSeconds=86400",          // keep the boot thread alive/logging for the whole session
            "-Dheadless.captureTargetFrames=999999", // never early-exit; ride the window
        )

        Log.i(TAG, "starting HotSpot: DeviceHeadlessBoot, cp=$classpath")
        val rc = JvmLauncher.startJvm(
            javaHome = javaHome.absolutePath,
            libjvmPath = libjvm.absolutePath,
            libraryPath = libraryPath,
            classpath = classpath,
            mainClass = "DeviceHeadlessBoot",
            extraOpts = opts,
        )
        Log.i(TAG, "startJvm rc=$rc (VM up; watch logcat tags jvm-boot/jvm-stdout)")
    }
}
