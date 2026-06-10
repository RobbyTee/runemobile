package dev.audaro.helloshell

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import android.view.WindowInsets
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.widget.FrameLayout
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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

    // AP-0 chat scroll-by-drag: a one-finger drag that STARTS inside the live chat box scrolls the chat
    // (finger-scroll, the iOS chatScrollAccum gesture) instead of rotating the camera. The rect is the
    // engine chatbox bounds (canvas px) from the per-frame chrome snapshot, so it tracks the relocate.
    private var chatScrolling = false
    private var chatScrollAccum = 0f
    private var chatRectCanvas: IntArray? = null   // [x,y,w,h] canvas px; null = chat not reported

    // A1 per-frame poll state (driven by Choreographer).
    private var vibrator: Vibrator? = null
    private var lastTapSeq = 0
    private var keyboardShown = false
    private var manualKeyboard = false   // ⌨ toggle: hold the soft keyboard up with no engine text field open
    private var frameCallback: Choreographer.FrameCallback? = null

    // A6 native chrome (OSRS-mobile 2-col tab grid; the engine stones are hidden and this grid replaces them).
    private lateinit var chromeOverlay: ChromeOverlayView
    private var chromeUiLogged = false
    private var lastStonesHidden: Boolean? = null   // desired HudVeneer hide-stones state (send only on change)
    private var lastSideX = Int.MIN_VALUE           // last side-panel relocation sent (send only on change)
    private var lastSideY = Int.MIN_VALUE
    private var lastChatX = Int.MIN_VALUE           // last chat-at-top relocation sent (send only on change)
    private var lastChatY = Int.MIN_VALUE

    // A3 enlarged native chat-tab veneer (bigger touch targets over the relocated engine chat row).
    private lateinit var chatTabOverlay: ChatTabOverlayView
    private var chatTabsLogged = false
    private var lastChatRowH = Int.MIN_VALUE        // last setChatTabRowHeight sent (send only on change)
    private var lastMmX = Int.MIN_VALUE             // last minimap relocation sent (send only on change)
    private var lastMmY = Int.MIN_VALUE

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
        // A6 native chrome: the OSRS-mobile 2-col tab grid at the bottom-right. The engine's own 14 tab stones
        // are HIDDEN (HudVeneerBridge.setStonesHidden, driven from updateChrome) so these native cells ARE the
        // tabs; a tap opens the tab by replaying the engine stone's op script (openTab) — works while hidden, so
        // the engine + plugins stay authoritative and no widget is moved or covered (the proven iOS layout).
        chromeOverlay = ChromeOverlayView(this)
        chromeOverlay.onTabTap = { idx ->
            Log.i(TAG, "a6: native tab $idx tap -> openTab")
            JvmLauncher.onBridge { JvmLauncher.openTab(idx) }   // off the UI thread (daemon-attaches to HotSpot)
        }
        // A3 enlarged native chat-tab veneer: bigger sprite-backed buttons over the relocated engine chat row. A
        // tap proxies a precise click onto the live engine button centre (selectMenuPoint = click + deferred
        // hover-clear, so no "Switch tab" tooltip stays parked) — the engine's own chat switching stays live.
        chatTabOverlay = ChatTabOverlayView(this)
        chatTabOverlay.onChatTab = { idx, ecx, ecy ->
            Log.i(TAG, "a3: chat tab $idx tap -> selectMenuPoint($ecx,$ecy)")
            JvmLauncher.onBridge { JvmLauncher.selectMenuPoint(ecx, ecy) }
        }
        // Long-press a chat tab -> the engine's filter menu (On/Filter/Off/Hide), via the shared press-and-peek
        // overlay. The overlay owns this gesture, so it forwards drag/release here to drive the highlight + commit.
        chatTabOverlay.onChatTabLongPressBegin = { _, ecx, ecy, vx, vy -> beginChatFilterMenu(ecx, ecy, vx, vy) }
        chatTabOverlay.onChatTabLongPressMove = { vx, vy ->
            menuCurX = vx; menuCurY = vy
            if (menuActive && menuOverlay.isActive) updateMenuHighlight(vx, vy)
        }
        chatTabOverlay.onChatTabLongPressEnd = { cancelled ->
            menuTouchActive = false
            if (cancelled) { if (menuActive) cancelMenu() }
            else if (menuOverlay.isActive) commitMenuSelection()
        }
        // A4 plugin UI: a full-screen Compose panel (GONE until opened) + a small launcher button. The panel
        // is a data screen (Compose); the live game overlays above stay classic Views (hybrid, §4.2).
        val pluginPanel = ComposeView(this).apply { visibility = View.GONE }
        // Compact, low-profile dev affordances (single-glyph): kill the Material 88dp min width + slim padding so
        // the three sit in a small bottom-left cluster instead of three chunky buttons across the HUD.
        fun devButton(glyph: String) = Button(this).apply {
            text = glyph
            minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            val px = (10 * resources.displayMetrics.density).toInt()
            setPadding(px, px / 2, px, px / 2)
            alpha = 0.7f
        }
        val pluginButton = devButton("⚙")   // gear
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
        // A6 A/B: toggle the native grid on/off (▦ = native tab grid + hidden stones; ▤ = raw engine stones).
        // updateChrome re-applies setStonesHidden to match, so flipping this reveals/hides the real stone row.
        val chromeButton = devButton("▦")
        chromeButton.setOnClickListener {
            chromeOverlay.setShown(!chromeOverlay.railsShown)
            chromeButton.text = if (chromeOverlay.railsShown) "▦" else "▤"
        }
        // A3: on-device Jagex login / switch account — runs the WebView OAuth, writes tokens.json, restarts.
        val signInButton = devButton("⎆")
        signInButton.setOnClickListener { startActivity(Intent(this, LoginActivity::class.java)) }
        // AP-0: persistent Log out — forget the Jagex session entirely (tokens + web cookies) and restart.
        // JX_* is snapshotted at JNI_CreateJavaVM, so only a clean process restart truly drops the session;
        // with no tokens the login-first boot gate then lands on the Jagex login. Mirrors the iOS onLogout.
        val logoutButton = devButton("⏻")
        logoutButton.setOnClickListener { confirmLogout() }
        // AP-0: manual ⌨ keyboard summon — composing public chat / PM has no engine text field for the
        // auto-raise poll to react to, so this forces the soft keyboard up (and keeps the poll from
        // instantly dismissing it). Tapping again returns to pure auto-raise. Mirrors the iOS ⌨ toggle.
        val kbButton = devButton("⌨")
        kbButton.setOnClickListener {
            manualKeyboard = !manualKeyboard
            kbButton.alpha = if (manualKeyboard) 1f else 0.7f
            Log.i(TAG, "ap0: manual keyboard ${if (manualKeyboard) "ON" else "OFF"}")
        }
        // Track G AG2: restart into GPU Route-C mode (force-enable the base gpu plugin, render the scene on GLES).
        val gpuButton = devButton("◆")
        gpuButton.setOnClickListener { restartIntoGpu() }
        // Track G AG3: restart into 117HD (routecrlhd) mode — the sideload-friendly trigger (Quest/SideQuest
        // installs can't easily push an rlhd.mode marker, but anyone can tap a button).
        val hdButton = devButton("HD")
        hdButton.setOnClickListener { restartIntoRlhd() }
        // Group the dev affordances into one discreet cluster pinned to the BOTTOM-LEFT — clear of the relocated
        // HUD (chat top-left, minimap top-right, tab grid + side panel bottom-right) so they don't obscure the UI.
        val devBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(pluginButton); addView(kbButton); addView(chromeButton); addView(signInButton)
            addView(logoutButton); addView(gpuButton); addView(hdButton)
        }
        val root = FrameLayout(this).apply {
            val mp = FrameLayout.LayoutParams.MATCH_PARENT
            addView(surfaceView, FrameLayout.LayoutParams(mp, mp))
            addView(chromeOverlay, FrameLayout.LayoutParams(mp, mp))  // native 2-col tab grid (stones hidden)
            addView(chatTabOverlay, FrameLayout.LayoutParams(mp, mp)) // enlarged native chat-tab row (top-left)
            addView(keyboardView, FrameLayout.LayoutParams(1, 1))
            addView(menuOverlay, FrameLayout.LayoutParams(mp, mp))   // drawn on top of the live frame + chrome
            addView(pluginPanel, FrameLayout.LayoutParams(mp, mp))   // Compose plugin list (GONE until opened)
            addView(devBar, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START).apply {
                    val m = (8 * resources.displayMetrics.density).toInt()
                    leftMargin = m; bottomMargin = m
                })
        }
        setContentView(root)
        // Edge-to-edge + fullscreen immersive: draw under (and hide) the status + nav bars so the game frame fills
        // the whole screen with no Android time/battery/wifi chrome. Re-asserted in onWindowFocusChanged (a dialog
        // or the soft keyboard can transiently reveal the bars). Cutout = shortEdges so a landscape notch is drawn
        // under (safe-area insets still report it, so the chat/grid placement clears it).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        enterImmersive()
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
                chatScrolling = false
            }
            true
        }

        startInputPolling()

        // Boot the VM once per process, off the UI thread and on the application context.
        // `adb ... --ez census true` arms the A-1b runtime gate-flip census (extra gates + the census probe).
        val censusMode = intent?.getBooleanExtra("census", false) ?: false
        // Track G AG1a: `adb ... --ez gpuSmoke true` boots the facade-only EGL smoke (no engine/tokens/login) to
        // probe GL.createCapabilities().OpenGL33==true through the facade on a live Android EGL context.
        // AG2 login diagnosis: a pushed `netprobe.mode` marker (--other-files) runs the network probe ONLY — does
        // the home-minted Jagex session authenticate from THIS device's IP? — then logs NETPROBE… and stops (no
        // JVM boot). Lets the Firebase device answer "can we mint/login from the datacenter?" before we build for it.
        if (File(getExternalFilesDir(null), "netprobe.mode").exists()) {
            val app = applicationContext
            thread(name = "netprobe") { NetProbe.run(app) }
            return
        }
        if (intent?.getBooleanExtra("gpuSmoke", false) == true) {
            if (booted.compareAndSet(false, true)) {
                val app = applicationContext
                thread(name = "jvm-boot") { bootGpuSmoke(app) }
            }
            return
        }
        // Track G AG2.5-b: `adb ... --ez rlhdprobe true` (or a pushed rlhdprobe.mode marker, so Firebase Robo can
        // trigger it without an intent extra) boots the embedded VM on [client-*-gpu.jar + client-*-rlhd.jar],
        // mainClass=RLHDProbe — a headless 117HD class-load + loader-data probe (no engine/bridge/render/login).
        // Proves the OpenCL stub + Zone facade delta resolve at class-load and the loader JSON parses on-device.
        if (intent?.getBooleanExtra("rlhdprobe", false) == true ||
            File(getExternalFilesDir(null), "rlhdprobe.mode").exists()) {
            if (booted.compareAndSet(false, true)) {
                val app = applicationContext
                thread(name = "jvm-boot") { bootRlhdProbe(app) }
            }
            return
        }
        // Track G AG2: `adb ... --ez routecgpu true` (or the dev "GPU" button, which restarts the process into
        // this) boots the FULL client on the GPU-variant jar with the gpu plugin force-enabled. Also trigger on a
        // pushed marker file (getExternalFilesDir/gpu.mode): Firebase Test Lab Robo launches the default activity
        // and can't pass an intent extra, but it CAN seed a file via --other-files. Falls through the login gate.
        val gpuMode = (intent?.getBooleanExtra("routecgpu", false) ?: false) ||
            File(getExternalFilesDir(null), "gpu.mode").exists()
        // Track G AG3-b: `--ez routecrlhd true` (or a pushed rlhd.mode marker) boots the FULL client on the gpu jar
        // + the rlhd jar with the 117HD (Zone) plugin loaded + started in-world (RLHDStarter). Needs login (tokens).
        // AG3-c: a Firebase Game Loop launch (action TEST_LOOP) also boots routecrlhd — Game Loop runs the app
        // UNINTERRUPTED (no Robo crawl/relaunch), giving the long startUp + Zone shader compile its minute on real Mali.
        val gameLoop = intent?.action == "com.google.intent.action.TEST_LOOP"
        // One-shot marker written by the in-app HD button before it restarts the process. Intent extras don't
        // reliably survive finishAffinity()+relaunch on every launcher (notably Quest's Horizon OS, which
        // relaunches the app from its stored shortcut intent and drops our extra) — a file read at boot does.
        // Consume it on read so HD mode applies to exactly this boot; the next normal launch is the CPU client.
        val rlhdOnce = File(getExternalFilesDir(null), "rlhd.once")
        val rlhdOnceSet = rlhdOnce.exists()
        if (rlhdOnceSet) { rlhdOnce.delete(); Log.i(TAG, "rlhd.once marker consumed -> routecrlhd this boot") }
        // A TV (Shield / Android TV) is a GPU-only target here — default its leanback-launcher icon straight into
        // 117HD (the only thing it's used for), so opening the app from the TV home screen is turnkey. A phone
        // still boots the CPU client by default (the HD button / intent opt-in unchanged). Opt out with a
        // cpu.mode marker if the CPU client is ever wanted on the TV.
        val isTv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            packageManager.hasSystemFeature("android.hardware.type.television")
        val cpuForced = File(getExternalFilesDir(null), "cpu.mode").exists()
        val rlhdMode = (intent?.getBooleanExtra("routecrlhd", false) ?: false) ||
            File(getExternalFilesDir(null), "rlhd.mode").exists() || gameLoop || rlhdOnceSet ||
            (isTv && !cpuForced)
        if (gameLoop) Log.i(TAG, "AG3-c: Game Loop launch -> routecrlhd (uninterrupted run for the Zone shader compile)")
        if (isTv && !cpuForced) Log.i(TAG, "TV/leanback device -> defaulting to routecrlhd (117HD) on launch")
        // A3 login-first boot gate: JX_* is snapshotted at JNI_CreateJavaVM, so a valid tokens.json must exist
        // BEFORE boot. With none (and not census, which uses pushed tokens), run the on-device Jagex login first;
        // it writes tokens.json then restarts the process for a clean boot (Review-2: login-first or restart).
        if (!censusMode && !rlhdMode && !JagexTokens.hasTokens(applicationContext) &&
            !LegacyCreds.hasCreds(applicationContext)
        ) {
            // AG3-c rlhd boots without tokens (the Zone shader compile is pre-login) — don't divert to LoginActivity.
            Log.i(TAG, "a3: no tokens.json — launching on-device Jagex login before boot")
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        if (booted.compareAndSet(false, true)) {
            val app = applicationContext
            thread(name = "jvm-boot") { bootJvm(app, censusMode, gpu = gpuMode && !rlhdMode, rlhd = rlhdMode) }
        }
    }

    /** Dev "GPU" button: restart the process into GPU Route-C mode (the embedded VM is one-per-process, so
     *  switching from the CPU jar to the GPU jar needs a clean boot — same mechanism as the login restart). */
    private fun restartIntoGpu() {
        val i = packageManager.getLaunchIntentForPackage(packageName)
        i?.putExtra("routecgpu", true)
        i?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(i)
        finishAffinity()
        Runtime.getRuntime().exit(0)
    }

    /** Dev "HD" button: restart the process into 117HD (routecrlhd) mode — same clean-boot mechanism as
     *  [restartIntoGpu]; RLHDStarter then loads + starts the Zone plugin at LOGIN_SCREEN/LOGGED_IN. */
    private fun restartIntoRlhd() {
        // Write a one-shot marker BEFORE restarting: some launchers (Quest's Horizon OS) relaunch the app from
        // its stored shortcut intent and drop the routecrlhd extra, so the extra alone isn't reliable on device.
        // The marker is filesystem state read + consumed at boot, so it survives the restart regardless.
        runCatching { File(getExternalFilesDir(null), "rlhd.once").writeText("1") }
        val i = packageManager.getLaunchIntentForPackage(packageName)
        i?.putExtra("routecrlhd", true)
        i?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(i)
        finishAffinity()
        Runtime.getRuntime().exit(0)
    }

    /** Log out is destructive (drops the Jagex session + kills the process) — confirm before doing it. */
    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("Log out")
            .setMessage("Forget this Jagex session? Deletes the saved login (tokens.json) and web cookies, then restarts to the sign-in screen.")
            .setPositiveButton("Log out") { _, _ -> doLogout() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** AP-0 logout: flush pending config (the restart kills the process), delete tokens.json, purge the
     *  WebView cookie store (so the IdP can't silently re-auth the old account), then restart into the
     *  login-first boot gate. Cookie removal is async — chain the restart off its callback. */
    private fun doLogout() {
        val purgeAndRestart = {
            val clean = JagexTokens.deleteAll(applicationContext)
            if (!clean) Log.w(TAG, "logout: a usable tokens.json source remains — session may return on next boot")
            CookieManager.getInstance().removeAllCookies {
                CookieManager.getInstance().flush()
                Log.i(TAG, "logout: tokens deleted + cookies purged — restarting into the login gate")
                val i = packageManager.getLaunchIntentForPackage(packageName)
                i?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(i)
                finishAffinity()
                Runtime.getRuntime().exit(0)
            }
        }
        if (booted.get()) {
            JvmLauncher.onBridge {
                val ack = JvmLauncher.flushConfig()
                Log.i(TAG, "logout: flushConfig -> $ack")
                runOnUiThread(purgeAndRestart)
            }
        } else {
            purgeAndRestart()
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
            // AP-0: a drag that STARTS inside the live chat box scrolls the chat instead of rotating the
            // camera (decided once, at gesture start, from the DOWN point — same rule as iOS).
            if (!cameraGestureActive && !chatScrolling) {
                val r = chatRectCanvas
                if (r != null && e1 != null) {
                    val gx = toGameX(e1.x); val gy = toGameY(e1.y)
                    if (gx >= r[0] && gx < r[0] + r[2] && gy >= r[1] && gy < r[1] + r[3]) {
                        chatScrolling = true
                        chatScrollAccum = 0f
                        Log.i(TAG, "ap0: chat scroll begin game=($gx,$gy)")
                    }
                }
                if (!chatScrolling) {
                    cameraGestureActive = true
                    JvmLauncher.clearHover()
                }
            }
            if (chatScrolling) {
                // Content follows the finger: finger dy = -distanceY, scaled to canvas px and accumulated so
                // sub-pixel drags aren't lost; whole px go to the chat's own scroll layer with the same
                // direction mapping as iOS (kChatScrollDir = -1, i.e. send -dy).
                val fh = JvmLauncher.frameHeight(); val vh = surfaceView.height
                val k = if (fh > 0 && vh > 0) fh.toFloat() / vh else 1f
                chatScrollAccum += -distanceY * k
                val whole = chatScrollAccum.toInt()
                if (whole != 0) {
                    chatScrollAccum -= whole
                    JvmLauncher.onBridge { JvmLauncher.scrollChat(-whole) }
                }
                return true
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

    /** Hide the Android status + navigation bars (sticky immersive) so the game frame is edge-to-edge with no
     *  system chrome. Transient bars come back on a swipe from the edge, then auto-hide. */
    private fun enterImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()   // re-assert after a dialog / keyboard transiently revealed the bars
    }

    /**
     * Per-frame poll of the engine read-surface: auto-raise/dismiss the soft keyboard when a text field gains/
     * loses focus, and fire a haptic tick when the interact-tap counter advances. Driven by Choreographer so it
     * tracks the display refresh without a dedicated thread.
     */
    private fun startInputPolling() {
        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val want = manualKeyboard || JvmLauncher.isTextInputOpen()
                // Compare against the ACTUAL IME visibility, not just our last request: the system can dismiss
                // the keyboard behind our back (focus change, back swipe), which would otherwise wedge a stale
                // cached state — manual mode then re-raises (sticky until ⌨ is toggled off). API<30 falls back
                // to the cached flag (no ime() inset type).
                val imeShown = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    surfaceView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) ?: keyboardShown
                } else {
                    keyboardShown
                }
                if (want != imeShown) {
                    if (want != keyboardShown) {
                        keyboardShown = want
                        Log.i(TAG, "a1: textInputOpen/manual=$want -> ${if (want) "raise" else "dismiss"} soft keyboard")
                    }
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    if (want) {
                        keyboardView.requestFocus()
                        imm.showSoftInput(keyboardView, InputMethodManager.SHOW_IMPLICIT)
                    } else {
                        imm.hideSoftInputFromWindow(keyboardView.windowToken, 0)
                    }
                } else if (want != keyboardShown) {
                    keyboardShown = want   // converged without a request (e.g. user-raised IME matches want)
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
     * A6: drive the native 2-col tab grid each frame from the render-thread-published MobileChromeBridge snapshot
     * (lock-free, no HotSpot attach). The grid only engages in the supported Classic/PRE_EOC layout (logged in +
     * toplevel 164); there it hides the engine stones (HudVeneerBridge.setStonesHidden) so the native cells ARE
     * the tabs, highlights the open tab, and relocates the engine side panel flush-left of the grid. In any other
     * layout it disengages — un-hides the engine stones + releases the side panel — so the real HUD renders (the
     * iOS LAYOUT GUARD). All bridge writes go through onBridge (off the UI thread) and fire only on state change.
     */
    private fun updateChrome() {
        if (!::chromeOverlay.isInitialized) return
        val json = JvmLauncher.chromeSnapshotPub()
        val o = try { JSONObject(json) } catch (e: Exception) { null }
        val loggedIn = o?.optBoolean("loggedIn") == true
        val topLevel = o?.optInt("topLevelId", -1) ?: -1
        val active = o?.optInt("activeTab", -1) ?: -1
        val supported = loggedIn && topLevel == 164      // Classic/PRE_EOC: the native grid's tabs map to real stones
        chromeOverlay.setGridEnabled(supported)
        chromeOverlay.setActiveTab(active)

        // AP-0 chat scroll-by-drag: cache the live chatbox bounds (canvas px) for the gesture-start hit test.
        // The snapshot reports the post-relocate bounds, so the scroll region tracks the chat-at-top move.
        chatRectCanvas = if (loggedIn) {
            o?.optJSONArray("chat")?.takeIf { it.length() >= 4 && it.optInt(2) > 0 && it.optInt(3) > 0 }
                ?.let { intArrayOf(it.optInt(0), it.optInt(1), it.optInt(2), it.optInt(3)) }
        } else null

        val gridOn = supported && chromeOverlay.railsShown
        applyStonesHidden(gridOn)
        applyChatTabs(gridOn)
        if (gridOn) {
            applySidePanel(o)
            applyChatAtTop()
            applyMinimap(o)
            if (!chromeUiLogged && chromeOverlay.gridLeftPx > 0f) {
                chromeUiLogged = true
                Log.i(TAG, "ANDROID_A6_UI_GREEN native 2-col tab grid drawn (engine stones hidden) topLevel=$topLevel activeTab=$active view=${surfaceView.width}x${surfaceView.height}")
            }
        } else {
            releaseSidePanel()
            releaseChat()
            releaseMinimap()
        }
    }

    /** Drive the enlarged native chat-tab veneer: feed it the published chat snapshot + game→view scale each
     *  frame, and tell the engine to push its message area below the taller native row ([on]) or restore the 23px
     *  default ([!on]). The row-height write is sent only on change (off the UI thread). */
    private fun applyChatTabs(on: Boolean) {
        val fw = JvmLauncher.frameWidth(); val fh = JvmLauncher.frameHeight()
        val vw = surfaceView.width; val vh = surfaceView.height
        val sx = if (fw > 0 && vw > 0) vw.toFloat() / fw else 1f
        val sy = if (fh > 0 && vh > 0) vh.toFloat() / fh else 1f
        val n = chatTabOverlay.update(if (on) JvmLauncher.chatSnapshotPub() else "", sx, sy, on)
        if (on && n > 0 && !chatTabsLogged) {
            chatTabsLogged = true
            Log.i(TAG, "ANDROID_A3_CHATTABS_GREEN enlarged native chat-tab row drawn ($n tabs) sx=$sx sy=$sy")
        }
        val wantRowH = if (on) ChatTabOverlayView.ROW_H_CANVAS else 0
        if (wantRowH != lastChatRowH) {
            lastChatRowH = wantRowH
            JvmLauncher.onBridge { JvmLauncher.setChatTabRowHeight(wantRowH); JvmLauncher.setChatAreaWidth(0) }
        }
    }

    /** Send the desired engine-stone hide state to HudVeneerBridge, only when it changes (off the UI thread). */
    private fun applyStonesHidden(hide: Boolean) {
        if (lastStonesHidden == hide) return
        lastStonesHidden = hide
        JvmLauncher.onBridge { JvmLauncher.setStonesHidden(hide) }
    }

    /** Relocate the engine side panel flush-left of the native grid (the iOS arrangement). The grid's left/top
     *  edge is in view px; convert to game-canvas px (k = frameW/viewW) and subtract the panel width. Sent only
     *  when it changes. No-op until the grid has laid out (gridLeftPx>0) and a frame size is known. */
    private fun applySidePanel(o: JSONObject?) {
        val fw = JvmLauncher.frameWidth(); val vw = surfaceView.width
        val gx0 = chromeOverlay.gridLeftPx; val gy0 = chromeOverlay.gridTopPx
        if (fw <= 0 || vw <= 0 || gx0 <= 0f) return
        val k = fw.toFloat() / vw.toFloat()
        var sideW = 204                                  // fallback until the panel is open + reports its width
        val sc = o?.optJSONArray("sideContainer")
        if (sc != null && sc.length() >= 4 && sc.optInt(2) > 0) sideW = sc.getInt(2)
        val sideX = maxOf(0, Math.round(gx0 * k) - sideW)   // right edge flush against the grid's left edge
        val sideY = maxOf(0, Math.round(gy0 * k))           // stones hidden, so top-align with the grid
        if (sideX == lastSideX && sideY == lastSideY) return
        lastSideX = sideX; lastSideY = sideY
        JvmLauncher.onBridge { JvmLauncher.relocateSidePanel(sideX, sideY) }
    }

    /** Stop relocating the engine side panel (let it return to its native position) when the grid disengages. */
    private fun releaseSidePanel() {
        if (lastSideX == -1 && lastSideY == -1) return
        lastSideX = -1; lastSideY = -1
        JvmLauncher.onBridge { JvmLauncher.relocateSidePanel(-1, -1) }
    }

    /** Move the engine's own chatbox to the top-left (inside the safe area) and flip it to OSRS transparent-chat
     *  mode — the mobile look. It stays the live engine chat (tabs / scrollback / colours / input all work); the
     *  engine tick re-applies it. Insets are in view px → canvas px (k = frameW/viewW); sent only when changed. */
    private fun applyChatAtTop() {
        val fw = JvmLauncher.frameWidth(); val fh = JvmLauncher.frameHeight()
        val vw = surfaceView.width; val vh = surfaceView.height
        if (fw <= 0 || fh <= 0 || vw <= 0 || vh <= 0) return
        val kx = fw.toFloat() / vw; val ky = fh.toFloat() / vh
        val ins = safeInsetsLTR()
        val margin = 8                                    // canvas-px breathing room from the safe edge
        val chatX = Math.round(ins[0] * kx) + margin
        val chatY = Math.round(ins[1] * ky) + margin
        if (chatX == lastChatX && chatY == lastChatY) return
        lastChatX = chatX; lastChatY = chatY
        JvmLauncher.onBridge { JvmLauncher.relocateChat(chatX, chatY, true) }
    }

    /** Stop relocating the chat (let it return to its native bottom position) when the chrome disengages. */
    private fun releaseChat() {
        if (lastChatX == -1 && lastChatY == -1) return
        lastChatX = -1; lastChatY = -1
        JvmLauncher.onBridge { JvmLauncher.relocateChat(-1, -1, false) }
    }

    /** [left, top, right] safe-area insets in VIEW px (display cutout + system bars), so the relocated HUD clears
     *  a landscape notch / nav bar. 0 on the full-bleed emulator. */
    private fun safeInsetsLTR(): IntArray {
        val wi = surfaceView.rootWindowInsets ?: return intArrayOf(0, 0, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val i = wi.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            intArrayOf(i.left, i.top, i.right)
        } else {
            @Suppress("DEPRECATION") intArrayOf(wi.stableInsetLeft, wi.stableInsetTop, wi.stableInsetRight)
        }
    }

    /** A7 edge-to-edge: pull the engine minimap+orbs cluster (MAP_CONTAINER) into the top-right safe area — flush
     *  to the right edge, top-aligned, clear of a notch. It stays the live HUD (minimap click-to-walk + orb clicks
     *  keep working — the iOS relocateEngineMinimap move). The cluster width comes from the snapshot. On change. */
    private fun applyMinimap(o: JSONObject?) {
        val fw = JvmLauncher.frameWidth(); val fh = JvmLauncher.frameHeight()
        val vw = surfaceView.width; val vh = surfaceView.height
        if (fw <= 0 || fh <= 0 || vw <= 0 || vh <= 0) return
        val kx = fw.toFloat() / vw; val ky = fh.toFloat() / vh
        val ins = safeInsetsLTR()
        val margin = 8
        var mapW = 211                                    // fallback cluster width until the snapshot reports it
        val mc = o?.optJSONArray("mapContainer")
        if (mc != null && mc.length() >= 4 && mc.optInt(2) > 0) mapW = mc.getInt(2)
        val mmX = maxOf(0, fw - mapW - Math.round(ins[2] * kx) - margin)
        val mmY = Math.round(ins[1] * ky) + margin
        if (mmX == lastMmX && mmY == lastMmY) return
        lastMmX = mmX; lastMmY = mmY
        JvmLauncher.onBridge { JvmLauncher.relocateMinimap(mmX, mmY) }
    }

    /** Stop relocating the minimap (let it return to its native position) when the chrome disengages. */
    private fun releaseMinimap() {
        if (lastMmX == -1 && lastMmY == -1) return
        lastMmX = -1; lastMmY = -1
        JvmLauncher.onBridge { JvmLauncher.relocateMinimap(-1, -1) }
    }

    private fun vibrateTick() {
        val v = vibrator ?: return
        // Guard the vibrate: it needs the VIBRATE permission and some devices restrict the vibrator outright — a
        // SecurityException here must never take down the UI thread (it once did, on the menu-highlight haptic).
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (e: Exception) {
            // haptics are best-effort; ignore
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

    /** A3: a native chat-tab long-press opens the engine's filter menu by right-clicking the LIVE engine chat
     *  button (engine canvas px, from the snapshot) and anchoring the native press-and-peek overlay at the finger
     *  (view px). Reuses the A2 menu machinery (tryBuildNativeMenu / updateMenuHighlight / commitMenuSelection),
     *  driven by the chat overlay's long-press callbacks (it owns the gesture). Mirrors the iOS onChatTabLongPress. */
    private fun beginChatFilterMenu(engineCx: Int, engineCy: Int, viewX: Float, viewY: Float) {
        if (menuActive) return
        menuAnchorX = viewX; menuAnchorY = viewY
        menuCurX = viewX; menuCurY = viewY
        menuHandRight = viewX > surfaceView.width * 0.5f
        menuActive = true; menuBuilding = true; menuTouchActive = true; menuHighlight = -1
        menuOpenAtNanos = System.nanoTime()
        JvmLauncher.click(engineCx, engineCy, 3)   // right-click the live engine chat button -> its filter menu
        Log.i(TAG, "a3: chat tab long-press -> filter menu game=($engineCx,$engineCy)")
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

    override fun onStop() {
        // A8: the app is backgrounded (and may be killed) — persist any pending plugin-config writes now so a
        // toggle/setting isn't lost. Off the UI thread (it dispatches to the client thread); fire-and-forget.
        if (booted.get()) JvmLauncher.onBridge {
            val ack = JvmLauncher.flushConfig()
            Log.i(TAG, "a8: flushConfig on background -> $ack")
        }
        super.onStop()
    }

    override fun onDestroy() {
        frameCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
        frameCallback = null
        super.onDestroy()
    }

    /**
     * Track G AG1a — boot the embedded VM with the facade-only `gpu-smoke.jar` ALONE on the classpath +
     * mainClass `RouteCSmoke` (`-Drunelite.routec.smoke`). The host (jvm_bootstrap) skips CACIO/engine, dlopen's
     * the librlawt EGL bridge + RegisterNatives the org.lwjgl.opengl/AWTContext facade, then runs the probe →
     * `ANDROID_AG1A` verdict (logcat jvm-stdout in debug). emulator-GREEN != AG1-GREEN (SwiftShader is lenient).
     */
    private fun bootGpuSmoke(app: android.content.Context) {
        val javaHome = AssetInstaller.ensureInstalled(app)
        val libjvm = File(javaHome, "lib/server/libjvm.so")
        val jvmDir = File(app.filesDir, "jvm")
        val smokeJar = File(jvmDir, "gpu-smoke.jar")
        // AssetInstaller only re-unpacks assets/jvm on a runtime.tag change, so a re-install without a tag bump
        // won't refresh the smoke jar. Copy it from assets if absent so the smoke is launchable on any install.
        if (!smokeJar.exists()) {
            runCatching {
                jvmDir.mkdirs()
                app.assets.open("jvm/gpu-smoke.jar").use { i -> smokeJar.outputStream().use { o -> i.copyTo(o) } }
            }.onFailure { Log.w(TAG, "AG1a: copy gpu-smoke.jar from assets failed: ${it.message}") }
        }
        if (!libjvm.exists() || !smokeJar.exists()) {
            Log.e(TAG, "AG1a: missing libjvm ($libjvm) or gpu-smoke.jar ($smokeJar — run android/dev-tools/gpu/build-smoke-jar.sh)")
            booted.set(false)
            return
        }
        val tmpDir = File(app.filesDir, "tmp").apply { mkdirs() }.absolutePath
        JvmLauncher.setStdioRedirect(BuildConfig.DEBUG)   // the probe prints ANDROID_AG1A to stdout -> logcat
        val opts = arrayOf("-Djava.io.tmpdir=$tmpDir", "-Drunelite.routec.smoke=true")
        Log.i(TAG, "AG1a: booting RouteCSmoke on the facade-only jar $smokeJar")
        val rc = JvmLauncher.startJvm(
            javaHome = javaHome.absolutePath,
            libjvmPath = libjvm.absolutePath,
            libraryPath = File(javaHome, "lib").absolutePath,
            classpath = smokeJar.absolutePath,
            mainClass = "RouteCSmoke",
            extraOpts = opts,
        )
        Log.i(TAG, "AG1a: startJvm rc=$rc (watch logcat: ANDROID_AG1A / librlawt / routec)")
    }

    /**
     * Track G AG2.5-b — boot the embedded VM on [client-*-gpu.jar (the facade-backed client) + client-*-rlhd.jar]
     * with mainClass RLHDProbe (`-Drunelite.rlhdprobe=true`). The host (jvm_bootstrap) does CACIO activation
     * (RLHD touches java.awt at class-init) but SKIPS the bridge/render — this is a headless class-load + loader-
     * data test, no GL context, no tokens, no login. RLHDProbe link/verifies every rs117.* class against the
     * facade, runs the OpenCL class-load graph, and parses the 9 bundled loader JSON files. Watch logcat for
     * ANDROID_RLHD_PROBE / MEGAVERDICT.
     */
    private fun bootRlhdProbe(app: android.content.Context) {
        val javaHome = AssetInstaller.ensureInstalled(app)
        val libjvm = File(javaHome, "lib/server/libjvm.so")
        if (!libjvm.exists()) { Log.e(TAG, "rlhdprobe: embedded JRE not installed ($libjvm)"); booted.set(false); return }
        val jvmDir = File(app.filesDir, "jvm")
        // The GPU + RLHD jars are added to assets without bumping runtime.tag, so AssetInstaller may not have
        // unpacked them — copy each from assets when absent (a fresh install copies them fine).
        for (suffix in listOf("-gpu.jar", "-rlhd.jar")) {
            val asset = runCatching { app.assets.list("jvm") }.getOrNull()
                ?.firstOrNull { it.startsWith("client-") && it.endsWith(suffix) }
            if (asset != null && !File(jvmDir, asset).exists()) {
                runCatching {
                    jvmDir.mkdirs()
                    app.assets.open("jvm/$asset").use { i -> File(jvmDir, asset).outputStream().use { o -> i.copyTo(o) } }
                }.onFailure { Log.w(TAG, "rlhdprobe: copy $asset from assets failed: ${it.message}") }
            }
        }
        val gpuJar = jvmDir.list { _, n -> n.startsWith("client-") && n.endsWith("-gpu.jar") }?.firstOrNull()
        val rlhdJar = jvmDir.list { _, n -> n.startsWith("client-") && n.endsWith("-rlhd.jar") }?.firstOrNull()
        if (gpuJar == null || rlhdJar == null) {
            Log.e(TAG, "rlhdprobe: missing jar(s) gpu=$gpuJar rlhd=$rlhdJar in $jvmDir " +
                "(run android/dev-tools/gpu/stage-android-gpu-jar.sh + stage-rlhd-zone-jar.sh)")
            booted.set(false); return
        }
        val classpath = listOf(gpuJar, rlhdJar, "probes.jar").joinToString(":") { File(jvmDir, it).absolutePath }
        val libraryPath = File(javaHome, "lib").absolutePath
        val home = app.filesDir.absolutePath
        val tmpDir = File(app.filesDir, "tmp").apply { mkdirs() }.absolutePath
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
            "-Drunelite.rlhdprobe=true",
        )
        JvmLauncher.setStdioRedirect(true)   // probe prints its verdict to stdout -> logcat
        Log.i(TAG, "rlhdprobe: starting HotSpot RLHDProbe, cp=$classpath")
        val rc = JvmLauncher.startJvm(
            javaHome = javaHome.absolutePath,
            libjvmPath = libjvm.absolutePath,
            libraryPath = libraryPath,
            classpath = classpath,
            mainClass = "RLHDProbe",
            extraOpts = opts,
        )
        if (rc != 0) booted.set(false)
        Log.i(TAG, "rlhdprobe: startJvm rc=$rc (watch logcat: ANDROID_RLHD_PROBE / MEGAVERDICT)")
    }

    private fun bootJvm(app: android.content.Context, census: Boolean = false, gpu: Boolean = false, rlhd: Boolean = false) {
        val javaHome = AssetInstaller.ensureInstalled(app)
        val libjvm = File(javaHome, "lib/server/libjvm.so")
        if (!libjvm.exists()) {
            Log.e(TAG, "embedded JRE not installed; expected $libjvm")
            booted.set(false)   // not booted yet — let a later launch retry the asset unpack
            return
        }

        val jvmDir = File(app.filesDir, "jvm")
        // GPU + 117HD modes boot the facade-backed jar (client-<ver>-gpu.jar: lwjgl/rlawt/gpu stripped + facade); the
        // CPU boot uses the pristine client-<ver>-shaded.jar. 117HD ALSO needs client-<ver>-rlhd.jar (rs117.* + the
        // shaders/JSON) appended to the classpath. The jars carry the project version, so glob by suffix (a version
        // bump needs no code edit). AssetInstaller only unpacks assets/jvm on a runtime.tag change, so a jar may be
        // absent on an install that didn't bump the tag — copy each from assets if so.
        val useGpuJar = gpu || rlhd
        val jarSuffix = if (useGpuJar) "-gpu.jar" else "-shaded.jar"
        run {
            val wanted = mutableListOf<String>()
            if (useGpuJar) wanted.add("-gpu.jar")
            if (rlhd) wanted.add("-rlhd.jar")
            for (suffix in wanted) {
                val asset = runCatching { app.assets.list("jvm") }.getOrNull()
                    ?.firstOrNull { it.startsWith("client-") && it.endsWith(suffix) }
                if (asset != null && !File(jvmDir, asset).exists()) {
                    runCatching {
                        jvmDir.mkdirs()
                        app.assets.open("jvm/$asset").use { i -> File(jvmDir, asset).outputStream().use { o -> i.copyTo(o) } }
                    }.onFailure { Log.w(TAG, "boot: copy $asset from assets failed: ${it.message}") }
                }
            }
        }
        val clientJar = jvmDir.list { _, name -> name.startsWith("client-") && name.endsWith(jarSuffix) }
            ?.firstOrNull()
        if (clientJar == null) {
            Log.e(TAG, "no client-*$jarSuffix staged in $jvmDir" +
                if (useGpuJar) " (run android/dev-tools/gpu/stage-android-gpu-jar.sh)" else "")
            booted.set(false)
            return
        }
        val rlhdJar = if (rlhd) jvmDir.list { _, name -> name.startsWith("client-") && name.endsWith("-rlhd.jar") }?.firstOrNull() else null
        if (rlhd && rlhdJar == null) {
            Log.e(TAG, "no client-*-rlhd.jar staged in $jvmDir (run android/dev-tools/gpu/stage-rlhd-zone-jar.sh)")
            booted.set(false)
            return
        }
        val classpath = (listOf(clientJar) + listOfNotNull(rlhdJar) + listOf("probes.jar"))
            .joinToString(":") { File(jvmDir, it).absolutePath }
        val libraryPath = File(javaHome, "lib").absolutePath

        // A writable RuneLite home + headless capture dir (the gamepack cache + PNG fallback evidence).
        val home = app.filesDir.absolutePath
        // AG3-c: in rlhd mode put the capture dir on EXTERNAL storage so Firebase --directories-to-pull can fetch the
        // 117HD-rendered frames (the composited output, written by HeadlessCapture once isGpu=true).
        val captureBase = if (rlhd) (app.getExternalFilesDir(null) ?: app.filesDir) else app.filesDir
        val captureDir = File(captureBase, "rl-capture").apply { mkdirs() }.absolutePath
        // Android's default java.io.tmpdir is /tmp (absent in the app sandbox); Font.createFont stages a
        // temp file there and fails with "Problem reading font data", poisoning FontManager.<clinit>. Point
        // it at a writable dir so the gamepack fonts load.
        val tmpDir = File(app.filesDir, "tmp").apply { mkdirs() }.absolutePath

        // A7 resizable (full-screen): force the gamepack into RESIZABLE display mode so HeadlessResize's wider
        // canvas FILLS instead of pillarboxing. The mode is a single byte (byte[3]: 1=fixed, 2=resizable) in the
        // OSRS legacy prefs blob, which the gamepack reads at startup and persists. Patch/seed it before startJvm.
        ensureResizableDisplayMode(File(app.filesDir, ".runelite/jagexcache/oldschool/LIVE/preferences.dat"))

        // JX_* launcher handoff: if a tokens.json is present, set the JX_* env (before startJvm) and arm
        // HeadlessLogin's EULA->Play Now click machine. No tokens.json -> login-screen render only. The setenv
        // must happen here, before JvmLauncher.startJvm creates the VM (HotSpot snapshots env at startup).
        val autologinArmed = JagexTokens.loadAndArm(app)
        // Legacy (non-Jagex) username/password fallback: arms HeadlessLogin's legacy stage instead. A
        // tokens.json session wins when both exist. Same pre-startJvm setenv constraint as JX_*.
        val legacyArmed = !autologinArmed && LegacyCreds.loadAndArm(app)

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
            "-Drunelite.mobilechrome=true",         // A6: publish the read-only chrome snapshot (tab-stone bounds)
            "-Drunelite.hudveneer=true",            // A6: the chrome write surface (hide stones / openTab / relocate)
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
        // 117HD auto-login: on a real home-network device login works, so autologin straight in-world (RLHDStarter
        // engages at LOGGED_IN too) — the desired turnkey launch. SKIP it only for the Firebase DATACENTER pre-login
        // case (an explicit rlhd_prelogin.mode marker): there the world connect is WAF-blocked and autologin would
        // churn at state=UNKNOWN forever instead of settling at LOGIN_SCREEN, so the Zone shader-compile proof needs
        // the clean login screen. Default (no marker) = autologin, including in routecrlhd.
        val rlhdPreLogin = rlhd && File(app.getExternalFilesDir(null), "rlhd_prelogin.mode").exists()
        if (autologinArmed && !rlhdPreLogin) {
            opts.addAll(JagexTokens.autologinOpts)
            Log.i(TAG, "autologin ARMED (tokens.json present)${if (rlhd) " [routecrlhd -> RLHDStarter loads 117HD at LOGGED_IN]" else ""}: EULA 302,312 -> Play Now 382,248, world 301")
        } else if (legacyArmed && !rlhdPreLogin) {
            opts.addAll(JagexTokens.autologinOpts)
            opts.add("-Drunelite.headless.autologin.legacy=true")
            Log.i(TAG, "LEGACY autologin ARMED (legacy_login.json present): username/password form, world 301")
        } else if (rlhd) {
            Log.i(TAG, "routecrlhd: autologin SKIPPED (rlhd_prelogin marker -> settle at LOGIN_SCREEN for the datacenter shader compile)")
        }
        if (census) {
            // A-1b runtime gate-flip census: turn on the remaining read-surface gates (menupeek + autologin
            // are already on above) and arm the one-shot GateCensus probe in DeviceHeadlessBoot.
            opts.addAll(listOf(
                "-Drunelite.gatecensus=true",
                "-Drunelite.huddata=true",
            ))
            Log.i(TAG, "A-1b census mode: +huddata + gatecensus (mobileui/mobilechrome/hudveneer/resize always-on)")
        }
        if (gpu) {
            // Arm RouteCGpuStarter (force-enable + start the gpu plugin once in-world) and the frame-dump
            // diagnostic so the AG2 device gate gets pullable colour/flip evidence. The bridge itself is
            // registered natively (jvm_bootstrap sees -Drunelite.routecgpu and dlopen's librlawt after VM create).
            // Dump to app-private EXTERNAL storage so Firebase Test Lab's --directories-to-pull (and adb pull)
            // can fetch the frames; fall back to internal filesDir if external is unavailable.
            val dumpDir = (app.getExternalFilesDir(null) ?: app.filesDir).absolutePath   // +"/routec-frames/"
            opts.addAll(listOf(
                "-Drunelite.routecgpu=true",
                "-Drunelite.gpuframedump=true",
                "-Drunelite.gpuframedump.dir=$dumpDir",
            ))
            Log.i(TAG, "AG2 GPU mode: gpu jar $clientJar, routecgpu + frame-dump armed (dir=$dumpDir/routec-frames)")
        }
        if (rlhd) {
            // AG3-b: arm RLHDStarter (load + start the 117HD Zone plugin once in-world; it disables the base gpu
            // plugin via its conflicts="GPU"). librlawt is registered natively (jvm_bootstrap sees routecrlhd). Frame
            // dump to app-EXTERNAL storage so the HD frame is pullable (Firebase --directories-to-pull / adb pull).
            val dumpDir = (app.getExternalFilesDir(null) ?: app.filesDir).absolutePath
            opts.addAll(listOf(
                "-Drunelite.routecrlhd=true",
                "-Drunelite.gpuframedump=true",
                "-Drunelite.gpuframedump.dir=$dumpDir",
            ))
            Log.i(TAG, "AG3-b 117HD mode: gpu jar $clientJar + rlhd jar $rlhdJar, routecrlhd + frame-dump armed (dir=$dumpDir/routec-frames)")
        }

        // A8: only pipe the embedded VM's stdout/err to logcat in DEBUG builds — a release build must not leak
        // the engine's output (account/network chatter). Must be set before startJvm (it redirects fd 1/2 there).
        JvmLauncher.setStdioRedirect(BuildConfig.DEBUG)

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
     * A7 resizable: force the OSRS gamepack into RESIZABLE display mode by setting the window-mode byte in its
     * legacy prefs blob (preferences.dat byte[3]: 1=fixed, 2=resizable — verified by byte-diffing a fixed vs an
     * iOS-resizable file). The gamepack reads this at startup and keeps it (it normalizes other bytes but honors
     * byte[3]). Patch it if the file exists; seed a minimal resizable one if not (fresh install). Idempotent;
     * runs before startJvm. With this + HeadlessResize's wider canvas, the scene fills the screen (PRE_EOC modern)
     * instead of pillarboxing 765x503 — no per-account/server setting needed (the mode is client-side per install).
     */
    private fun ensureResizableDisplayMode(f: File) {
        try {
            if (f.exists() && f.length() >= 4) {
                val b = f.readBytes()
                if (b[3] != 0x02.toByte()) {
                    b[3] = 0x02
                    f.writeBytes(b)
                    Log.i(TAG, "a7: preferences.dat patched fixed->resizable (byte3=2)")
                } else {
                    Log.i(TAG, "a7: preferences.dat already resizable")
                }
            } else {
                f.parentFile?.mkdirs()
                // Minimal valid OSRS preferences.dat with window mode = resizable (byte[3]=02).
                f.writeBytes(byteArrayOf(
                    0x0c, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00, 0x32,
                    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x19, 0x00))
                Log.i(TAG, "a7: seeded resizable preferences.dat (fresh)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "a7: ensureResizableDisplayMode failed: ${e.message}")
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
