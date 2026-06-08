package dev.audaro.helloshell

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.Choreographer
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.view.Gravity
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import org.json.JSONObject
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
    private lateinit var scaleGestures: ScaleGestureDetector
    private lateinit var keyboardView: GameInputView
    private var cameraGestureActive = false
    private var pinchActive = false
    private var scaleAccum = 1f

    // A1 per-frame poll state (driven by Choreographer).
    private var vibrator: Vibrator? = null
    private var lastTapSeq = 0
    private var keyboardShown = false
    private var frameCallback: Choreographer.FrameCallback? = null

    // A6 native chrome (cover-and-proxy tab rails over the live stones).
    private lateinit var chromeOverlay: ChromeOverlayView
    private var chromeUiLogged = false
    private var lastChromeJson = ""

    // A2 press-and-peek menu state.
    private lateinit var menuOverlay: MenuOverlayView
    private var menuActive = false      // the long-press has claimed this gesture
    private var menuBuilding = false    // waiting for the engine menu to land in the snapshot
    private var menuTouchActive = false // finger still down
    private var menuAnchorX = 0f
    private var menuAnchorY = 0f
    private var menuCurX = 0f
    private var menuCurY = 0f
    private var menuHandRight = false
    private var menuOpenAtNanos = 0L
    private var menuHighlight = -1
    private var emX = 0    // engine menu box (game-canvas coords) from the snapshot
    private var emY = 0
    private var emW = 0
    private var emCount = 0

    companion object {
        private const val TAG = "MainActivity"
        private val booted = AtomicBoolean(false)

        // AWT virtual-key codes (java.awt.event.KeyEvent) the engine's onKeyStroke expects.
        private const val VK_BACK_SPACE = 8
        private const val VK_ENTER = 10
        private const val VK_LEFT = 37
        private const val VK_UP = 38
        private const val VK_RIGHT = 39
        private const val VK_DOWN = 40

        // Pinch-to-wheel: geometric step a scale ratio must cross to emit one wheel notch.
        private const val PINCH_STEP = 1.08f

        // OSRS engine menu geometry: 19px "Choose Option" header, 15px per row. A native row k commits to the
        // engine row centre (menuX + menuW/2, menuY + 19 + k*15 + 7) so plugin onClick/Menu-Entry-Swapper hold.
        private const val MENU_HEADER_PX = 19
        private const val MENU_ROW_PX = 15
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

        // SurfaceView for the live game frame; a tiny hidden input view to host the soft keyboard's
        // InputConnection (the SurfaceView itself can't be a text editor). FrameLayout stacks them.
        surfaceView = SurfaceView(this)
        keyboardView = GameInputView(this)
        menuOverlay = MenuOverlayView(this)
        // A6 native chrome: cover-and-proxy tab-rails drawn over the live stones. A native tab tap proxies a
        // precise click onto the live engine stone centre (the engine + plugins stay authoritative).
        chromeOverlay = ChromeOverlayView(this)
        chromeOverlay.onTabTap = { idx, gcx, gcy ->
            Log.i(TAG, "a6: native tab $idx tap -> onClickPrecise($gcx,$gcy)")
            JvmLauncher.clickPrecise(gcx, gcy, 1)
        }
        // A4 plugin UI: a full-screen Compose panel (GONE until opened) + a small launcher button. The panel
        // is a data screen (Compose); the live game overlays above stay classic Views (hybrid, §4.2).
        val pluginPanel = ComposeView(this).apply { visibility = View.GONE }
        val pluginButton = Button(this).apply { text = "⚙" }   // gear
        pluginButton.setOnClickListener {
            pluginPanel.setContent {
                PluginPanel(onClose = {
                    pluginPanel.visibility = View.GONE
                    pluginButton.visibility = View.VISIBLE
                })
            }
            pluginPanel.visibility = View.VISIBLE
            pluginButton.visibility = View.GONE
        }
        // A6 A/B: toggle the native rails on/off (▦ = native chrome shown; ▤ = raw engine stones).
        val chromeButton = Button(this).apply { text = "▦" }
        chromeButton.setOnClickListener {
            chromeOverlay.setShown(!chromeOverlay.railsShown)
            chromeButton.text = if (chromeOverlay.railsShown) "▦" else "▤"
        }
        // A3: on-device Jagex login / switch account — runs the WebView OAuth, writes tokens.json, restarts.
        val signInButton = Button(this).apply { text = "⎆" }
        signInButton.setOnClickListener { startActivity(Intent(this, LoginActivity::class.java)) }
        val root = FrameLayout(this).apply {
            val mp = FrameLayout.LayoutParams.MATCH_PARENT
            addView(surfaceView, FrameLayout.LayoutParams(mp, mp))
            addView(chromeOverlay, FrameLayout.LayoutParams(mp, mp))  // native rails over the live stones
            addView(keyboardView, FrameLayout.LayoutParams(1, 1))
            addView(menuOverlay, FrameLayout.LayoutParams(mp, mp))   // drawn on top of the live frame + chrome
            addView(pluginPanel, FrameLayout.LayoutParams(mp, mp))   // Compose plugin list (GONE until opened)
            addView(pluginButton, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END))
            addView(chromeButton, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START))
            addView(signInButton, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        }
        setContentView(root)
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
        scaleGestures = ScaleGestureDetector(this, ScaleListener())
        // Drive gestures from the SurfaceView itself so event coords are view-local: the screen->game
        // mapping is then correct wherever the view sits in the window (status-bar inset, display cutout,
        // multi-window), not only the full-bleed emulator case.
        surfaceView.setOnTouchListener { _, event ->
            // Once a long-press has opened the menu, this gesture belongs to menu-peek: track the finger for the
            // row highlight and commit on release — do NOT feed the tap/camera/pinch detectors.
            if (menuActive) {
                menuCurX = event.x
                menuCurY = event.y
                when (event.actionMasked) {
                    MotionEvent.ACTION_MOVE -> updateMenuHighlight(event.x, event.y)
                    MotionEvent.ACTION_UP -> { menuTouchActive = false; if (menuOverlay.isActive) commitMenuSelection() }
                    MotionEvent.ACTION_CANCEL -> { menuTouchActive = false; cancelMenu() }
                }
                return@setOnTouchListener true
            }
            scaleGestures.onTouchEvent(event)   // pinch-zoom first; it sets the isInProgress flag onScroll reads
            gestures.onTouchEvent(event)
            // Pre-hover: move the engine pointer to a fresh single-finger touch so tooltips/menus track it.
            if (event.actionMasked == MotionEvent.ACTION_DOWN && event.pointerCount == 1) {
                JvmLauncher.move(toGameX(event.x), toGameY(event.y))
            }
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                cameraGestureActive = false
            }
            true
        }

        startInputPolling()

        // Boot the VM once per process, off the UI thread and on the application context.
        // `adb ... --ez census true` arms the A-1b runtime gate-flip census (extra gates + the census probe).
        val censusMode = intent?.getBooleanExtra("census", false) ?: false
        // A3 login-first boot gate: JX_* is snapshotted at JNI_CreateJavaVM, so a valid tokens.json must exist
        // BEFORE boot. With none (and not census, which uses pushed tokens), run the on-device Jagex login first;
        // it writes tokens.json then restarts the process for a clean boot (Review-2: login-first or restart).
        if (!censusMode && !JagexTokens.hasTokens(applicationContext)) {
            Log.i(TAG, "a3: no tokens.json — launching on-device Jagex login before boot")
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        if (booted.compareAndSet(false, true)) {
            val app = applicationContext
            thread(name = "jvm-boot") { bootJvm(app, censusMode) }
        }
    }

    /** Map a SurfaceView-local coordinate to game-canvas pixels, clamped to the current frame. */
    private fun toGameX(viewX: Float): Int {
        val fw = JvmLauncher.frameWidth()
        val vw = surfaceView.width
        if (fw <= 0 || vw <= 0) return viewX.toInt()
        return (viewX * fw / vw).toInt().coerceIn(0, fw - 1)
    }
    private fun toGameY(viewY: Float): Int {
        val fh = JvmLauncher.frameHeight()
        val vh = surfaceView.height
        if (fh <= 0 || vh <= 0) return viewY.toInt()
        return (viewY * fh / vh).toInt().coerceIn(0, fh - 1)
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
            beginMenuPeek(e.x, e.y)
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            // One finger = camera rotate; two fingers / an active pinch = zoom (handled by ScaleListener).
            if (pinchActive || scaleGestures.isInProgress || e2.pointerCount > 1) {
                return false
            }
            if (!cameraGestureActive) {
                cameraGestureActive = true
                JvmLauncher.clearHover()
            }
            // Android distanceX/Y = old - new; negate so dragging moves the world the natural way.
            JvmLauncher.camera(-scaleX(distanceX).toInt(), -scaleY(distanceY).toInt())
            return true
        }
    }

    /** Pinch-to-zoom: convert the scale ratio into discrete mouse-wheel notches at the pinch centroid. */
    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            pinchActive = true
            scaleAccum = 1f
            JvmLauncher.clearHover()
            return true
        }

        override fun onScale(d: ScaleGestureDetector): Boolean {
            scaleAccum *= d.scaleFactor
            val gx = toGameX(d.focusX)
            val gy = toGameY(d.focusY)
            // Fingers spreading (scaleAccum grows) = zoom in = wheel up = negative rotation.
            while (scaleAccum >= PINCH_STEP) {
                JvmLauncher.wheel(gx, gy, -1)
                scaleAccum /= PINCH_STEP
            }
            while (scaleAccum <= 1f / PINCH_STEP) {
                JvmLauncher.wheel(gx, gy, 1)
                scaleAccum *= PINCH_STEP
            }
            return true
        }

        override fun onScaleEnd(d: ScaleGestureDetector) {
            pinchActive = false
        }
    }

    /**
     * A tiny invisible text editor that hosts the soft keyboard. Its InputConnection forwards committed text
     * to the engine via onText and special keys via onKeyStroke (AWT virtual-key codes). NO_SUGGESTIONS keeps
     * most IMEs on commitText rather than composing, which keeps the mapping 1:1.
     */
    private inner class GameInputView(context: Context) : View(context) {
        init {
            isFocusable = true
            isFocusableInTouchMode = true
        }

        override fun onCheckIsTextEditor(): Boolean = true

        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
            outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_DONE
            return object : BaseInputConnection(this, false) {
                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    val s = text?.toString().orEmpty()
                    if (s.isNotEmpty()) {
                        JvmLauncher.text(s)
                    }
                    return true
                }

                override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                    repeat(beforeLength.coerceAtLeast(0)) { JvmLauncher.keyStroke(VK_BACK_SPACE) }
                    return true
                }

                override fun sendKeyEvent(event: KeyEvent): Boolean {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        when (event.keyCode) {
                            KeyEvent.KEYCODE_DEL -> JvmLauncher.keyStroke(VK_BACK_SPACE)
                            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> JvmLauncher.keyStroke(VK_ENTER)
                            KeyEvent.KEYCODE_DPAD_LEFT -> JvmLauncher.keyStroke(VK_LEFT)
                            KeyEvent.KEYCODE_DPAD_RIGHT -> JvmLauncher.keyStroke(VK_RIGHT)
                            KeyEvent.KEYCODE_DPAD_UP -> JvmLauncher.keyStroke(VK_UP)
                            KeyEvent.KEYCODE_DPAD_DOWN -> JvmLauncher.keyStroke(VK_DOWN)
                            else -> {
                                val u = event.unicodeChar
                                if (u != 0) JvmLauncher.text(u.toChar().toString())
                            }
                        }
                    }
                    return true
                }
            }
        }
    }

    /**
     * Per-frame poll of the engine read-surface: auto-raise/dismiss the soft keyboard when a text field gains/
     * loses focus, and fire a haptic tick when the interact-tap counter advances. Driven by Choreographer so it
     * tracks the display refresh without a dedicated thread.
     */
    private fun startInputPolling() {
        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val want = JvmLauncher.isTextInputOpen()
                if (want != keyboardShown) {
                    keyboardShown = want
                    Log.i(TAG, "a1: textInputOpen=$want -> ${if (want) "raise" else "dismiss"} soft keyboard")
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    if (want) {
                        keyboardView.requestFocus()
                        imm.showSoftInput(keyboardView, InputMethodManager.SHOW_IMPLICIT)
                    } else {
                        imm.hideSoftInputFromWindow(keyboardView.windowToken, 0)
                    }
                }
                val seq = JvmLauncher.interactTapSeq()
                if (seq != lastTapSeq) {
                    lastTapSeq = seq
                    Log.i(TAG, "a1: interactTapSeq -> $seq (haptic)")
                    vibrateTick()
                }
                if (menuBuilding) {
                    tryBuildNativeMenu()
                }
                updateChrome()
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        frameCallback = cb
        Choreographer.getInstance().postFrameCallback(cb)
    }

    /**
     * A6: feed the cover-and-proxy tab-rails the latest chrome geometry each frame. Reads the render-thread-
     * published MobileChromeBridge snapshot (lock-free, no HotSpot attach), parses the 14 stone bounds, and
     * maps them game-canvas -> view via the blit full-stretch (same scale as toGameX/Y, inverted). Skips the
     * parse when the published JSON is unchanged (e.g. the constant login-screen snapshot).
     */
    private fun updateChrome() {
        if (!::chromeOverlay.isInitialized || !chromeOverlay.railsShown) return
        val json = JvmLauncher.chromeSnapshotPub()
        if (json == lastChromeJson) return
        lastChromeJson = json
        val fw = JvmLauncher.frameWidth(); val fh = JvmLauncher.frameHeight()
        val vw = surfaceView.width; val vh = surfaceView.height
        if (fw <= 0 || fh <= 0 || vw <= 0 || vh <= 0) return
        val o = try { JSONObject(json) } catch (e: Exception) { return }
        if (!o.optBoolean("loggedIn")) { chromeOverlay.setStones(emptyList(), -1, 1f, 1f); return }
        val tabsArr = o.optJSONArray("tabs") ?: return
        val tabs = ArrayList<IntArray?>(tabsArr.length())
        for (i in 0 until tabsArr.length()) {
            if (tabsArr.isNull(i)) { tabs.add(null); continue }
            val a = tabsArr.optJSONArray(i)
            tabs.add(if (a != null && a.length() >= 4) intArrayOf(a.getInt(0), a.getInt(1), a.getInt(2), a.getInt(3)) else null)
        }
        val active = o.optInt("activeTab", -1)
        val n = chromeOverlay.setStones(tabs, active, vw.toFloat() / fw, vh.toFloat() / fh)
        if (n > 0 && !chromeUiLogged) {
            chromeUiLogged = true
            Log.i(TAG, "ANDROID_A6_UI_GREEN tabsDrawn=$n activeTab=$active frame=${fw}x${fh} view=${vw}x${vh}")
        }
    }

    private fun vibrateTick() {
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    // ---- A2 press-and-peek menu state machine -------------------------------------------------------

    /** Long-press: open the engine right-click menu at the finger and start polling for it to land. */
    private fun beginMenuPeek(viewX: Float, viewY: Float) {
        if (menuActive) return
        val gx = toGameX(viewX)
        val gy = toGameY(viewY)
        menuAnchorX = viewX; menuAnchorY = viewY
        menuCurX = viewX; menuCurY = viewY
        menuHandRight = viewX > surfaceView.width * 0.5f
        menuActive = true
        menuBuilding = true
        menuTouchActive = true
        menuHighlight = -1
        menuOpenAtNanos = System.nanoTime()
        JvmLauncher.click(gx, gy, 3)   // open the engine right-click menu at the finger
        Log.i(TAG, "a2: long-press -> menu open game=($gx,$gy) hand=${if (menuHandRight) "R" else "L"}")
    }

    /** Poll the snapshot (each frame, while building) until the engine menu the long-press opened is reflected. */
    private fun tryBuildNativeMenu() {
        val d = try { JSONObject(JvmLauncher.menuSnapshot()) } catch (e: Exception) { null }
        val entries = d?.optJSONArray("entries")
        val ready = d != null && d.optBoolean("loggedIn", false) && d.optBoolean("menuOpen", false) &&
            d.optInt("count", 0) > 0 && entries != null && entries.length() > 0
        if (ready) {
            menuBuilding = false
            presentNativeMenu(d!!)
            if (!menuTouchActive) cancelMenu()   // finger lifted before the menu built
            return
        }
        if (System.nanoTime() - menuOpenAtNanos > 1_000_000_000L) {
            Log.i(TAG, "a2: no engine menu within 1s — aborting")
            teardownMenu()
        }
    }

    /** Build the native overlay from the snapshot (game-space engine box + display-order entries). */
    private fun presentNativeMenu(d: JSONObject) {
        emX = d.optInt("menuX", -1)
        emY = d.optInt("menuY", -1)
        emW = d.optInt("menuW", 0)
        emCount = d.optInt("count", 0)
        val arr = d.optJSONArray("entries") ?: return
        val n = minOf(arr.length(), 14)
        if (n == 0 || emW <= 0) { cancelMenu(); return }
        val items = ArrayList<MenuOverlayView.Entry>(n)
        for (k in 0 until n) {
            val o = arr.optJSONObject(k) ?: continue
            items.add(MenuOverlayView.Entry(o.optString("opt", ""), o.optString("tgt", "")))
        }
        menuOverlay.show(items, menuAnchorX, menuAnchorY, menuHandRight)
        Log.i(TAG, "a2: native menu $emCount entries at game=($emX,$emY) w=$emW")
        if (menuTouchActive) updateMenuHighlight(menuCurX, menuCurY)
    }

    /** Lock the highlight onto the row under the finger; haptic tick on change. */
    private fun updateMenuHighlight(x: Float, y: Float) {
        if (!menuOverlay.isActive) return
        val row = menuOverlay.rowAt(x, y)
        if (menuOverlay.setHighlight(row)) {
            menuHighlight = row
            vibrateTick()
        }
    }

    /** Release on a row: click through to the engine row centre (19/15 geometry). Off-row -> cancel. */
    private fun commitMenuSelection() {
        val k = menuHighlight
        if (k in 0 until emCount) {
            val gx = emX + emW / 2
            val gy = emY + MENU_HEADER_PX + k * MENU_ROW_PX + MENU_ROW_PX / 2
            JvmLauncher.selectMenuPoint(gx, gy)
            Log.i(TAG, "a2: select row $k -> game=($gx,$gy)")
            teardownMenu()
        } else {
            cancelMenu()
        }
    }

    /** Close the engine menu via its own bottom Cancel row (no stray world click), then tear down. */
    private fun cancelMenu() {
        if (emCount > 0 && emW > 0) {
            val k = emCount - 1
            val gx = emX + emW / 2
            val gy = emY + MENU_HEADER_PX + k * MENU_ROW_PX + MENU_ROW_PX / 2
            JvmLauncher.selectMenuPoint(gx, gy)
        }
        Log.i(TAG, "a2: menu cancelled")
        teardownMenu()
    }

    private fun teardownMenu() {
        menuOverlay.hide()
        menuActive = false
        menuBuilding = false
        menuTouchActive = false
        menuHighlight = -1
    }

    override fun onDestroy() {
        frameCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
        frameCallback = null
        super.onDestroy()
    }

    private fun bootJvm(app: android.content.Context, census: Boolean = false) {
        val javaHome = AssetInstaller.ensureInstalled(app)
        val libjvm = File(javaHome, "lib/server/libjvm.so")
        if (!libjvm.exists()) {
            Log.e(TAG, "embedded JRE not installed; expected $libjvm")
            booted.set(false)   // not booted yet — let a later launch retry the asset unpack
            return
        }

        val jvmDir = File(app.filesDir, "jvm")
        // The client jar carries the project version (e.g. client-1.12.29-SNAPSHOT-shaded.jar); glob for it so
        // a version bump needs no code edit (staged by tools/stage-mobile-runtime.sh; AssetInstaller unpacks it).
        val clientJar = jvmDir.list { _, name -> name.startsWith("client-") && name.endsWith("-shaded.jar") }
            ?.firstOrNull()
        if (clientJar == null) {
            Log.e(TAG, "no client-*-shaded.jar staged in $jvmDir")
            booted.set(false)
            return
        }
        val classpath = listOf(clientJar, "probes.jar")
            .joinToString(":") { File(jvmDir, it).absolutePath }
        val libraryPath = File(javaHome, "lib").absolutePath

        // A writable RuneLite home + headless capture dir (the gamepack cache + PNG fallback evidence).
        val home = app.filesDir.absolutePath
        val captureDir = File(app.filesDir, "rl-capture").apply { mkdirs() }.absolutePath
        // Android's default java.io.tmpdir is /tmp (absent in the app sandbox); Font.createFont stages a
        // temp file there and fails with "Problem reading font data", poisoning FontManager.<clinit>. Point
        // it at a writable dir so the gamepack fonts load.
        val tmpDir = File(app.filesDir, "tmp").apply { mkdirs() }.absolutePath

        // JX_* launcher handoff: if a tokens.json is present, set the JX_* env (before startJvm) and arm
        // HeadlessLogin's EULA->Play Now click machine. No tokens.json -> login-screen render only. The setenv
        // must happen here, before JvmLauncher.startJvm creates the VM (HotSpot snapshots env at startup).
        val autologinArmed = JagexTokens.loadAndArm(app)

        // iOS's headless/CACIO opt set, minus the iOS-JIT workarounds (Android grants JIT for free):
        // dropped -XX:+MirrorMappedCodeCache / DisablePrimordialThreadGuardPages / -UseCompressedClassPointers /
        // ReservedCodeCacheSize. Kept: the CACIO JPMS exports/opens, headless render + bridge props.
        val opts = mutableListOf(
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
            "-Drunelite.menupeek=true",             // A2: publish the menu-peek snapshot each client tick
            "-Drunelite.menupeek.maxEntries=14",
            "-Drunelite.mobileui=true",             // A4: plugin control plane (list/toggle/config from the host)
            "-Drunelite.mobilechrome=true",         // A6: publish the read-only chrome snapshot (tab-rail bounds)
        )
        // A7 resizable: render at the screen's landscape aspect — HeadlessResize grows the gamepack Component on
        // login (stock, inset-aware, the same mechanism iOS device-proved). With the OSRS account in RESIZABLE
        // display mode the gamepack fills it (resizable toplevel 164 → chat-at-top + resizable chrome unblock);
        // a FIXED account harmlessly keeps the 765x503 viewport (gamepack pillarboxes, canvas stays 765x503, so
        // the blit + touch + A6 rails are unchanged — no regression). The account's display mode is the switch.
        run {
            val dm = app.resources.displayMetrics
            val longEdge = maxOf(dm.widthPixels, dm.heightPixels)
            val shortEdge = maxOf(1, minOf(dm.widthPixels, dm.heightPixels))
            val renderH = 720
            val renderW = (renderH.toLong() * longEdge / shortEdge).toInt()
            opts.add("-Drunelite.headless.resize=${renderW}x${renderH}")
            Log.i(TAG, "A7 resize armed: ${renderW}x${renderH} (screen ${longEdge}x${shortEdge}; resizable iff account in resizable display mode)")
        }
        if (autologinArmed) {
            opts.addAll(JagexTokens.autologinOpts)
            Log.i(TAG, "autologin ARMED (tokens.json present): EULA 302,312 -> Play Now 382,248, world 301")
        }
        if (census) {
            // A-1b runtime gate-flip census: turn on the remaining read-surface gates (menupeek + autologin
            // are already on above) and arm the one-shot GateCensus probe in DeviceHeadlessBoot.
            opts.addAll(listOf(
                "-Drunelite.gatecensus=true",
                "-Drunelite.huddata=true",
                "-Drunelite.hudveneer=true",
            ))
            Log.i(TAG, "A-1b census mode: +huddata/hudveneer + gatecensus (mobileui/mobilechrome/resize always-on)")
        }

        Log.i(TAG, "starting HotSpot: DeviceHeadlessBoot, cp=$classpath")
        val rc = JvmLauncher.startJvm(
            javaHome = javaHome.absolutePath,
            libjvmPath = libjvm.absolutePath,
            libraryPath = libraryPath,
            classpath = classpath,
            mainClass = "DeviceHeadlessBoot",
            extraOpts = opts.toTypedArray(),
        )
        if (rc != 0) {
            // VM creation failed (JNI_OK == 0). Clear the boot latch so a later launch into this same process
            // retries instead of being wedged VM-less with booted==true. (A VM that came up but whose main
            // class later threw is still rc==0 and stays booted — that's the engine's own run, not a boot fail.)
            Log.e(TAG, "startJvm rc=$rc — VM bringup FAILED; clearing booted so a relaunch can retry")
            booted.set(false)
            return
        }
        Log.i(TAG, "startJvm rc=$rc (VM up; watch logcat tags jvm-boot/jvm-stdout)")
        if (census) {
            runA4SeamSelfTest()
            runA6SeamSelfTest()
        }
    }

    /**
     * A6 chrome-seam probe (census mode): does the FIXED-mode MobileChromeBridge read surface resolve real
     * geometry once the world fully loads? The bridge's own one-shot CHROME_READ verdict fires ~60 LOGGED_IN
     * frames in — possibly before the scene builds — so this polls the LIVE getSnapshot() for ~40s after login
     * and reports the most-resolved snapshot. GREEN = the read surface ever resolved a viewport OR a tab stone
     * (=> A6 cover-and-proxy is viable in FIXED mode; the resizable look is a separate, deferrable concern).
     * RED = logged in but the surface stayed empty (=> the resizable-canvas resize is a hard prerequisite).
     * Runs on the rl-bridge thread (queued after the A4 test), by which point the world is fully loaded.
     */
    private fun runA6SeamSelfTest() {
        JvmLauncher.onBridge {
            var loggedIn = false
            var everResolved = false   // viewport OR any tab stone resolved => cover-and-proxy viable in fixed mode
            var best = "(no loggedIn snapshot)"
            for (i in 0 until 40) {     // ~40s @ 1s — chrome only ticks once mobilechrome gate + LOGGED_IN
                val snap = try { JvmLauncher.chromeSnapshot() } catch (t: Throwable) { "" }
                if (snap.length > 2) {
                    try {
                        val o = org.json.JSONObject(snap)
                        if (o.optBoolean("loggedIn")) {
                            loggedIn = true
                            val vp = !o.isNull("viewport")
                            val tabs = o.optJSONArray("tabs")
                            var tabsNonNull = 0
                            if (tabs != null) for (j in 0 until tabs.length()) if (!tabs.isNull(j)) tabsNonNull++
                            val resolved = vp || tabsNonNull > 0
                            if (resolved) everResolved = true
                            best = "rootKind=${o.optString("rootKind", "?")} resized=${o.optBoolean("resized")} " +
                                "topLevel=${o.optInt("topLevelId", -1)} activeTab=${o.optInt("activeTab", -1)} " +
                                "viewport=$vp tabsNonNull=$tabsNonNull/14 sidePanel=${!o.isNull("sidePanel")} " +
                                "minimap=${!o.isNull("minimap")} orbs=${!o.isNull("orbs")} chat=${!o.isNull("chat")}"
                            if (resolved) break
                        }
                    } catch (t: Throwable) { /* transient parse — retry */ }
                }
                Thread.sleep(1000)
            }
            when {
                everResolved -> Log.i(TAG, "ANDROID_A6_SEAM_GREEN fixed-mode chrome read resolved: $best")
                loggedIn -> Log.e(TAG, "ANDROID_A6_SEAM_RED loggedIn but read surface empty: $best — resizable-canvas fix required")
                else -> Log.e(TAG, "ANDROID_A6_SEAM_RED chrome snapshot never reached loggedIn (gate/login/boot?)")
            }
        }
    }

    /**
     * A4 host-seam self-test (census mode): prove the Android host can reach the plugin control plane
     * (MobileUiBridge) ACROSS the VM boundary on the rl-bridge thread — the path A4's UI will use (A-1b proved
     * the same call in-VM). Read-only: poll listPlugins() until the engine has booted and the -Drunelite.mobileui
     * gate has wired it, then log the received length + the event seq. (Write/toggle + the Compose UI are next.)
     */
    private fun runA4SeamSelfTest() {
        JvmLauncher.onBridge {
            var plugins = ""
            for (i in 0 until 90) {
                plugins = try { JvmLauncher.listPlugins() } catch (t: Throwable) { "" }
                if (plugins.length > 2) break
                Thread.sleep(1000)
            }
            val seq = try { JvmLauncher.mobileUiSeq() } catch (t: Throwable) { -1L }
            if (plugins.length <= 2) {
                Log.e(TAG, "ANDROID_A4_SEAM_RED listPlugins empty after 90s (mobileui gate / boot?)")
                return@onBridge
            }
            Log.i(TAG, "ANDROID_A4_SEAM_GREEN listPluginsLen=${plugins.length} mobileUiSeq=$seq")

            // A4 write path: toggle a currently-enabled plugin off->on (restoring it) and confirm the
            // round-trip. setPluginEnabled queues the start/stop on the EDT, so poll until the flag flips.
            try {
                val arr = org.json.JSONArray(plugins)
                var className: String? = null
                var was = true
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.optBoolean("enabled")) { className = o.getString("className"); was = true; break }
                }
                if (className == null && arr.length() > 0) {      // fallback: none enabled -> first plugin
                    className = arr.getJSONObject(0).getString("className")
                    was = arr.getJSONObject(0).optBoolean("enabled")
                }
                if (className == null) { Log.e(TAG, "ANDROID_A4_TOGGLE_RED no plugins to toggle"); return@onBridge }
                val seq0 = JvmLauncher.mobileUiSeq()
                val ack = JvmLauncher.setPluginEnabled(className, !was)
                val flipped = pollEnabled(className, !was, 8)
                val seq1 = JvmLauncher.mobileUiSeq()
                JvmLauncher.setPluginEnabled(className, was)       // restore original state
                val restored = pollEnabled(className, was, 8)
                if (flipped && restored) {
                    Log.i(TAG, "ANDROID_A4_TOGGLE_GREEN plugin=$className $was->${!was}->$was seq=$seq0->$seq1 ack=$ack")
                } else {
                    Log.e(TAG, "ANDROID_A4_TOGGLE_RED plugin=$className flipped=$flipped restored=$restored ack=$ack")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "ANDROID_A4_TOGGLE_RED parse/toggle threw: $t")
            }

            // A4 config READ surface: pull one plugin's typed config schema across the boundary — this is what
            // the config UI renders. (setConfig is wired and follows the same EDT write path as the toggle.)
            try {
                val arr2 = org.json.JSONArray(JvmLauncher.listPlugins())
                var group: String? = null
                for (i in 0 until arr2.length()) {
                    val o = arr2.getJSONObject(i)
                    if (o.optBoolean("hasConfig") && !o.isNull("configGroup")) { group = o.getString("configGroup"); break }
                }
                if (group != null) {
                    val cfg = JvmLauncher.getPluginConfig(group)
                    val wid = JvmLauncher.processedWriteId()
                    if (cfg.length > 2) Log.i(TAG, "ANDROID_A4_CONFIG_GREEN group=$group configLen=${cfg.length} writeId=$wid")
                    else Log.e(TAG, "ANDROID_A4_CONFIG_RED group=$group empty schema")
                } else {
                    Log.e(TAG, "ANDROID_A4_CONFIG_RED no plugin advertises a config group")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "ANDROID_A4_CONFIG_RED getPluginConfig threw: $t")
            }

            // A5 Tier-1 panel seam: open a plugin's Swing side-panel off-screen (CACIO) and pull its rendered
            // ARGB image across the VM boundary — the rendered-image fallback that works for any plugin panel.
            try {
                val parr = org.json.JSONArray(JvmLauncher.listPanels())
                if (parr.length() == 0) {
                    Log.e(TAG, "ANDROID_A5_SEAM_RED no panels listed (mobileui gate / safe-mode?)")
                } else {
                    val p0 = parr.getJSONObject(0)
                    val idx = p0.optInt("index", 0)
                    val tip = p0.optString("tooltip", "?")
                    JvmLauncher.openPanel(idx)
                    var px: IntArray? = null
                    var w = 0
                    var h = 0
                    for (i in 0 until 30) {
                        Thread.sleep(500)
                        w = JvmLauncher.panelWidth(); h = JvmLauncher.panelHeight(); px = JvmLauncher.panelPixels()
                        if (w > 0 && h > 0 && (px?.size ?: 0) >= w * h) break
                    }
                    val n = px?.size ?: 0
                    if (n > 0 && w > 0 && h > 0) {
                        Log.i(TAG, "ANDROID_A5_SEAM_GREEN panels=${parr.length()} panel='$tip' ${w}x${h} px=$n seq=${JvmLauncher.panelFrameSeq()}")
                    } else {
                        Log.e(TAG, "ANDROID_A5_SEAM_RED panels=${parr.length()} panel='$tip' ${w}x${h} px=$n")
                    }
                    JvmLauncher.closePanel()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "ANDROID_A5_SEAM_RED panel seam threw: $t")
            }

            // A5 Tier-2 Quest Helper seam (QuestHelperBridge). isAvailable() is false unless the Quest Helper
            // hub plugin is installed — BOTH outcomes prove the seam (the native screen falls back to Tier-1
            // when false). GREEN = the host reached the bridge and got parseable JSON across the boundary.
            try {
                val qAvail = JvmLauncher.questAvailable()
                JvmLauncher.questSetUiOpen(true)
                var qlist = "{}"
                for (i in 0 until 20) {
                    qlist = JvmLauncher.questListJson()
                    if (qlist.length > 2 && (!qAvail || qlist.contains("\"quests\""))) break
                    Thread.sleep(500)
                }
                val qobj = org.json.JSONObject(qlist)
                val qListAvail = qobj.optBoolean("available")
                val qn = qobj.optJSONArray("quests")?.length() ?: 0
                org.json.JSONObject(JvmLauncher.questActiveJson())   // parse-check the active surface too
                JvmLauncher.questSetUiOpen(false)
                Log.i(TAG, "ANDROID_A5_TIER2_QUEST_GREEN available=$qListAvail quests=$qn isAvailable=$qAvail")
            } catch (t: Throwable) {
                Log.e(TAG, "ANDROID_A5_TIER2_QUEST_RED quest seam threw: $t")
            }

            // A5 Tier-2 World Switcher seam (WorldSwitcherBridge). World Switcher is BUILT-IN and we're
            // LOGGED_IN, so expect available=true with a populated world list (setUiOpen triggers an async
            // WorldService refresh — poll until it lands). GREEN requires available=true && worlds>0.
            try {
                val wFlag = JvmLauncher.worldAvailable()
                JvmLauncher.worldSetUiOpen(true)
                var wAvail = false; var wn = 0; var wcur = -1
                for (i in 0 until 30) {
                    Thread.sleep(500)
                    val o = org.json.JSONObject(JvmLauncher.worldListJson())
                    wAvail = o.optBoolean("available")
                    wn = o.optJSONArray("worlds")?.length() ?: 0
                    wcur = o.optInt("current", -1)
                    if (wAvail && wn > 0) break
                }
                JvmLauncher.worldSetUiOpen(false)
                if (wAvail && wn > 0) {
                    Log.i(TAG, "ANDROID_A5_TIER2_WORLD_GREEN available=true worlds=$wn current=$wcur")
                } else {
                    Log.e(TAG, "ANDROID_A5_TIER2_WORLD_RED available=$wAvail worlds=$wn isAvailable=$wFlag")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "ANDROID_A5_TIER2_WORLD_RED world seam threw: $t")
            }
        }
    }

    /** Poll listPlugins() until [className]'s enabled flag == [want] (the toggle applies async on the EDT). */
    private fun pollEnabled(className: String, want: Boolean, secs: Int): Boolean {
        for (i in 0 until secs * 2) {
            try {
                val list = org.json.JSONArray(JvmLauncher.listPlugins())
                for (j in 0 until list.length()) {
                    val o = list.getJSONObject(j)
                    if (o.getString("className") == className) {
                        if (o.optBoolean("enabled") == want) return true
                        break
                    }
                }
            } catch (t: Throwable) { /* transient parse/read — retry */ }
            Thread.sleep(500)
        }
        return false
    }
}
