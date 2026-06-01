# Audit brief — Phase 6 (W3): the native Jagex-Mobile UIKit shell + plugin config (2026-06-01)

Self-contained; a reviewer needs only this doc. Asking for an adversarial review of **(a) is the
reverse-JNI bridge still the right foundation** now that the full mobile control scheme rides on it, and
**(b) the design + sequencing for the native Jagex-Mobile-style UI shell, the native plugin-config panel,
and the remaining polish** — before we invest in the UI layer.

## Where we are (all device-proven this session, md5-gated against verbatim devicectl artifacts)
The full stack **plays like a real mobile client on device** (iPhone 17 Pro Max, iOS 26.5, TXM; JIT via
per-launch StikDebug attach): on-device JIT OpenJDK 21 → CACIO CTC toolkit → **real unmodified RuneLite**
→ live OSRS → **full-screen landscape**, with a complete touch + keyboard control scheme. Phases closed
this session (each GREEN, md5-gated; see `project_ios_gate_progress` memory + commits):
- **Control scheme** — tap = left click (walk/interact/menu-entry); **long-press = drag-to-select
  right-click menu** (press opens, slide highlights, release selects, release-off cancels); **one-finger
  drag = camera rotate** (direct `setCameraYawTarget`/`setCameraPitchTarget`, no synthesized middle-mouse);
  **pinch = zoom** (`MouseWheelEvent` — CTC accepts it, the one bridge unknown the Phase-5 review flagged,
  now retired). One-finger trio disambiguated by UIKit gesture mutual-exclusivity.
- **Keyboard** — hidden `UITextField` → AWT `KeyEvent`s (`onText`/`onKeyStroke`); typed text lands in OSRS
  chat. **Contextual auto-raise** for bank/GE search + private-message (`VarClientInt.INPUT_TYPE != 0`),
  edge-triggered. **Confirmed OSRS limitation:** general chat + "enter amount/name" set `INPUT_TYPE=NONE`
  (no pre-keystroke focus signal — RuneLite's own `ChatKeyboardListener` treats `NONE` as general chat),
  so those open via a ⌨ button; an input **accessory bar** (Hide / Send ⏎) dismisses it.
- **Full-screen landscape** — the game fills the **safe area** at the safe-area aspect. Mechanism (desktop
  de-risked first, then device): after `LOGGED_IN`, `HeadlessResize` resizes the gamepack `Component`
  inset-aware so the CACIO CTC canvas lands EXACTLY on a wide `WxH` and the gamepack switches to resizable
  layout and fills it (desktop 765×503→1272×562 filled; device safe-area 832×420 → canvas 1189×600,
  viewport 1189×600). **Caveat: the OSRS account must be in resizable display mode** (fresh = fixed →
  pillarbox); modern OSRS persists display mode server-side per account (set once, sticks).
- **Shell (debug)** — landscape-only; the game is the bottom layer; all debug UI (status / launch / canary
  / log console) lives in a **collapsible overlay panel toggled by a ≡ button**; a floating ⌨ button.

## The bridge as built (the thing to pressure-test for soundness)
`net.runelite.client.headless.JavaBridge` (gated `-Drunelite.bridge=true`) — the one reverse-JNI facade:
- **Frame:** `register(Client)` (top of `Hooks.draw`), `snapshot(MainBufferProvider)` (post-overlay
  composite, double-buffered + `frameSeq`), `getFrameWidth/Height/Seq/Pixels`.
- **Input (intent-level):** `onClick(x,y,button)` (the only click-synthesizer — focus-prime + move +
  press + release + click); `onMove(x,y)` (hover, for menu highlight); `onTouch(type,x,y,button)` (drag
  stream, no click on release); `onWheel(x,y,rotation)` (MouseWheelEvent); `onCameraDrag(dx,dy)` (camera
  yaw/pitch targets); `onText(String)` + `onKeyStroke(vk)` (KeyEvents); `isTextInputOpen()`.
- **Native** (`JVMLauncher.m`): `gVM` published atomically; `ensureBridge()` `AttachCurrentThreadAsDaemon`
  on the UI thread + cached class/method ids; `latestFrame` (seq-skip → CGImage); `postClick/postMove/
  postTouch/postWheel/postCamera/postText/postKeyStroke/textInputOpen`; `resizeSpec` → resize prop.
- **Resize:** `HeadlessResize.tick(client, state)` (gated `-Drunelite.headless.resize=WxH`) resizes the
  gamepack Component inset-aware on `LOGGED_IN`.

## Questions to adjudicate (verdict + reasoning each)
1. **Is the reverse-JNI bridge still the right foundation for the whole native UI?** It now carries blit +
   touch + camera + wheel + keyboard + resize + an input-state query, all main-thread JNI calls each
   display tick. (a) Is per-tick JNI (getFrameSeq + the input poll) fine, or should the engine push state
   (e.g. a shared struct / a single batched call) to cut JNI crossings? (b) The blit copies the int[] out
   each changed frame then `CGImageCreate`s — at ~1189×600 that's ~2.8 MB/frame; worth a zero-copy path
   (shared direct `ByteBuffer` / `CVPixelBuffer` / Metal texture), or still negligible? (c) Any soundness
   risk in growing the bridge surface further (more static method ids, more cross-thread reads)?
2. **UIKit vs SwiftUI for the chrome**, and lifecycle. The JVM runs on a worker thread; the blit + input
   run on the main thread via `CADisplayLink`; a `beginBackgroundTask` assertion keeps the process alive.
   Does adding a real native UI (nav, panels, sheets) risk the JVM-thread / CADisplayLink / background-task
   interplay (e.g. SwiftUI re-render storms, scene lifecycle)? Recommended structure?
3. **Native plugin-config panel** (the real remaining "plugin system" work). RuneLite runs unmodified, so
   `PluginManager` + overlays already work; what's missing is the desktop Swing config UI (we bypass
   `ClientUI`). Plan: expose `PluginManager` (list/enable/disable) + `ConfigManager` + `ConfigDescriptor`
   (per-plugin settings: bool/int/enum/color/keybind/…) over the bridge, and render them natively
   (UIKit list + typed controls), writing back via `ConfigManager.setConfiguration`. Pitfalls: config
   change must run on the client thread; enum/color/custom config items; plugins that draw their own
   overlays vs ones with interactive panels (the chatbox-panel inputs). Is a read/reflect-over-the-bridge
   approach right, or should the native side talk to a small Java config-facade we add (like JavaBridge)?
