package dev.audaro.helloshell

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.provider.MediaStore
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
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
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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

    // Phase-5 world map (floating window): open-state + control rects from the published map snapshot.
    // While open, a one-finger drag that STARTS inside the window pans the MAP (shared panWorldMap — finger
    // deltas in canvas px; synthetic drags don't pan, probe-proven), taps inside are PRECISE clicks (close X,
    // zoom, POIs — no walk ring), and a search-rect tap raises the IME manually (the map search never flips
    // the engine INPUT_TYPE). The engine HUD shoves while open are all shared-side.
    private var mapOpen = false
    private var mapRectCanvas: IntArray? = null     // pannable map display [x,y,w,h] (canvas px)
    private var minimapRectCanvas: IntArray? = null // relocated minimap+orb cluster [x,y,w,h] canvas px; null = not relocated
    private var mapSearchCanvas: IntArray? = null   // search field rect
    // The window CHROME (close X, zoom, search bar) extends OUTSIDE the pannable display rect — the
    // snapshot publishes each control's rect separately and taps on them must also route precise.
    private var mapCloseCanvas: IntArray? = null
    private var mapZoomInCanvas: IntArray? = null
    private var mapZoomOutCanvas: IntArray? = null
    private var mapPanning = false
    private var mapPanAccumX = 0f; private var mapPanAccumY = 0f
    // FIX-4 inventory item-drag: a one-finger drag that STARTS inside the inventory grid drags the item (touch
    // PRESS->DRAGGED->RELEASE) instead of rotating the camera. invRectCanvas = the live inventory bounds (canvas
    // px) from the chrome snapshot; itemDragLast = the last valid drag point, so a release in the letterbox still
    // closes the engine WidgetDrag (no stuck held item).
    private var invRectCanvas: IntArray? = null
    private var itemDragging = false
    private var itemDragLastX = 0; private var itemDragLastY = 0
    // DRAG-1b: the engine's other drag-to-reorder panes (Prayer/Spellbook/Bank) as canvas-px rects from the chrome
    // snapshot. A drag that STARTS inside any of these routes through the same engine WidgetDrag stream as the
    // inventory (touch PRESS->DRAGGED->RELEASE) so prayers/spells/bank items reorder instead of rotating the camera.
    private var reorderRectsCanvas: List<IntArray> = emptyList()
    private var mapSearchKeyboard = false           // transient ⌨-like latch while the map search is in use
    private var mapWasSearching = false             // FIX-14: engine WORLDMAP_SEARCHING edge (drop the latch on commit)
    private var lastMapSnapshot = ""

    // A1 per-frame poll state (driven by Choreographer).
    private var vibrator: Vibrator? = null
    private var lastTapSeq = 0

    // Phase-1 click ring: every world tap spawns a ring whose colour is decided by ENGINE TRUTH, not at touch
    // time — the engine-resolved tap machine settles ~80–160 ms after the tap, so showing instantly would
    // misclassify (the iOS lesson). On tap we snapshot interactTapSeq; the Choreographer poll then resolves:
    // seq advanced within the grace window → red (interaction), grace expired → yellow (walk).
    private lateinit var tapRing: TapRingView
    private var ringPending = false
    private var ringX = 0f; private var ringY = 0f
    private var ringArmedAtMs = 0L
    private var ringBaseSeq = 0
    private var keyboardShown = false
    private var imeWasShown = false       // last frame's ACTUAL ime visibility — to spot a user-driven dismiss
    private var manualKeyboard = false   // ⌨ toggle: hold the soft keyboard up with no engine text field open
    private var keyboardToggleBtn: Button? = null   // the ⌨ button, so a dismiss can reset its on/off alpha
    // FIX-11: a rendered Swing panel's text field is focused and its OWN Compose mirror field owns the soft
    // keyboard — the per-frame poll must NOT raise GameInputView (steals focus) or dismiss the IME (kills it).
    @Volatile var textActivePanel = false
    private var shiftHeld = false         // ⇧ latch: synthetic VK_SHIFT held (AP-2a Finding 12)
    private var ctrlHeld = false          // ⌃ latch: synthetic VK_CONTROL held (mirror of shift)
    private var altHeld = false           // ⌥ latch: synthetic VK_ALT held (Ground Items hold-to-highlight)
    private var frameCallback: Choreographer.FrameCallback? = null

    // AP-2a Findings 9/25: a low-frequency daemon that drains the shared notification + link queues (off the UI
    // thread — these calls daemon-attach to HotSpot). nextNotification → system shade; nextLink → browser.
    @Volatile private var drainRun = false
    private var notifId = 1000

    // A6 native chrome (OSRS-mobile 2-col tab grid; the engine stones are hidden and this grid replaces them).
    private lateinit var chromeOverlay: ChromeOverlayView
    private var chromeUiLogged = false
    private var lastStonesHidden: Boolean? = null   // desired HudVeneer hide-stones state (send only on change)
    private var lastSideX = Int.MIN_VALUE           // last side-panel relocation sent (send only on change)
    private var lastSideY = Int.MIN_VALUE
    private var lastChatX = Int.MIN_VALUE           // last chat-at-top relocation sent (send only on change)
    private var lastChatY = Int.MIN_VALUE

    // A3 enlarged native chat-tab veneer (bigger touch targets over the relocated engine chat row).
    private lateinit var chatTabOverlay: ChatPillView
    // UI-4: opaque NPC-dialogue parchment over the (transparent) engine dialogue.
    private lateinit var dialogueOverlay: DialogueView
    // FIX-9: a native "Reconnecting…" scrim shown while the headless auto-reconnect recovers a dropped session.
    private lateinit var reconnectOverlay: ReconnectingOverlayView
    // DEBUG-only force for the reconnect overlay (real network drops are hard to repro over Wi-Fi adb). null =
    // follow the live snapshot; true/false = force shown/hidden. Driven by the SET_RECONNECT_OVERLAY broadcast.
    @Volatile private var reconnectTestOverride: Boolean? = null
    // Phase-4: native-tracked chat collapse state (the shared side shoves the live widget off-canvas; this
    // mirrors what we last asked for — the engine never collapses on its own).
    private var chatCollapsed = false
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
    // FIX-10b tap-mode (submenu drill-in): a persistent, tap-to-select menu used when an entry has a submenu
    // (Menu Entry Swapper swap lists) or ⇧ is latched. menuDrillParent = the top-level engine index we drilled
    // into (-1 = top level); menuTopEntries = the saved top level for '‹ Back'; menuTapArmed = the opening
    // long-press has been released (so subsequent discrete taps drive selection, not the release itself).
    private var menuTapMode = false
    private var menuTapArmed = false
    private var menuDrillParent = -1
    private var menuTopEntries: List<MenuOverlayView.Entry> = emptyList()

    companion object {
        private const val TAG = "MainActivity"
        private val booted = AtomicBoolean(false)

        // Engine canvas dimensions (set once at the boot HeadlessResize below; stable in-world). The overlay
        // free-drag UI reads these to map a drag-pad fraction → canvas pixels for OverlayBridge.setOverlayPreferredLocation.
        @Volatile var canvasW = 0
        @Volatile var canvasH = 0

        private const val NOTIF_CHANNEL = "runelite_game"
        private const val REQ_POST_NOTIFICATIONS = 7001

        // AWT virtual-key codes (java.awt.event.KeyEvent) the engine's onKeyStroke expects.
        private const val VK_BACK_SPACE = 8
        private const val VK_ENTER = 10
        private const val VK_LEFT = 37
        private const val VK_UP = 38
        private const val VK_RIGHT = 39
        private const val VK_DOWN = 40

        // Pinch-to-wheel: geometric step a scale ratio must cross to emit one wheel notch.
        private const val PINCH_STEP = 1.08f
        // Click-ring truth grace: how long after a tap we wait for interactTapSeq before calling it a walk.
        // Matches iOS (250 ms). The old 600 ms was tuned for a slow emulator EDT (interaction at +535 ms), but on
        // real hardware the settle gate stabilises in 3–6 frames and an interaction bumps interactTapSeq at
        // ~65–140 ms (touch-diag measured on a Pixel 7a) — so 600 ms just made every WALK ring appear a visible
        // ~600 ms after the tap, reading as input lag. 250 ms catches real interactions with margin and the walk
        // ring is prompt; a pathologically slow interaction (rare MAX_SETTLE) at worst flashes the wrong colour.
        private const val RING_GRACE_MS = 250L

        // OSRS engine menu geometry: 19px "Choose Option" header, 15px per row. A native row k commits to the
        // engine row centre (menuX + menuW/2, menuY + 19 + k*15 + 7) so plugin onClick/Menu-Entry-Swapper hold.
        private const val MENU_HEADER_PX = 19
        private const val MENU_ROW_PX = 15
    }

    @SuppressLint("ClickableViewAccessibility")
    // FIX-13: debug-only live 117HD config flip, for a thermal-fair fps A/B (apply both presets seconds apart at
    // one thermal/crowd/camera state — no reboot). All three keys apply live via HdPlugin.onConfigChanged.
    //   adb shell am broadcast -a dev.audaro.helloshell.SET_HD_CONFIG --es preset new   (85/8/25)
    //   adb shell am broadcast -a dev.audaro.helloshell.SET_HD_CONFIG --es preset base  (100/16/30, RLHD defaults)
    //   adb shell am broadcast -a dev.audaro.helloshell.SET_HD_CONFIG --es key drawDistance --es value 20
    private var hdConfigReceiver: BroadcastReceiver? = null
    // FIX-9: debug-only force for the "Reconnecting…" overlay (a real in-session drop is hard to repro over
    // Wi-Fi adb, which a network toggle also kills) — lets a screencap verify the scrim renders.
    //   adb shell am broadcast -a dev.audaro.helloshell.SET_RECONNECT_OVERLAY --es show on     (force shown)
    //   adb shell am broadcast -a dev.audaro.helloshell.SET_RECONNECT_OVERLAY --es show off    (force hidden)
    //   adb shell am broadcast -a dev.audaro.helloshell.SET_RECONNECT_OVERLAY --es show clear  (follow live state)
    private var reconnectTestReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

        if (BuildConfig.DEBUG) {
            hdConfigReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    intent ?: return
                    val pairs: List<Pair<String, String>> = when (intent.getStringExtra("preset")) {
                        "new" -> listOf("sceneResolutionScale" to "85", "anisotropicFilteringLevel" to "8", "drawDistance" to "25")
                        "base" -> listOf("sceneResolutionScale" to "100", "anisotropicFilteringLevel" to "16", "drawDistance" to "30")
                        else -> {
                            val k = intent.getStringExtra("key"); val v = intent.getStringExtra("value")
                            if (k != null && v != null) listOf(k to v) else emptyList()
                        }
                    }
                    if (pairs.isEmpty()) { Log.w(TAG, "SET_HD_CONFIG: need --es preset new|base, or --es key K --es value V"); return }
                    JvmLauncher.onBridgeTouch { pairs.forEach { (k, v) -> JvmLauncher.setConfig("hd", k, v) } }
                    Log.i(TAG, "SET_HD_CONFIG applied live: $pairs")
                }
            }
            ContextCompat.registerReceiver(
                this, hdConfigReceiver, IntentFilter("dev.audaro.helloshell.SET_HD_CONFIG"),
                ContextCompat.RECEIVER_EXPORTED,
            )
            reconnectTestReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    reconnectTestOverride = when (intent?.getStringExtra("show")) {
                        "on" -> true
                        "off" -> false
                        else -> null   // "clear" / anything else -> follow the live snapshot
                    }
                    Log.i(TAG, "SET_RECONNECT_OVERLAY override=$reconnectTestOverride")
                }
            }
            ContextCompat.registerReceiver(
                this, reconnectTestReceiver, IntentFilter("dev.audaro.helloshell.SET_RECONNECT_OVERLAY"),
                ContextCompat.RECEIVER_EXPORTED,
            )
        }

        // AP-2a Finding 9: a notification channel for in-game alerts (idle, level-up, etc.) + the runtime
        // POST_NOTIFICATIONS prompt on API 33+ (silently granted below that). The drain loop delivers to it.
        setupNotifications()

        // SurfaceView for the live game frame; a tiny hidden input view to host the soft keyboard's
        // InputConnection (the SurfaceView itself can't be a text editor). FrameLayout stacks them.
        surfaceView = SurfaceView(this)
        keyboardView = GameInputView(this)
        menuOverlay = MenuOverlayView(this)
        tapRing = TapRingView(this)
        // A6 native chrome: the OSRS-mobile 2-col tab grid at the bottom-right. The engine's own 14 tab stones
        // are HIDDEN (HudVeneerBridge.setStonesHidden, driven from updateChrome) so these native cells ARE the
        // tabs; a tap opens the tab by replaying the engine stone's op script (openTab) — works while hidden, so
        // the engine + plugins stay authoritative and no widget is moved or covered (the proven iOS layout).
        chromeOverlay = ChromeOverlayView(this)
        chromeOverlay.onTabTap = { idx ->
            Log.i(TAG, "a6: native tab $idx tap -> openTab")
            JvmLauncher.onBridgeTouch { JvmLauncher.openTab(idx) }   // off the UI thread (daemon-attaches to HotSpot)
        }
        // Phase-4 chat channel PILL (replaces the A3 enlarged tab row as the chat control; iOS parity).
        // Quick tap = collapse/expand (shared shove-off-canvas); hold 0.15s = chips fan out; drag + lift =
        // switch channel (op-script replay, works collapsed); pull DOWN past a filterable chip = its engine
        // filter menu via the shared press-and-peek overlay.
        chatTabOverlay = ChatPillView(this)
        chatTabOverlay.onTabTap = { k ->
            // UI-3 (iOS onChatTabButton): tap the ACTIVE tab to collapse the message area; tap any other to
            // switch the channel (auto-expanding a collapsed chat first).
            if (!chatCollapsed && k == chatTabOverlay.currentTab) {
                chatCollapsed = true
                Log.i(TAG, "ui3: tab $k (active) -> collapse")
                JvmLauncher.onBridgeTouch { JvmLauncher.setChatCollapsed(true) }
            } else {
                chatTabOverlay.markOptimistic(k)   // instant highlight
                if (chatCollapsed) {
                    chatCollapsed = false
                    JvmLauncher.onBridgeTouch { JvmLauncher.setChatCollapsed(false); JvmLauncher.switchChatTab(k) }
                } else {
                    JvmLauncher.onBridgeTouch { JvmLauncher.switchChatTab(k) }
                }
                Log.i(TAG, "ui3: tab $k -> switch")
            }
        }
        chatTabOverlay.onFilterOpen = { _, ecx, ecy, vx, vy ->
            if (chatCollapsed) {
                // The collapsed chat's filter button sits off-canvas (y≈3000) — a right-click there opens
                // nothing. Expand first; the engine restores the widget next tick, the snapshot follows, and a
                // later move (finger still below the chip) passes the on-canvas gate below. iOS 69ed4cf9e.
                chatCollapsed = false
                JvmLauncher.onBridgeTouch { JvmLauncher.setChatCollapsed(false) }
                Log.i(TAG, "p4: pull-down on collapsed chat -> expand first")
                false
            } else if (ecx >= 0 && ecy in 0 until JvmLauncher.frameHeight()) {
                beginChatFilterMenu(ecx, ecy, vx, vy)
                true
            } else false   // engine button not on-canvas yet (expand still landing) — retried each move
        }
        chatTabOverlay.onFilterMove = { vx, vy ->
            menuCurX = vx; menuCurY = vy
            if (menuActive && menuOverlay.isActive) updateMenuHighlight(vx, vy)
        }
        chatTabOverlay.onFilterEnd = { cancelled ->
            menuTouchActive = false
            if (cancelled) { if (menuActive) cancelMenu() }
            else if (menuOverlay.isActive) commitMenuSelection()
        }
        chatTabOverlay.onHaptic = { vibrateTick() }
        // UI-4: the opaque dialogue parchment. A continue tap precisely clicks the engine CONTINUE widget (robust
        // vs the space-to-continue setting); an option row posts its digit.
        dialogueOverlay = DialogueView(this)
        dialogueOverlay.onContinue = { cx, cy ->
            if (cx >= 0 && cy >= 0) JvmLauncher.onBridgeTouch { JvmLauncher.clickPrecise(cx, cy, 1) }
            else JvmLauncher.onBridgeTouch { JvmLauncher.text(" ") }
        }
        dialogueOverlay.onOption = { digit -> JvmLauncher.onBridgeTouch { JvmLauncher.text(digit.toString()) } }
        // FIX-9: the "Reconnecting…" scrim (non-interactive; driven from updateChrome's snapshot).
        reconnectOverlay = ReconnectingOverlayView(this)
        // A4 plugin UI: a full-screen Compose panel (GONE until opened) + a small launcher button. The panel
        // is a data screen (Compose); the live game overlays above stay classic Views (hybrid, §4.2).
        // §4 increment 3: the launcher is a transparent-host PEEK/EXPAND sheet. Material Buttons (the handle +
        // devBar) carry a default elevation that would float them ABOVE a same-Z ComposeView regardless of
        // add-order, so give the panel a large elevation to keep its sheet/scrim on top (no shadow — the host
        // has no opaque outline). GONE until opened.
        val pluginPanel = ComposeView(this).apply { visibility = View.GONE; elevation = 200f }
        // Compact, low-profile dev affordances (single-glyph): kill the Material 88dp min width + slim padding so
        // the three sit in a small bottom-left cluster instead of three chunky buttons across the HUD.
        fun devButton(glyph: String) = Button(this).apply {
            text = glyph
            minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            val px = (10 * resources.displayMetrics.density).toInt()
            setPadding(px, px / 2, px, px / 2)
            alpha = 0.7f
        }
        // §4 right-bar redesign (increment 1): open the plugin launcher. The dismiss-flush (§4) persists any
        // config edit made in the panel even when it's closed without backgrounding.
        fun openLauncher() {
            pluginPanel.setContent {
                PluginPanel(
                    onClose = {
                        pluginPanel.visibility = View.GONE
                        // Dispose so the sheet's BackHandler unregisters — else BACK in-game would be swallowed.
                        pluginPanel.disposeComposition()
                        JvmLauncher.onBridge { JvmLauncher.flushConfig() }
                    },
                    rendererPref = rendererPref(),
                    onRendererSelect = { setRendererPref(it) },
                    // §4 cutover: the former devBar toolbar glyphs, now fixed launcher tiles (audit §4.3/#9).
                    onScreenshot = { saveScreenshot() },
                    onAccount = { startActivity(Intent(this@MainActivity, LoginActivity::class.java)) },
                    onLogout = { confirmLogout() },
                    // RuneLite's Discord invite (runelite.properties runelite.discord.invite) — Discord had no
                    // Android home before; publishLink routes it through the shared link path to the browser.
                    onDiscord = { JvmLauncher.onBridge { JvmLauncher.publishLink("https://discord.gg/ArdAhnN") } },
                    // FIX-11: a panel's mirror text field is focused -> the per-frame poll must leave the IME alone.
                    onPanelTextActive = { active -> textActivePanel = active },
                )
            }
            pluginPanel.visibility = View.VISIBLE
        }
        // The discoverable bottom-center HANDLE replaces the buried dev-cluster gear as the primary entry into
        // the launcher (audit diagnosis #1: the gear read as a disposable debug control). Tap to open; system
        // BACK closes it (wired below). Drag-up + the PEEK sheet are later increments.
        val launcherHandle = Button(this).apply {
            text = "≡ Plugins"
            textSize = 12f
            isAllCaps = false
            minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            val px = (16 * resources.displayMetrics.density).toInt()
            setPadding(px, px / 4, px, px / 4)
            setTextColor(0xFFFFFFFF.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 100f
                setColor(0xCC1E1E1E.toInt())
                setStroke((1 * resources.displayMetrics.density).toInt(), 0x55FFFFFF)
            }
            alpha = 0.85f
            setOnClickListener { openLauncher() }
        }
        // A6 A/B: toggle the native grid on/off (▦ = native tab grid + hidden stones; ▤ = raw engine stones).
        // updateChrome re-applies setStonesHidden to match, so flipping this reveals/hides the real stone row.
        val chromeButton = devButton("▦")
        chromeButton.setOnClickListener {
            chromeOverlay.setShown(!chromeOverlay.railsShown)
            chromeButton.text = if (chromeOverlay.railsShown) "▦" else "▤"
        }
        // §4 cutover: sign-in/switch-account (⎆), Log out (⏻) and screenshot (📷) moved out of the devBar into
        // fixed launcher tiles (audit §4.3/#9) — wired in openLauncher to startActivity/confirmLogout/
        // saveScreenshot. Only the in-play input modifiers (⌨ / ⇧) stay on the HUD cluster.
        // AP-0: manual ⌨ keyboard summon — composing public chat / PM has no engine text field for the
        // auto-raise poll to react to, so this forces the soft keyboard up (and keeps the poll from
        // instantly dismissing it). Tapping again returns to pure auto-raise. Mirrors the iOS ⌨ toggle.
        val kbButton = devButton("⌨")
        keyboardToggleBtn = kbButton
        kbButton.setOnClickListener {
            manualKeyboard = !manualKeyboard
            kbButton.alpha = if (manualKeyboard) 1f else 0.7f
            Log.i(TAG, "ap0: manual keyboard ${if (manualKeyboard) "ON" else "OFF"}")
        }
        // AP-2a (Finding 12): sticky-Shift latch. The engine's shift-gated surface — "Mark" on objects/NPCs/
        // tiles, Menu Entry Swapper shift-to-customize, overlay "Configure" — only injects while shift is held.
        // Touch has no hardware shift, so this latch posts a synthetic held VK_SHIFT (shared JavaBridge.
        // setShiftHeld, iOS-device-proven): latch ON → long-press shows the shift entries → tap to commit →
        // latch OFF. The hold is multi-second with no per-frame re-assert (proven by ShiftFeedSpike).
        val shiftButton = devButton("⇧")
        shiftButton.setOnClickListener {
            shiftHeld = !shiftHeld
            shiftButton.alpha = if (shiftHeld) 1f else 0.7f
            JvmLauncher.setShiftHeld(shiftHeld)
            Log.i(TAG, "ap2a: sticky-Shift ${if (shiftHeld) "HELD" else "released"}")
        }
        // DEBUG: long-press ⇧ publishes a host-origin notification through the shared bridge — a deterministic
        // end-to-end check of the Finding-9 delivery path (queue → drain → NotificationManager) without
        // needing an in-game trigger. Real engine notifications (idle/level) ride the identical path.
        if (BuildConfig.DEBUG) shiftButton.setOnLongClickListener {
            JvmLauncher.onBridge {
                JvmLauncher.publishNotification("RuneLite", "Notification delivery test")
                JvmLauncher.publishLink("https://oldschool.runescape.wiki/")
            }
            Log.i(TAG, "ap2a: debug test notification + link published")
            true
        }
        // Ctrl/Alt sticky latches — same mechanism as ⇧ (shared JavaBridge.setCtrlHeld/setAltHeld). ⌥ held drives
        // Ground Items' hold-to-highlight (item names appear while Alt is down); ⌃ covers the Ctrl-gated surface.
        val ctrlButton = devButton("⌃")
        ctrlButton.alpha = 0.7f
        ctrlButton.setOnClickListener {
            ctrlHeld = !ctrlHeld
            ctrlButton.alpha = if (ctrlHeld) 1f else 0.7f
            JvmLauncher.setCtrlHeld(ctrlHeld)
            Log.i(TAG, "mod: sticky-Ctrl ${if (ctrlHeld) "HELD" else "released"}")
        }
        val altButton = devButton("⌥")
        altButton.alpha = 0.7f
        altButton.setOnClickListener {
            altHeld = !altHeld
            altButton.alpha = if (altHeld) 1f else 0.7f
            JvmLauncher.setAltHeld(altHeld)
            Log.i(TAG, "mod: sticky-Alt ${if (altHeld) "HELD" else "released"}")
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
            // In-play input modifiers stay on the HUD: soft keyboard + sticky-shift. (Account/Log out/Screenshot
            // moved to the launcher as fixed tiles — §4 cutover; the settings entry is the bottom-center handle.)
            addView(kbButton); addView(shiftButton); addView(ctrlButton); addView(altButton)
            // AP-0: developer-only tooling (chrome A/B raw-stones toggle + GPU/117HD process-restart footguns) is
            // hidden in release — renderer selection lives in the native plugin panel (renderer-as-plugins).
            if (BuildConfig.DEBUG) { addView(chromeButton); addView(gpuButton); addView(hdButton) }
        }
        val root = FrameLayout(this).apply {
            val mp = FrameLayout.LayoutParams.MATCH_PARENT
            addView(surfaceView, FrameLayout.LayoutParams(mp, mp))
            addView(tapRing, FrameLayout.LayoutParams(mp, mp))        // tap-feedback ring (over the frame, no touch)
            addView(chromeOverlay, FrameLayout.LayoutParams(mp, mp))  // native 2-col tab grid (stones hidden)
            addView(chatTabOverlay, FrameLayout.LayoutParams(mp, mp)) // enlarged native chat-tab row (top-left)
            addView(dialogueOverlay, FrameLayout.LayoutParams(mp, mp)) // UI-4: opaque NPC-dialogue parchment (above chat, below menus)
            addView(keyboardView, FrameLayout.LayoutParams(1, 1))
            addView(menuOverlay, FrameLayout.LayoutParams(mp, mp))   // drawn on top of the live frame + chrome
            addView(reconnectOverlay, FrameLayout.LayoutParams(mp, mp)) // FIX-9: "Reconnecting…" scrim (non-interactive)
            // §4: the launcher handle sits above the game/chrome (tappable) but below the launcher sheet
            // (covered while it's open) and clears the bottom gesture inset.
            addView(launcherHandle, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                    bottomMargin = (10 * resources.displayMetrics.density).toInt()
                })
            addView(devBar, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START).apply {
                    val m = (8 * resources.displayMetrics.density).toInt()
                    leftMargin = m; bottomMargin = m
                })
            // §4 increment 3: the launcher is now a transparent-host PEEK/EXPAND bottom sheet (SheetScaffold) —
            // added LAST so its sheet/scrim draw OVER the handle + devBar (and the game composites through the
            // transparent top). GONE until opened.
            addView(pluginPanel, FrameLayout.LayoutParams(mp, mp))
        }
        setContentView(root)
        // §4 dismiss model (d): while the launcher sheet is open the in-sheet BackHandler (PluginPanel) handles
        // BACK — it pops one level (sub-screen -> launcher) or dismisses. This activity callback is the fallback
        // for when no composition is active: dispose+hide if somehow still visible, else passthrough to normal
        // BACK so the app backgrounds.
        onBackPressedDispatcher.addCallback(this) {
            if (pluginPanel.visibility == View.VISIBLE) {
                pluginPanel.visibility = View.GONE
                pluginPanel.disposeComposition()
                JvmLauncher.onBridge { JvmLauncher.flushConfig() }
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
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
                // FIX-10b: tap-mode (submenu drill-in / shift-customize) is a PERSISTENT menu. The opening
                // long-press's release just ARMS it; after that each discrete tap selects a row / drills / Backs,
                // and a tap outside dismisses. Drag-mode (below) is the original slide-select.
                if (menuTapMode) {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                            if (menuTapArmed) updateMenuHighlight(event.x, event.y)
                        MotionEvent.ACTION_UP -> {
                            menuTouchActive = false
                            if (!menuTapArmed) { menuTapArmed = true; menuOverlay.setHighlight(-1) }
                            else onMenuTap(event.x, event.y)
                        }
                    }
                    return@setOnTouchListener true
                }
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
                if (itemDragging) {
                    // FIX-4: ALWAYS release so the engine WidgetDrag closes — on a clean UP drop on the lift point
                    // (the destination slot); on a cancel fall back to the last valid drag point.
                    val up = event.actionMasked == MotionEvent.ACTION_UP
                    val fx = if (up) toGameX(event.x) else itemDragLastX
                    val fy = if (up) toGameY(event.y) else itemDragLastY
                    JvmLauncher.touch(2, fx, fy, 1)   // MOUSE_RELEASED on the destination slot
                    itemDragging = false
                    Log.i(TAG, "drag-1: item drag end game=($fx,$fy)")
                }
                cameraGestureActive = false
                chatScrolling = false
                mapPanning = false
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
        // Phase-2 renderer-as-plugins: the persisted preference (cpu|gpu|117hd, default cpu — the iOS
        // rendererPref model) joins the dev/CI triggers below as one more way into each mode. Renderers are
        // NOT hot-swappable (jar choice + librlawt registration are fixed at JVM start), so the panel toggle
        // writes this and the change applies on the next process start.
        val prefRenderer = rendererPref()
        val gpuMode = (intent?.getBooleanExtra("routecgpu", false) ?: false) ||
            File(getExternalFilesDir(null), "gpu.mode").exists() || prefRenderer == "gpu"
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
            (isTv && !cpuForced) || prefRenderer == "117hd"
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
        // AP-2a: start draining the notification/link queues. Safe before the VM is up — the externs return ""
        // until the MobileUiBridge facade publishes, then start yielding real items.
        startBridgeDrain()
    }

    /** Phase-2 renderer preference (cpu|gpu|117hd) — the iOS rendererPref model. Persisted; read once at
     *  boot to pick the jar set + routec flags, so a panel toggle applies on the NEXT process start. */
    private fun rendererPref(): String =
        getSharedPreferences("renderer", MODE_PRIVATE).getString("mode", "cpu") ?: "cpu"

    private fun setRendererPref(mode: String) {
        getSharedPreferences("renderer", MODE_PRIVATE).edit().putString("mode", mode).apply()
        Log.i(TAG, "p2: rendererPref -> $mode (applies on next launch)")
        val label = when (mode) { "gpu" -> "GPU"; "117hd" -> "117 HD"; else -> "CPU (vanilla)" }
        AlertDialog.Builder(this)
            .setTitle("Renderer: $label")
            .setMessage("Saved. The renderer is fixed at launch, so it applies on the next start. Restart now?")
            .setPositiveButton("Restart") { _, _ ->
                val i = packageManager.getLaunchIntentForPackage(packageName)
                i?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(i)
                finishAffinity()
                Runtime.getRuntime().exit(0)
            }
            .setNegativeButton("Later", null)
            .show()
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

    /** AP-2b Finding 5: pull the composited CPU frame (JavaBridge.getFramePixels — the stable 0x00RRGGBB
     *  snapshot) and write a PNG into MediaStore (Pictures/RuneLite) so it lands in the system gallery. The
     *  pixel pull attaches to HotSpot, so it runs on the rl-bridge thread; the encode/insert runs on a plain
     *  background thread; the Toast hops back to the UI thread. No shared-Java change — the getter already exists.
     *  Note: getFramePixels is the CPU path only — under live GPU/117HD present the frame isn't published here. */
    private fun saveScreenshot() {
        if (!booted.get()) { Toast.makeText(this, "No frame yet", Toast.LENGTH_SHORT).show(); return }
        JvmLauncher.onBridge {
            val px = JvmLauncher.framePixels()
            val w = JvmLauncher.frameWidth()
            // Width must divide the array exactly; a non-multiple means a torn read across a resize — bail and
            // let the user retap (frames are static at the login screen and rarely resize in-world).
            if (px == null || w <= 0 || px.isEmpty() || px.size % w != 0) {
                runOnUiThread { Toast.makeText(this, "Screenshot unavailable", Toast.LENGTH_SHORT).show() }
                return@onBridge
            }
            val h = px.size / w
            // Source is 0x00RRGGBB (alpha unset). Force opaque; channel order already matches Android's ARGB int
            // (0xAARRGGBB) — NO R<->B swizzle here (unlike the SurfaceView blit, which swizzles for RGBA_8888).
            for (i in px.indices) px[i] = px[i] or 0xFF000000.toInt()
            val ok = try {
                val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
                val saved = writePngToGallery(bmp)
                bmp.recycle()
                saved
            } catch (t: Throwable) {
                Log.w(TAG, "screenshot: encode/save failed", t); false
            }
            runOnUiThread {
                Toast.makeText(this, if (ok) "Screenshot saved to Pictures/RuneLite" else "Screenshot save failed",
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Insert [bmp] as a PNG into the shared Pictures/RuneLite collection. API 29+ uses scoped storage
     *  (RELATIVE_PATH + IS_PENDING, no permission); API 28 falls back to the legacy top-level insert (covered by
     *  WRITE_EXTERNAL_STORAGE maxSdk=28 in the manifest). Returns true on a successful write. */
    private fun writePngToGallery(bmp: Bitmap): Boolean {
        val name = "RuneLite_${System.currentTimeMillis()}.png"
        val resolver = contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RuneLite")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) throw java.io.IOException("PNG compress failed")
            } ?: throw java.io.IOException("null output stream")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            Log.i(TAG, "screenshot: saved $name -> $uri")
            true
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)   // roll back the half-written/pending row
            Log.w(TAG, "screenshot: write failed", t)
            false
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
            // Phase-5: taps inside the open world-map window are PRECISE clicks — the engine resolves the
            // close X / zoom buttons / POI rows directly, the walk/interact ring stays suppressed, and a tap
            // on the search field raises the IME manually (the map search never flips INPUT_TYPE).
            if (mapOpen && (inCanvasRect(mapRectCanvas, gx, gy) || inCanvasRect(mapCloseCanvas, gx, gy)
                    || inCanvasRect(mapSearchCanvas, gx, gy) || inCanvasRect(mapZoomInCanvas, gx, gy)
                    || inCanvasRect(mapZoomOutCanvas, gx, gy))) {
                if (inCanvasRect(mapSearchCanvas, gx, gy)) {
                    mapSearchKeyboard = true
                    Log.i(TAG, "p5: map search tap -> raise IME")
                }
                JvmLauncher.clickPrecise(gx, gy, 1)
                return true
            }
            // AP-0: the relocated minimap + orb cluster is HUD/UI. Clicks there still fire (minimap walk / orb
            // ops) but the world-style walk/interact ring is suppressed — a ring drawn on the minimap or an orb
            // is spurious (rings are for in-scene taps), and an orb tap is never a walk so the optimistic-walk
            // grace would always paint a stray yellow ring. Mirrors the open-map precise-click suppression.
            if (!mapOpen && inCanvasRect(minimapRectCanvas, gx, gy)) {
                JvmLauncher.click(gx, gy, 1)
                Log.i(TAG, "ap0: minimap/orb tap ($gx,$gy) -> click without walk-ring")
                return true
            }
            // Arm the click ring BEFORE posting the click so the baseline seq can't race the engine's bump.
            ringBaseSeq = JvmLauncher.interactTapSeq()
            ringX = e.x; ringY = e.y
            ringArmedAtMs = SystemClock.uptimeMillis()
            ringPending = true
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
            // Context routing, decided once at gesture start from the DOWN point (same rule as iOS):
            // inside the open world-map window = pan the MAP; inside the live chat box = scroll the chat;
            // anywhere else = rotate the camera.
            if (!cameraGestureActive && !chatScrolling && !mapPanning && !itemDragging) {
                if (mapOpen && e1 != null && inCanvasRect(mapRectCanvas, toGameX(e1.x), toGameY(e1.y))) {
                    mapPanning = true
                    mapPanAccumX = 0f; mapPanAccumY = 0f
                    Log.i(TAG, "p5: map pan begin")
                }
                if (!mapPanning) {
                    val r = chatRectCanvas
                    if (r != null && e1 != null && !mapOpen) {
                        val gx = toGameX(e1.x); val gy = toGameY(e1.y)
                        if (gx >= r[0] && gx < r[0] + r[2] && gy >= r[1] && gy < r[1] + r[3]) {
                            chatScrolling = true
                            chatScrollAccum = 0f
                            Log.i(TAG, "ap0: chat scroll begin game=($gx,$gy)")
                        }
                    }
                    if (!chatScrolling) {
                        // FIX-4 / DRAG-1b: a drag that STARTS inside the inventory grid OR a reorder pane
                        // (Prayer/Spellbook/Bank) drives the engine WidgetDrag (getDraggedWidget), not the camera.
                        // The true start point (e1, the DOWN event) is the source slot/icon. All these panes use
                        // the identical MOUSE_PRESSED->DRAGGED->RELEASED stream, so one branch covers them.
                        val sx = if (e1 != null) toGameX(e1.x) else -1
                        val sy = if (e1 != null) toGameY(e1.y) else -1
                        val inDraggablePane = !mapOpen && e1 != null
                            && (inCanvasRect(invRectCanvas, sx, sy) || reorderRectsCanvas.any { inCanvasRect(it, sx, sy) })
                        if (inDraggablePane) {
                            itemDragging = true
                            itemDragLastX = sx; itemDragLastY = sy
                            // onTouch(0) primes focus + MOVED + PRESSED on the slot — no clearHover (that would
                            // park the cursor off-canvas; iOS doesn't either).
                            JvmLauncher.touch(0, sx, sy, 1)   // MOUSE_PRESSED on the source widget
                            Log.i(TAG, "drag-1: widget drag begin game=($sx,$sy)")
                        } else {
                            cameraGestureActive = true
                            JvmLauncher.clearHover()
                        }
                    }
                }
            }
            if (itemDragging) {
                // Stream the WidgetDrag at the finger's current canvas point (one per frame is fine); cache the
                // last valid point so the release always lands somewhere sensible (no stuck held item).
                val gx = toGameX(e2.x); val gy = toGameY(e2.y)
                itemDragLastX = gx; itemDragLastY = gy
                JvmLauncher.touch(1, gx, gy, 1)   // MOUSE_DRAGGED
                return true
            }
            if (mapPanning) {
                // "Grab the map": finger deltas in canvas px, sub-px accumulated (slow drags must not
                // quantize to zero — the iOS lesson); the shared tick converts to tiles via the live zoom.
                val fw = JvmLauncher.frameWidth(); val vw = surfaceView.width
                val k = if (fw > 0 && vw > 0) fw.toFloat() / vw else 1f
                mapPanAccumX += -distanceX * k
                mapPanAccumY += -distanceY * k
                val wx = mapPanAccumX.toInt(); val wy = mapPanAccumY.toInt()
                if (wx != 0 || wy != 0) {
                    mapPanAccumX -= wx; mapPanAccumY -= wy
                    JvmLauncher.onBridgeTouch { JvmLauncher.panWorldMap(wx, wy) }
                }
                return true
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
                    JvmLauncher.onBridgeTouch { JvmLauncher.scrollChat(-whole) }
                }
                return true
            }
            // GestureDetector.onScroll distanceX/Y = old - new, which already equals what iOS sends the bridge
            // (postCameraDx passes -translation, i.e. old - new). Pass it through un-negated so the camera
            // yaws/pitches the same direction as iOS (grab-the-scene feel); negating double-inverts -> reversed.
            JvmLauncher.camera(scaleX(distanceX).toInt(), scaleY(distanceY).toInt())
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
            // Re-park the pointer on pinch END too (iOS 469afc4af8): the zoom re-projects the world under
            // the last tracked cursor position, so a begin-only clear leaves a stale hover label behind.
            JvmLauncher.clearHover()
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
                // Compare against the ACTUAL IME visibility, not just our last request (the system can dismiss
                // the keyboard behind our back). API<30 falls back to the cached flag (no ime() inset type).
                val imeShown = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    surfaceView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) ?: keyboardShown
                } else {
                    keyboardShown
                }
                if (textActivePanel) {
                    // FIX-11: a rendered Swing panel's text field is focused; its native Compose mirror field
                    // (PanelView) owns the soft keyboard. Don't touch the IME here — raising GameInputView would
                    // steal focus from the mirror, and dismissing would kill the keyboard the mirror just raised.
                    imeWasShown = imeShown
                } else {
                    // The keyboard was up and just went down while one of OUR latches still wanted it: that's the
                    // user closing it (the soft-keyboard ▽ / back). Respect it — drop the latches so we don't
                    // immediately re-raise (the old "sticky" behavior fought the close button). Edge-triggered off
                    // the OBSERVED visibility, so the raise animation's transient !imeShown doesn't misfire. An
                    // engine text field (isTextInputOpen) is NOT a latch we clear here — if the engine truly has a
                    // field focused it re-raises next frame, which is correct.
                    if (imeWasShown && !imeShown && (manualKeyboard || mapSearchKeyboard)) {
                        manualKeyboard = false
                        mapSearchKeyboard = false
                        keyboardToggleBtn?.alpha = 0.7f
                        Log.i(TAG, "a1: user dismissed IME -> clear keyboard latches")
                    }
                    imeWasShown = imeShown
                    val want = manualKeyboard || mapSearchKeyboard || JvmLauncher.isTextInputOpen()
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
                }
                val seq = JvmLauncher.interactTapSeq()
                if (seq != lastTapSeq) {
                    lastTapSeq = seq
                    Log.i(TAG, "a1: interactTapSeq -> $seq (haptic)")
                    vibrateTick()
                }
                // Phase-1 click ring: resolve the pending tap from engine truth. Seq advanced past the
                // tap-time baseline → the engine consumed an interaction (red); RING_GRACE_MS with no
                // advance → plain walk (yellow). The grace covers the settle pipeline (~80–160 ms).
                if (ringPending) {
                    if (seq != ringBaseSeq) {
                        ringPending = false
                        Log.i(TAG, "p1: ring INTERACT (+${SystemClock.uptimeMillis() - ringArmedAtMs}ms)")
                        tapRing.spawn(ringX, ringY, TapRingView.COLOR_INTERACT)
                    } else if (SystemClock.uptimeMillis() - ringArmedAtMs > RING_GRACE_MS) {
                        ringPending = false
                        Log.i(TAG, "p1: ring WALK")
                        tapRing.spawn(ringX, ringY, TapRingView.COLOR_WALK)
                    }
                }
                if (menuBuilding) {
                    tryBuildNativeMenu()
                }
                updateChrome()
                applyOverlayInsets()
                updateWorldMap()
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        frameCallback = cb
        Choreographer.getInstance().postFrameCallback(cb)
    }

    /** AP-2a Finding 9: create the notification channel + request POST_NOTIFICATIONS (API 33+). */
    private fun setupNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(NOTIF_CHANNEL, "Game alerts", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "In-game notifications (idle, level-up, etc.)" }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIFICATIONS)
        }
    }

    /**
     * AP-2a Findings 9 + 25: drain the shared notification + link queues on a dedicated daemon (NOT the UI
     * thread — these JNI calls daemon-attach to HotSpot; the UI thread must never attach, ANR risk). A
     * notification → the system shade (NotificationManager); a link → the browser. ~3Hz: notifications/links
     * are human-paced, so a third of a second is imperceptible and costs almost nothing.
     */
    private fun startBridgeDrain() {
        if (drainRun) return
        drainRun = true
        thread(name = "rl-drain", isDaemon = true) {
            while (drainRun) {
                try {
                    var n = JvmLauncher.nextNotification()
                    while (n.isNotEmpty()) {
                        deliverNotification(n)
                        n = JvmLauncher.nextNotification()
                    }
                    var url = JvmLauncher.nextLink()
                    while (url.isNotEmpty()) {
                        openLink(url)
                        url = JvmLauncher.nextLink()
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "rl-drain: ${t.message}")
                }
                try { Thread.sleep(300) } catch (e: InterruptedException) { break }
            }
        }
    }

    /** Raise a system notification from a "titlebody" payload (the shared SOH-delimited format). */
    private fun deliverNotification(payload: String) {
        val sep = payload.indexOf('')
        val title = if (sep >= 0) payload.substring(0, sep) else "RuneLite"
        val body = if (sep >= 0) payload.substring(sep + 1) else payload
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "ap2a: notification suppressed (POST_NOTIFICATIONS not granted): '$title'")
            return
        }
        // Tap reopens the game (single top — don't stack a second activity).
        val openSelf = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pi = openSelf?.let {
            android.app.PendingIntent.getActivity(
                this, 0, it,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val n = NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title.ifEmpty { "RuneLite" })
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .apply { if (pi != null) setContentIntent(pi) }
            .build()
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(notifId++, n)
            Log.i(TAG, "ap2a: notification delivered title='$title'")
        } catch (t: Throwable) {
            Log.w(TAG, "ap2a: notify failed: ${t.message}")
        }
    }

    /** AP-0/Finding 25: open an in-game outbound link (Wiki/HiScore/plugin page) in an in-app Chrome Custom Tab
     *  (stays in the task, one back-press returns to the game) instead of cold-switching to the system browser.
     *  Falls back to ACTION_VIEW if no browser supports Custom Tabs. */
    private fun openLink(url: String) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Log.i(TAG, "ap2a: link ignored (non-http): $url")
            return
        }
        val uri = Uri.parse(url)
        try {
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, uri)
            Log.i(TAG, "ap2a: link opened (custom tab) $url")
        } catch (t: Throwable) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Log.i(TAG, "ap2a: link opened (browser fallback) $url")
            } catch (t2: Throwable) {
                Log.w(TAG, "ap2a: openLink failed: ${t2.message}")
            }
        }
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
        // FIX-9: show the native "Reconnecting…" scrim while the headless auto-reconnect recovers a dropped
        // session (the snapshot only carries the flag during an active reconnect — a normal login screen or a
        // deliberate logout omits it). The DEBUG override forces it for screencap verification.
        if (::reconnectOverlay.isInitialized) {
            val live = !loggedIn && o?.optBoolean("reconnecting") == true
            val show = reconnectTestOverride ?: live
            if (reconnectOverlay.setReconnecting(show)) {
                Log.i(TAG, "fix9: reconnect overlay -> $show")
            }
        }
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
        // FIX-4: live inventory grid bounds (null when the inventory tab isn't showing) for the item-drag hit test.
        invRectCanvas = if (loggedIn) {
            o?.optJSONArray("inv")?.takeIf { it.length() >= 4 && it.optInt(2) > 0 && it.optInt(3) > 0 }
                ?.let { intArrayOf(it.optInt(0), it.optInt(1), it.optInt(2), it.optInt(3)) }
        } else null
        // DRAG-1b: the Prayer/Spellbook/Bank reorder-pane rects (empty when none on-screen) for the reorder-drag hit test.
        reorderRectsCanvas = if (loggedIn) {
            val arr = o?.optJSONArray("reorderPanes")
            if (arr == null || arr.length() == 0) {
                emptyList()
            } else {
                val out = ArrayList<IntArray>(arr.length())
                for (i in 0 until arr.length()) {
                    val r = arr.optJSONArray(i) ?: continue
                    if (r.length() >= 4 && r.optInt(2) > 0 && r.optInt(3) > 0) {
                        out.add(intArrayOf(r.optInt(0), r.optInt(1), r.optInt(2), r.optInt(3)))
                    }
                }
                out
            }
        } else emptyList()

        val gridOn = supported && chromeOverlay.railsShown
        applyStonesHidden(gridOn)
        // Phase-5: the pill hides while the world map owns the screen (its engine row-height push resets too,
        // mirroring iOS hideChatTabs); the chat/side/minimap RELOCATES keep being sent — the shared tick gates
        // their application while the map is open and restores them on close, so nothing is released here.
        applyChatTabs(gridOn && !mapOpen)
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
        val chatSnap = if (on) JvmLauncher.chatSnapshotPub() else ""
        val n = chatTabOverlay.update(chatSnap, sx, sy, on)
        // UI-4: drive the opaque NPC-dialogue parchment from the same snapshot + the dialogue JSON.
        dialogueOverlay.update(chatSnap, if (on) JvmLauncher.dialogueJsonPub() else "{}", sx, sy, on)
        if (on && n > 0 && !chatTabsLogged) {
            chatTabsLogged = true
            Log.i(TAG, "ANDROID_A3_CHATTABS_GREEN enlarged native chat-tab row drawn ($n tabs) sx=$sx sy=$sy")
        }
        val wantRowH = if (on) ChatPillView.ROW_H_CANVAS else 0
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
        if (fw <= 0 || vw <= 0) return
        val k = fw.toFloat() / vw.toFloat()
        var sideW = 204                                  // fallback until the panel is open + reports its width
        var sideHc = 275                                 // the SIDE_CONTAINER is a fixed 204x275 canvas raster
        val sc = o?.optJSONArray("sideContainer")
        if (sc != null && sc.length() >= 4) {
            if (sc.optInt(2) > 0) sideW = sc.getInt(2)
            if (sc.optInt(3) > 0) sideHc = sc.getInt(3)
        }
        // UI-1: the panel is a fixed engine raster we can't grow, so shrink the native grid to the panel height
        // (constant 275 canvas px) — they then share both the top and bottom edge. Do this BEFORE reading the
        // grid edges below so the relocate uses the post-shrink geometry (setTargetGridHeight recomputes it).
        chromeOverlay.setTargetGridHeight(sideHc / k)
        val gx0 = chromeOverlay.gridLeftPx; val gy0 = chromeOverlay.gridTopPx
        if (gx0 <= 0f) return
        val sideX = maxOf(0, Math.round(gx0 * k) - sideW)   // right edge flush against the grid's left edge
        val sideY = maxOf(0, Math.round(gy0 * k))           // stones hidden, so top-align with the grid
        if (sideX == lastSideX && sideY == lastSideY) return
        lastSideX = sideX; lastSideY = sideY
        JvmLauncher.onBridge { JvmLauncher.relocateSidePanel(sideX, sideY) }
    }

    /** Stop relocating the engine side panel (let it return to its native position) when the grid disengages. */
    private fun releaseSidePanel() {
        chromeOverlay.setTargetGridHeight(0f)   // UI-1: grid back to its natural size when the chrome disengages
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

    /** Stop relocating the chat (let it return to its native bottom position) when the chrome disengages.
     *  Also un-collapse: with the pill gone there'd be no way to expand a shoved-off-canvas chat. */
    private fun releaseChat() {
        if (lastChatX == -1 && lastChatY == -1) return
        lastChatX = -1; lastChatY = -1
        chatCollapsed = false
        JvmLauncher.onBridge { JvmLauncher.relocateChat(-1, -1, false); JvmLauncher.setChatCollapsed(false) }
    }

    // Phase-3: last overlay-insets values sent to the shared OverlayRenderer (send only on change).
    private var lastOverlayInsets: IntArray? = null

    /** Phase-3 overlay safe-insets: convert the full safe-area insets (view px, all four edges) to CANVAS px
     *  and push them to HudVeneerBridge.setOverlaySafeInsets — the shared OverlayRenderer then keeps every
     *  plugin-overlay snap corner clear of the cutout/system bars AND drops TOP_LEFT below the relocated chat
     *  (getOverlayChatAvoid, consumed Java-side). Zero-impact when insets are 0 (emulator, desktop). */
    private fun applyOverlayInsets() {
        val fw = JvmLauncher.frameWidth(); val fh = JvmLauncher.frameHeight()
        val vw = surfaceView.width; val vh = surfaceView.height
        if (fw <= 0 || fh <= 0 || vw <= 0 || vh <= 0) return
        val wi = surfaceView.rootWindowInsets ?: return
        val (l, t, r, b) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val i = wi.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            listOf(i.left, i.top, i.right, i.bottom)
        } else {
            @Suppress("DEPRECATION")
            listOf(wi.stableInsetLeft, wi.stableInsetTop, wi.stableInsetRight, wi.stableInsetBottom)
        }
        val canvas = intArrayOf(l * fw / vw, t * fh / vh, r * fw / vw, b * fh / vh)
        if (lastOverlayInsets?.contentEquals(canvas) == true) return
        lastOverlayInsets = canvas
        Log.i(TAG, "p3: overlay safe-insets -> canvas(${canvas[0]},${canvas[1]},${canvas[2]},${canvas[3]})")
        JvmLauncher.onBridge { JvmLauncher.setOverlayInsets(canvas[0], canvas[1], canvas[2], canvas[3]) }
    }

    /** Phase-5: parse the published world-map snapshot (per frame, cheap — skipped when the string is
     *  unchanged) and gate the native chrome on the open state. The engine-side HUD shoves are shared. */
    private fun updateWorldMap() {
        val snap = JvmLauncher.mapSnapshotPub()
        if (snap == lastMapSnapshot) return
        lastMapSnapshot = snap
        val o = try { JSONObject(snap) } catch (e: Exception) { null }
        val open = o?.optBoolean("open") == true
        fun rect(key: String): IntArray? = o?.optJSONArray(key)?.takeIf { it.length() >= 4 }
            ?.let { intArrayOf(it.optInt(0), it.optInt(1), it.optInt(2), it.optInt(3)) }
        mapRectCanvas = if (open) rect("map") else null
        mapSearchCanvas = if (open) rect("search") else null
        mapCloseCanvas = if (open) rect("close") else null
        mapZoomInCanvas = if (open) rect("zoomIn") else null
        mapZoomOutCanvas = if (open) rect("zoomOut") else null
        // FIX-14: the map search never flips INPUT_TYPE, so the soft keyboard would otherwise linger over the
        // jumped-to map until the map closes. Watch the engine WORLDMAP_SEARCHING flag (published in the
        // snapshot): on its 1->0 edge the search has committed (Enter) or been cancelled (Quit) — drop the latch
        // so the per-frame input poll auto-hides the keyboard.
        val searching = open && o?.optInt("searching", 0) == 1
        if (mapWasSearching && !searching && mapSearchKeyboard) {
            mapSearchKeyboard = false
            Log.i(TAG, "p5: map search committed -> dismiss keyboard")
        }
        mapWasSearching = searching
        if (open != mapOpen) {
            mapOpen = open
            // The native chrome would cover the floating window — hide it while the map owns the screen.
            // View-level visibility only: railsShown (the user's A/B toggle) and all relocates are untouched.
            chromeOverlay.visibility = if (open) View.GONE else View.VISIBLE
            if (!open) {
                mapPanning = false
                mapSearchKeyboard = false
            }
            Log.i(TAG, "p5: world map ${if (open) "OPEN" else "closed"} rect=${mapRectCanvas?.joinToString(",")}" +
                if (open) " close=${mapCloseCanvas?.joinToString(",")} search=${mapSearchCanvas?.joinToString(",")}" +
                    " zoomIn=${mapZoomInCanvas?.joinToString(",")} zoomOut=${mapZoomOutCanvas?.joinToString(",")}" else "")
        }
    }

    private fun inCanvasRect(r: IntArray?, gx: Int, gy: Int): Boolean =
        r != null && gx >= r[0] && gx < r[0] + r[2] && gy >= r[1] && gy < r[1] + r[3]

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
        var mapH = 340                                    // fallback cluster height until the snapshot reports it
        val mc = o?.optJSONArray("mapContainer")
        if (mc != null && mc.length() >= 4 && mc.optInt(2) > 0) {
            mapW = mc.getInt(2)
            if (mc.optInt(3) > 0) mapH = mc.getInt(3)
        }
        val mmX = maxOf(0, fw - mapW - Math.round(ins[2] * kx) - margin)
        val mmY = Math.round(ins[1] * ky) + margin
        // AP-0: cache the relocated cluster's canvas rect so a tap on the minimap/orbs suppresses the walk-ring.
        minimapRectCanvas = intArrayOf(mmX, mmY, mapW, mapH)
        if (mmX == lastMmX && mmY == lastMmY) return
        lastMmX = mmX; lastMmY = mmY
        JvmLauncher.onBridge { JvmLauncher.relocateMinimap(mmX, mmY) }
    }

    /** Stop relocating the minimap (let it return to its native position) when the chrome disengages. */
    private fun releaseMinimap() {
        minimapRectCanvas = null   // AP-0: no relocated cluster -> don't suppress rings (raw-stones mode)
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
        menuTapMode = false; menuTapArmed = false; menuDrillParent = -1   // FIX-10b: fresh menu
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
        menuTapMode = false; menuTapArmed = false; menuDrillParent = -1   // FIX-10b: fresh menu
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
            // Drag-mode: a finger lifted before the menu built means cancel. Tap-mode (FIX-10b) is persistent —
            // if the finger is already up, the menu just becomes immediately tap-ready (no auto-cancel).
            if (menuTapMode) { if (!menuTouchActive) menuTapArmed = true }
            else if (!menuTouchActive) cancelMenu()
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
        var anySub = false
        for (k in 0 until n) {
            val o = arr.optJSONObject(k) ?: continue
            // FIX-10b: a "sub" array (Menu Entry Swapper swap list) makes this a drill-in parent. engineIndex=k
            // (the top-level display index) so a tap can commit it via invokeMenuEntry / geometry.
            val subArr = o.optJSONArray("sub")
            var sub: List<MenuOverlayView.Entry>? = null
            if (subArr != null && subArr.length() > 0) {
                val cs = ArrayList<MenuOverlayView.Entry>(subArr.length())
                for (c in 0 until subArr.length()) {
                    val co = subArr.optJSONObject(c) ?: continue
                    cs.add(MenuOverlayView.Entry(co.optString("opt", ""), co.optString("tgt", ""), engineIndex = c))
                }
                if (cs.isNotEmpty()) { sub = cs; anySub = true }
            }
            val role = if (sub != null) MenuOverlayView.Role.PARENT else MenuOverlayView.Role.NORMAL
            items.add(MenuOverlayView.Entry(o.optString("opt", ""), o.optString("tgt", ""), sub = sub, role = role, engineIndex = k))
        }
        menuTopEntries = items
        menuDrillParent = -1
        // FIX-10b: tap-mode when there's a submenu to drill into, or ⇧ is latched (shift-customize). Else the
        // existing slide-select drag menu (tall menus already shrink-to-fit). The ⇧ button is the shift entry.
        menuTapMode = anySub || shiftHeld
        if (menuTapMode) {
            buildTapMenu(items, drilled = false)
            Log.i(TAG, "fix10b: tap menu $emCount entries (anySub=$anySub shift=$shiftHeld) at game=($emX,$emY)")
        } else {
            menuOverlay.show(items, menuAnchorX, menuAnchorY, menuHandRight)
            Log.i(TAG, "a2: native menu $emCount entries at game=($emX,$emY) w=$emW")
            if (menuTouchActive) updateMenuHighlight(menuCurX, menuCurY)
        }
    }

    /** FIX-10b: (re)build the persistent tap-menu — top level, or a submenu (prepend a '‹ Back' row, retitle). */
    private fun buildTapMenu(items: List<MenuOverlayView.Entry>, drilled: Boolean) {
        val display = if (drilled) {
            ArrayList<MenuOverlayView.Entry>(items.size + 1).apply {
                add(MenuOverlayView.Entry("‹ Back", "", role = MenuOverlayView.Role.BACK))
                addAll(items)
            }
        } else items
        menuOverlay.show(display, menuAnchorX, menuAnchorY, menuHandRight,
            tap = true, header = if (drilled) "Submenu" else "Choose Option")
    }

    /** FIX-10b: a discrete tap in tap-mode — route to a row, or dismiss when it lands outside the box. */
    private fun onMenuTap(x: Float, y: Float) {
        when (val r = menuOverlay.hitTest(x, y)) {
            -2 -> cancelMenu()   // outside the box -> dismiss
            -1 -> { }            // header -> ignore
            else -> onTapRow(r)
        }
    }

    /** FIX-10b: act on a tapped tap-mode row — Back / drill into a submenu / commit a leaf (top or child). */
    private fun onTapRow(displayRow: Int) {
        val e = menuOverlay.entryAt(displayRow) ?: return
        when (e.role) {
            MenuOverlayView.Role.BACK -> {
                menuDrillParent = -1
                menuOverlay.setHighlight(-1)
                buildTapMenu(menuTopEntries, drilled = false)
            }
            MenuOverlayView.Role.PARENT -> {
                menuDrillParent = e.engineIndex
                menuOverlay.setHighlight(-1)
                vibrateTick()
                buildTapMenu(e.sub ?: emptyList(), drilled = true)
            }
            MenuOverlayView.Role.NORMAL -> {
                if (menuDrillParent >= 0) {
                    // Submenu child — not in the engine menu geometry, so invoke its onClick directly, then
                    // dismiss the still-open engine top-level menu.
                    JvmLauncher.invokeMenuEntry(menuDrillParent, e.engineIndex)
                    closeEngineMenu()
                    Log.i(TAG, "fix10b: tap submenu child parent=$menuDrillParent child=${e.engineIndex}")
                } else {
                    // Top-level leaf — a geometry click invokes + closes the engine menu in one.
                    val gx = emX + emW / 2
                    val gy = emY + MENU_HEADER_PX + e.engineIndex * MENU_ROW_PX + MENU_ROW_PX / 2
                    JvmLauncher.selectMenuPoint(gx, gy)
                    Log.i(TAG, "fix10b: tap top row ${e.engineIndex} -> game=($gx,$gy)")
                }
                teardownMenu()
            }
        }
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

    /** Close the still-open engine right-click menu via its own bottom Cancel row (no stray world click). */
    private fun closeEngineMenu() {
        if (emCount > 0 && emW > 0) {
            val k = emCount - 1
            val gx = emX + emW / 2
            val gy = emY + MENU_HEADER_PX + k * MENU_ROW_PX + MENU_ROW_PX / 2
            JvmLauncher.selectMenuPoint(gx, gy)
        }
    }

    /** Close the engine menu, then tear down. */
    private fun cancelMenu() {
        closeEngineMenu()
        Log.i(TAG, "a2: menu cancelled")
        teardownMenu()
    }

    private fun teardownMenu() {
        menuOverlay.hide()
        menuActive = false
        menuBuilding = false
        menuTouchActive = false
        menuHighlight = -1
        menuTapMode = false        // FIX-10b
        menuTapArmed = false
        menuDrillParent = -1
        menuTopEntries = emptyList()
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
        drainRun = false   // stop the AP-2a notification/link drain daemon
        hdConfigReceiver?.let { runCatching { unregisterReceiver(it) } }; hdConfigReceiver = null
        reconnectTestReceiver?.let { runCatching { unregisterReceiver(it) } }; reconnectTestReceiver = null
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
        // Phase-2: EVERY mode boots the facade-backed jar (the iOS single-jar-set model since the gpu merge).
        // The CPU boot is the facade jar with no routec flags + gpuplugin force-disabled — identical client,
        // renderer plugins loadable-but-stopped. This is also the only combination the rlhd jar is compiled
        // against (facade rlawt), so the 117HD panel row class-loads cleanly on any boot. The pristine
        // shaded jar stays staged as the surgery source + desktop-dev artifact, not a boot target.
        val useGpuJar = true
        val jarSuffix = "-gpu.jar"
        run {
            val wanted = mutableListOf<String>()
            if (useGpuJar) wanted.add("-gpu.jar")
            // Phase-2: the rlhd jar rides EVERY classpath (not just rlhd boots) so RendererPanelStarter can
            // load rs117.hd.HdPlugin into the plugin panel as a selectable renderer row on any boot.
            wanted.add("-rlhd.jar")
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
        val rlhdJar = jvmDir.list { _, name -> name.startsWith("client-") && name.endsWith("-rlhd.jar") }?.firstOrNull()
        if (rlhd && rlhdJar == null) {
            // Only an rlhd BOOT hard-requires the jar; a CPU/GPU boot just loses the 117HD panel row.
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
            // Phase-2 renderer-as-plugins (the iOS d2b46053c model): drop safe-mode on every boot (so the
            // renderer plugins CAN load) and let RendererPanelStarter put GPU + 117HD into the panel without
            // starting them. Which one actually STARTS is decided purely by the routec flags below.
            "-Drunelite.nosafemode=true",
            "-Drunelite.rendererpanel=true",
        )
        if (!gpu) {
            // GpuPlugin is enabledByDefault; with safe-mode off it would auto-start (and on the pristine
            // shaded jar that means real LWJGL = instant native crash). Force-disable wins over persisted
            // config (the shared 04b4e3037 rule), so the renderer choice actually applies.
            opts.add("-Drunelite.forceDisablePlugins=gpuplugin")
        }
        // A7 resizable: render at the screen's landscape aspect — HeadlessResize grows the gamepack Component on
        // login (stock, inset-aware, the same mechanism iOS device-proved). With the OSRS account in RESIZABLE
        // display mode the gamepack fills it (resizable toplevel 164 → chat-at-top + resizable chrome unblock);
        // a FIXED account harmlessly keeps the 765x503 viewport (gamepack pillarboxes, canvas stays 765x503, so
        // the blit + touch + A6 rails are unchanged — no regression). The account's display mode is the switch.
        run {
            val dm = app.resources.displayMetrics
            val longEdge = maxOf(dm.widthPixels, dm.heightPixels)
            val shortEdge = maxOf(1, minOf(dm.widthPixels, dm.heightPixels))
            // UI-2: match iOS's render height (540). A lower engine canvas blits up to a physically BIGGER HUD
            // (minimap, orbs, chat, side panel, tab grid all scale together) — the only working way to enlarge the
            // minimap (the per-widget MINIMAP-scale path overlaps the orbs / blanks terrain). 540 is iOS-proven
            // safe; below ~480 the minimap column collides with the tab grid.
            val renderH = 540
            val renderW = (renderH.toLong() * longEdge / shortEdge).toInt()
            canvasW = renderW; canvasH = renderH   // for the overlay free-drag pad → canvas-pixel mapping
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
        // Phase-5 dev: a pushed worldmapprobe.mode marker arms the shared WorldMapProbe (one-shot orb-click
        // open + pan/zoom/search oracles, [worldmap] logs + PNGs) — the same recon the iOS Phase-0 ran on
        // desktop, runnable on the emulator when the map flow needs re-verifying.
        if (File(app.getExternalFilesDir(null), "worldmapprobe.mode").exists()) {
            opts.add("-Drunelite.worldmapprobe=true")
            Log.i(TAG, "p5: WorldMapProbe ARMED (worldmapprobe.mode marker)")
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
                // FIX-13: arm the once/sec engine-fps logcat probe ([fpsprobe] fps=N gs=...) so a preset A/B can be
                // measured in-world on real Mali (Hooks.frame, gated by this prop). Only active in 117HD test mode.
                "-Drunelite.fpsprobe=true",
            ))
            Log.i(TAG, "AG3-b 117HD mode: gpu jar $clientJar + rlhd jar $rlhdJar, routecrlhd + frame-dump + fpsprobe armed (dir=$dumpDir/routec-frames)")
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
