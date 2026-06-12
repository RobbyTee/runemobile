package dev.audaro.helloshell

import android.view.Surface
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Thin Kotlin facade over the native JNI Invocation-API bootstrap + render/input host.
 *
 * The app process is hosted by ART, but the game runs on a *second*, in-process HotSpot VM that the
 * native layer brings up with JNI_CreateJavaVM (dlopen of the embedded JRE's libjvm.so). This mirrors
 * the proven iOS CPU/headless bootstrap (JVMLauncher.m) minus every iOS-JIT workaround — Android grants JIT
 * for free. (Mirrors the iOS CPU path; the iOS Route-C GPU host has no Android counterpart yet —
 * see android/BLUEPRINT.md Track G.)
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

    /** A8: enable/disable piping the embedded VM's stdout/err to logcat. Call BEFORE [startJvm] with
     *  BuildConfig.DEBUG so a release build never leaks the engine's output (incl. account/network chatter). */
    external fun nativeSetStdioRedirect(enabled: Boolean)

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

    // AP-2a sticky-Shift (Finding 12): hold/release a synthetic VK_SHIFT via JavaBridge.setShiftHeld. Queued
    // through the input path so it's ORDERED ahead of the menu-open right-click (engine must read shift held
    // before it builds the menu, or Mark/Configure entries aren't injected). UI-thread-safe (enqueue only).
    external fun nativeSetShiftHeld(held: Boolean)

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

    // ---- A2 press-and-peek menu ---------------------------------------------------------------------
    /** Commit a menu choice by clicking the engine row centre (game-canvas x,y) — honours plugin onClick. */
    external fun nativeSelectMenuPoint(x: Int, y: Int)

    /** Latest MenuPeekBridge snapshot JSON (escaped), published by the render thread. */
    external fun nativeMenuSnapshot(): String

    /** Monotonic publish counter for the menu snapshot (freshness check). */
    external fun nativeMenuPublishSeq(): Long

    /** Latest composited frame dimensions (game-canvas pixels), or 0 before the first frame. */
    external fun nativeFrameWidth(): Int
    external fun nativeFrameHeight(): Int

    // AP-2b Finding 5 (screenshot): the latest composited CPU frame as 0x00RRGGBB ints (alpha unset; the consumer
    // ORs 0xFF for an opaque PNG). null before the first frame / bridge off. Pull OFF the UI thread (via [onBridge])
    // — it attaches the caller to HotSpot. Size against [nativeFrameWidth]; guard len % width == 0.
    external fun nativeFramePixels(): IntArray?

    // ---- A4 plugin control plane (MobileUiBridge; gated by -Drunelite.mobileui) ----------------------
    // CALL THESE OFF THE UI THREAD ONLY (via [onBridge]) — they daemon-attach the calling thread to the
    // embedded HotSpot VM and may block (config writes dispatch to the client thread). ART<->HotSpot strings
    // are marshalled as UTF-8 in the native layer.
    external fun nativeListPlugins(): String
    external fun nativeMobileUiSeq(): Long
    external fun nativePollMobileUiEvents(sinceSeq: Long): String
    external fun nativeSetPluginEnabled(className: String, enabled: Boolean): String
    external fun nativeSetPinned(pluginName: String, pinned: Boolean): String   // Finding 6: star by display name
    external fun nativeGetPluginConfig(group: String): String
    external fun nativeSetConfig(group: String, key: String, value: String): String
    external fun nativeProcessedWriteId(): Long
    // A8: persist pending plugin-config writes (call when the app backgrounds). Off the UI thread (onBridge).
    external fun nativeFlushConfig(): String

    // AP-2a bridge-amortized drain surfaces (off the UI thread via [onBridge]); "" = nothing pending / unresolved.
    external fun nativeNextNotification(): String   // Finding 9:  "titlebody" or ""
    external fun nativeNextLink(): String            // Finding 25: a URL or ""
    external fun nativeHubState(): String            // Finding 17: {"refreshing":bool,"seq":int}
    external fun nativeHubManifest(): String          // Finding 17: {"status":loading|ok,...,plugins:[...]}
    external fun nativeHubInstall(internalName: String): String     // Finding 17: {"ok":true,"writeId":n}
    external fun nativeHubUninstall(internalName: String): String   // Finding 17: {"ok":true,"writeId":n}
    external fun nativePublishNotification(title: String, body: String)   // host-origin alert into the shared queue
    external fun nativePublishLink(url: String)                            // host-origin link into the shared queue

    // A5 Tier-1 plugin panels: a plugin's Swing side-panel rendered off-screen to an ARGB int[] (like the
    // game blit), pulled here and shown in a zoom/pan view; taps map back via panelClick. Off the UI thread.
    external fun nativeListPanels(): String
    external fun nativeOpenPanel(index: Int): String
    external fun nativeClosePanel(): String
    external fun nativePanelWidth(): Int
    external fun nativePanelHeight(): Int
    external fun nativePanelFrameSeq(): Int
    external fun nativePanelPixels(): IntArray?
    external fun nativePanelClick(x: Int, y: Int): String

    // A5 Tier-2 native reflow: curated native screens for Quest Helper + World Switcher (QuestHelperBridge /
    // WorldSwitcherBridge). isAvailable() gates native-vs-Tier-1: Quest is false unless the hub plugin is
    // installed (→ fall back to the rendered panel); World Switcher is built-in. Off the UI thread (onBridge).
    external fun nativeQuestAvailable(): Boolean
    external fun nativeQuestSetUiOpen(open: Boolean)
    external fun nativeQuestListJson(): String
    external fun nativeQuestActiveJson(): String
    external fun nativeQuestStart(questName: String)
    external fun nativeQuestStop()
    external fun nativeWorldAvailable(): Boolean
    external fun nativeWorldSetUiOpen(open: Boolean)
    external fun nativeWorldListJson(): String
    external fun nativeWorldHop(worldId: Int)

    // Finding 14 — XP Tracker native reflow (read-only data panel; same facade shape as Quest/World).
    // setUiOpen brackets the screen so the bridge only rebuilds the snapshot while it's shown. Off the UI thread.
    external fun nativeXpAvailable(): Boolean
    external fun nativeXpSetUiOpen(open: Boolean)
    external fun nativeXpJson(): String
    external fun nativeLootAvailable(): Boolean
    external fun nativeLootSetUiOpen(open: Boolean)
    external fun nativeLootJson(): String
    external fun nativeGeAvailable(): Boolean
    external fun nativeGeSetUiOpen(open: Boolean)
    external fun nativeGeJson(): String

    // Finding 15 — overlay edit (OverlayBridge): list movable overlays, snap one to a corner, reset to default.
    // snap/reset return the bridge JSON ack; positionName is an OverlayPosition (TOP_LEFT/.../BOTTOM_RIGHT).
    external fun nativeOverlayAvailable(): Boolean
    external fun nativeOverlaySetUiOpen(open: Boolean)
    external fun nativeOverlaysJson(): String
    external fun nativeOverlaySnap(name: String, positionName: String): String
    external fun nativeOverlayReset(name: String): String

    // Finding 22 — infobox menu (InfoBoxBridge): list infoboxes + their menu options, invoke one by index.
    external fun nativeInfoBoxAvailable(): Boolean
    external fun nativeInfoBoxSetUiOpen(open: Boolean)
    external fun nativeInfoBoxesJson(): String
    external fun nativeInfoBoxInvoke(index: Int, option: String): String

    // A6 chrome: the per-frame MobileChromeBridge read-only snapshot (escaped JSON: tab/panel/minimap/orb/chat/
    // viewport canvas bounds the native edge-rails read). Gated by -Drunelite.mobilechrome. Off the UI thread.
    external fun nativeChromeSnapshot(): String

    // A6 overlay: the render-thread-PUBLISHED chrome snapshot — lock-free, no HotSpot attach, safe to read each
    // frame from the Choreographer tick (UI thread). Drives the native 2-col tab-grid overlay.
    external fun nativeChromeSnapshotPub(): String

    // A6 chrome write surface (HudVeneerBridge; gated -Drunelite.hudveneer): hide the 14 engine tab stones (the
    // native 2-col grid replaces them), open a tab by replaying its stone op-script (works while hidden), and
    // relocate the engine side panel flush-left of the grid. Off the UI thread ONLY (via [onBridge]) — they
    // daemon-attach the calling thread to HotSpot. Cheap fire-and-forget setters re-applied by the engine tick.
    external fun nativeSetStonesHidden(hidden: Boolean)
    external fun nativeOpenTab(tabIndex: Int)
    external fun nativeRelocateSidePanel(x: Int, y: Int)

    // A6 chat-at-top: move the engine's own chatbox to (x,y) canvas px and flip it to OSRS transparent-chat mode
    // (the tabs / scrollback / colours / input all stay live — it IS the real chat). x<0 stops relocating. Off
    // the UI thread (via [onBridge]); re-applied by the engine tick.
    external fun nativeRelocateChat(x: Int, y: Int, transparent: Boolean)

    // A7 edge-to-edge: pull the engine minimap+orbs cluster (MAP_CONTAINER) into the top-right safe area. It
    // stays the live engine HUD (minimap click-to-walk + orb clicks keep working). x<0 stops. Off the UI thread.
    external fun nativeRelocateMinimap(x: Int, y: Int)

    // A3 enlarged native chat-tab veneer. The per-frame chat-tab geometry/state snapshot (escaped JSON: each of
    // the 7 tabs' canvas bounds + selected/new/filter state) is PUBLISHED by the render thread — read lock-free
    // from the Choreographer tick (no HotSpot attach). The two setters (off the UI thread) tell the engine to
    // push the message area below the taller native row (rowH canvas px) / keep its natural width (0).
    external fun nativeChatSnapshotPub(): String
    external fun nativeSetChatTabRowHeight(canvasPx: Int)
    external fun nativeSetChatAreaWidth(canvasPx: Int)

    // AP-0 chat scroll-by-drag: scroll the chat by deltaCanvasPx (positive = toward newer/bottom) via the
    // chatbox's OWN scroll layer — not a mouse wheel, which would zoom the camera under the transparent chat.
    // Off the UI thread (via [onBridge]); accumulated and applied by the engine tick.
    external fun nativeScrollChat(deltaCanvasPx: Int)

    // Kotlin-friendly aliases.
    fun setEnv(key: String, value: String) = nativeSetEnv(key, value)
    fun setStdioRedirect(enabled: Boolean) = nativeSetStdioRedirect(enabled)
    fun setSurface(surface: Surface?) = nativeSetSurface(surface)
    fun click(x: Int, y: Int, button: Int) = nativeClick(x, y, button)
    fun touch(type: Int, x: Int, y: Int, button: Int) = nativeTouch(type, x, y, button)
    fun camera(dx: Int, dy: Int) = nativeCamera(dx, dy)
    fun clearHover() = nativeClearHover()
    fun setShiftHeld(held: Boolean) = nativeSetShiftHeld(held)
    fun wheel(x: Int, y: Int, rotation: Int) = nativeWheel(x, y, rotation)
    fun clickPrecise(x: Int, y: Int, button: Int) = nativeClickPrecise(x, y, button)
    fun move(x: Int, y: Int) = nativeMove(x, y)
    fun text(s: String) = nativeText(s)
    fun keyStroke(vk: Int) = nativeKeyStroke(vk)
    fun isTextInputOpen() = nativeIsTextInputOpen()
    fun interactTapSeq() = nativeInteractTapSeq()
    fun selectMenuPoint(x: Int, y: Int) = nativeSelectMenuPoint(x, y)
    fun menuSnapshot() = nativeMenuSnapshot()
    fun menuPublishSeq() = nativeMenuPublishSeq()
    fun frameWidth() = nativeFrameWidth()
    fun frameHeight() = nativeFrameHeight()
    fun framePixels(): IntArray? = nativeFramePixels()

    // ---- A3.5 bridge request/response thread --------------------------------------------------------
    // A single dedicated background thread the UI posts plugin-plane requests to, so the ART UI thread NEVER
    // attaches to HotSpot (ANR risk) and HotSpot only ever sees one caller from the ART side. The thread
    // daemon-attaches to HotSpot on its first bridge call (native layer) and stays attached for the process.
    private val bridgeExec: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "rl-bridge").apply { isDaemon = true } }

    /** Run [block] on the rl-bridge thread; returns a Future (do NOT .get() it on the UI thread). */
    fun <T> onBridge(block: () -> T): Future<T> = bridgeExec.submit(block)

    fun listPlugins() = nativeListPlugins()
    fun mobileUiSeq() = nativeMobileUiSeq()
    fun pollMobileUiEvents(sinceSeq: Long) = nativePollMobileUiEvents(sinceSeq)
    fun setPluginEnabled(className: String, enabled: Boolean) = nativeSetPluginEnabled(className, enabled)
    fun setPinned(pluginName: String, pinned: Boolean) = nativeSetPinned(pluginName, pinned)
    fun getPluginConfig(group: String) = nativeGetPluginConfig(group)
    fun setConfig(group: String, key: String, value: String) = nativeSetConfig(group, key, value)
    fun processedWriteId() = nativeProcessedWriteId()
    fun flushConfig() = nativeFlushConfig()
    fun nextNotification() = nativeNextNotification()
    fun nextLink() = nativeNextLink()
    fun hubState() = nativeHubState()
    fun hubManifest() = nativeHubManifest()
    fun hubInstall(internalName: String) = nativeHubInstall(internalName)
    fun hubUninstall(internalName: String) = nativeHubUninstall(internalName)
    fun publishNotification(title: String, body: String) = nativePublishNotification(title, body)
    fun publishLink(url: String) = nativePublishLink(url)

    fun listPanels() = nativeListPanels()
    fun openPanel(index: Int) = nativeOpenPanel(index)
    fun closePanel() = nativeClosePanel()
    fun panelWidth() = nativePanelWidth()
    fun panelHeight() = nativePanelHeight()
    fun panelFrameSeq() = nativePanelFrameSeq()
    fun panelPixels(): IntArray? = nativePanelPixels()
    fun panelClick(x: Int, y: Int) = nativePanelClick(x, y)

    fun questAvailable() = nativeQuestAvailable()
    fun questSetUiOpen(open: Boolean) = nativeQuestSetUiOpen(open)
    fun questListJson() = nativeQuestListJson()
    fun questActiveJson() = nativeQuestActiveJson()
    fun questStart(questName: String) = nativeQuestStart(questName)
    fun questStop() = nativeQuestStop()
    fun worldAvailable() = nativeWorldAvailable()
    fun worldSetUiOpen(open: Boolean) = nativeWorldSetUiOpen(open)
    fun worldListJson() = nativeWorldListJson()
    fun worldHop(worldId: Int) = nativeWorldHop(worldId)
    fun xpAvailable() = nativeXpAvailable()
    fun xpSetUiOpen(open: Boolean) = nativeXpSetUiOpen(open)
    fun xpJson() = nativeXpJson()
    fun lootAvailable() = nativeLootAvailable()
    fun lootSetUiOpen(open: Boolean) = nativeLootSetUiOpen(open)
    fun lootJson() = nativeLootJson()
    fun geAvailable() = nativeGeAvailable()
    fun geSetUiOpen(open: Boolean) = nativeGeSetUiOpen(open)
    fun geJson() = nativeGeJson()
    fun overlayAvailable() = nativeOverlayAvailable()
    fun overlaySetUiOpen(open: Boolean) = nativeOverlaySetUiOpen(open)
    fun overlaysJson() = nativeOverlaysJson()
    fun overlaySnap(name: String, positionName: String) = nativeOverlaySnap(name, positionName)
    fun overlayReset(name: String) = nativeOverlayReset(name)
    fun infoBoxAvailable() = nativeInfoBoxAvailable()
    fun infoBoxSetUiOpen(open: Boolean) = nativeInfoBoxSetUiOpen(open)
    fun infoBoxesJson() = nativeInfoBoxesJson()
    fun infoBoxInvoke(index: Int, option: String) = nativeInfoBoxInvoke(index, option)
    fun chromeSnapshot() = nativeChromeSnapshot()
    fun chromeSnapshotPub() = nativeChromeSnapshotPub()
    fun setStonesHidden(hidden: Boolean) = nativeSetStonesHidden(hidden)
    fun openTab(tabIndex: Int) = nativeOpenTab(tabIndex)
    fun relocateSidePanel(x: Int, y: Int) = nativeRelocateSidePanel(x, y)
    fun relocateChat(x: Int, y: Int, transparent: Boolean) = nativeRelocateChat(x, y, transparent)
    fun relocateMinimap(x: Int, y: Int) = nativeRelocateMinimap(x, y)
    fun chatSnapshotPub() = nativeChatSnapshotPub()
    fun setChatTabRowHeight(canvasPx: Int) = nativeSetChatTabRowHeight(canvasPx)
    fun setChatAreaWidth(canvasPx: Int) = nativeSetChatAreaWidth(canvasPx)
    fun scrollChat(deltaCanvasPx: Int) = nativeScrollChat(deltaCanvasPx)
}