4. **Auto-set resizable display mode.** Today the user sets it once in OSRS settings (persists
   server-side). Worth automating so a fresh account fills the screen immediately? No clean
   ScriptID/varbit was found; options = synthesize the settings-interface interaction, or
   `client.runScript(...)` with the display-mode proc (id unknown/version-specific). Risk vs benefit —
   is server-side persistence enough to leave it manual?
5. **Chat-tap affordance.** General chat has no focus signal, so tapping the chat to raise the keyboard
   needs a positional/widget approach. Plan: use the real chatbox widget bounds (`client.getWidget(...)`,
   target the message-entry area, not the tabs) over the bridge, NOT a hardcoded rectangle. Sound? Which
   widget(s)? How to avoid false-triggers (tab switches, name clicks, links)?
6. **Higher-DPI / render resolution.** `renderH=600` (canvas ~1189×600) scaled to the native ~2868×1320
   panel via a linear filter. Raise `renderH` for crisper text/UI (cost: bigger buffer, more fill — perf
   is a non-issue here), or render at native and scale the gamepack UI? Sweet spot? Any CTC/gamepack
   limits on canvas size?
7. **Sequencing + biggest risk.** Cheapest-correct order to build the native shell (chrome scaffold →
   plugin list/config → chat-tap → auto-resizable → DPI) WITHOUT regressing the working controls, and the
   **single most likely thing to break or be wrong** that we should de-risk first.

## Constraints / trust rules (unchanged)
- **Device-only** for the real verdict; desktop OpenJDK 21 is the develop-first mirror (W2 rule).
- **Trust only verbatim device artifacts** (`jvm-stdio.log`/`hello-shell.log`/`hs_err`/crash `.ips` via
  `devicectl`) + bash-computed md5-gated verdicts (documented false-GREEN history).
- **GPLv3 sources (Amethyst/Pojav/StikDebug) are patterns-only**, never vendored.
- **Throwaway Jagex account only**; `tokens.json` is a secret kept out of git (pushed to the container at
  test time). Self-built client on live OSRS is **ban-risk / not shippable** — personal technical build.

## Pointers (for the reviewer, all in-repo)
- Bridge facade: `runelite-client/src/main/java/net/runelite/client/headless/JavaBridge.java`.
- Resize: `runelite-client/.../headless/HeadlessResize.java`; login: `HeadlessLogin.java`; capture:
  `HeadlessCapture.java`; window-less host: `runelite-client/.../ui/HeadlessClientUI.java`.
- Hooks wiring: `runelite-client/src/main/java/net/runelite/client/callback/Hooks.java` (`draw`).
- Native: `ios/app/HelloShell/{JVMLauncher.m,JVMLauncher.h,ViewController.m}`; `Info.plist` (landscape).
- Config/plugin model to reproduce natively: `runelite-client/.../config/ConfigManager.java`,
  `ConfigDescriptor.java`, `ConfigItemDescriptor.java`; `runelite-client/.../plugins/config/` (the desktop
  `PluginListPanel`/`ConfigPanel` — the UI we are replacing); `PluginManager`.
- Camera/input API seams: `runelite-api/.../Client.java` (`setCameraYawTarget`, `getCanvas`,
  `getVarcIntValue`, `runScript`, widgets).
- Prior briefs/verdicts (the rhythm): `ios/AUDIT-BRIEF-phase5-mobile-controls.md`,
  `ios/AUDIT-BRIEF-phase4-interactive-login.md`, `ios/AUDIT-BRIEF-w1-phase3-awt.md`.
- Evidence (verbatim, md5-noted in commits): `ios/app/evidence/phase5-*` (dt1/dt1b/dt2/dt3/fs2 logs +
  `phase5-resize-desktop-1272x562.png`).
