# Audit result — full iOS app audit (security · performance · UX · architecture), 2026-06-12

Read-only audit of the native iOS app on `master` @ `a60ed250b`. No code changed, nothing committed. Scope:
the entire iOS feature surface — `ios/app/HelloShell/` (ObjC shell), the EAGL/Zone GL bridges under
`ios/dev-tools/gpu-routec/` + `ios/dev-tools/gpu-zone/`, the deploy/tooling scripts, and the shared Java
backend in `runelite-client/src/main/java/net/runelite/client/headless/` plus our seams in `Hooks.java` /
`RLHDStarter.java`. The `android/` tree was ignored except where shared Java serves iOS.

Method: every finding carries a `file:line` cite with a verbatim quote; the load-bearing P0/P1 cites were
re-read and confirmed by hand. The app cannot be run here (JIT needs the phone + StikDebug attach), so
behavioural claims that need the device are tagged **DEVICE-VERIFY**. Accepted risks from the brief
(ban risk, per-launch StikDebug attach, the patched libjvm, CPU renderer as default, `FINDINGS.md` dead-ends)
are not re-litigated.

---

## Executive summary (top 5)

1. **Secrets and a keystroke transcript live in a USB/Files-exposed directory.** `Info.plist` sets
   `UIFileSharingEnabled` + `LSSupportsOpeningDocumentsInPlace` over `Documents/`, which holds `tokens.json`
   (the live game-session bearer) **and** `hello-shell.log` (a full typed-text transcript + account display
   name / character id) **and** `jvm-stdio.log` (unfiltered Java stdout). Anyone with the unlocked phone or a
   trusted Mac can copy all of it. **[SEC-1 P0, SEC-2 P1]**
2. **The bridge calls into a VM marked dead, and nothing tears the render loop down.** At the 240 s boot
   timeout `gVM` is set NULL while the cached `gBridgeClass` and a never-invalidated `CADisplayLink` keep
   driving JNI; it is masked today only because `DestroyJavaVM` blocks forever on the engine's non-daemon
   threads. The first clean VM exit is a use-after-destroy crash. **[ARCH-1 P0]**
3. **`appendLine` is O(n²) with an fsync per line, on the main thread, with an unbounded buffer.** Every log
   line rebuilds the whole console string and `synchronizeFile`s; at ~2 lines/s growing forever this becomes
   tens-of-ms main-thread stalls inside a long session — on the default CPU renderer. **[PERF-1 P0]** The CPU
   present path also copies the full canvas three times per frame (~600 MiB/s of main-thread traffic). **[PERF-2 P1]**
4. **The upstream-merge seam is a 282-line in-body diff through the hottest callback, and the HUD apply
   swallows every exception.** `Hooks.java` carries 282 fork insertions woven through `draw()`/`frame()`/
   `drawScene()`; `HudVeneerBridge.tick`/`preRender` wrap their entire per-frame work in `catch (RuntimeException
   ignore) {}`. After any `git merge upstream/master` the mobile HUD can silently stop with no log line.
   **[MERGE-1 P0, JAVA-1 P1]**
5. **Failure states present as success or as a soft-brick.** A dying JVM freezes the last frame or vanishes to
   the home screen with no message **[UX-2 P1]**; the `▦` toggle hides the native tabs without un-hiding the
   engine stones, removing *all* tab access with no recovery **[UX-1 P0]**; cancelling login still shows
   "Logged in ✓" **[UX-4 P1]**; the screenshot button saves a stale boot frame under 117HD **[UX-3 P1]**; a
   failed HD init silently keeps the CPU renderer while the panel says "selected" **[UX-5 P1]**.

Net: the renderer/JVM/HUD engineering is solid and the happy paths are well built, but there has been **no
release-hardening, no failure-UX, and no teardown pass**. The highest-leverage work is (a) get secrets+logs out
of the shared Documents dir, (b) add a real JVM-death/teardown path, (c) collapse the `Hooks.java` seam before
the next upstream merge, and (d) make `appendLine` / the CPU feed stop doing O(n) work per line/frame.

---

## P0 findings

### SEC-1 (P0) — `Documents/` is exposed to Files.app + USB and holds the live session bearer
**Evidence.** `ios/app/HelloShell/Info.plist:61-64`:
```
<key>UIFileSharingEnabled</key>
<true/>
<key>LSSupportsOpeningDocumentsInPlace</key>
<true/>
```
`tokens.json` is written to and read from `NSDocumentDirectory`: `ViewController.m:955-956`
`NSString *docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, …); NSString *path = [docs
stringByAppendingPathComponent:@"tokens.json"];` (write), `JVMLauncher.m:490` same path (read →
`setenv("JX_SESSION_ID", …)` at `:516`). The on-device file carries `session_id` + `chosen_account_id`
(`ViewController.m:957-959`); `access_token`/`refresh_token` are written empty on-device (`:962-963`), but the
session id is itself the play-token bearer the gamepack mints from.

**Why it matters.** Both file-sharing keys true expose the *entire* app Documents container to the Files app
("On My iPhone → HelloShell") and to Finder/iTunes file sharing over USB. The write does use
`NSDataWritingFileProtectionComplete` (`ViewController.m:973-974`), so the file is encrypted *at rest while the
device is locked* — but during normal play the device is unlocked, and any Files browse or trusted-Mac
connection copies the session bearer straight out. For a throwaway account the blast radius is bounded, but the
pattern would leak a real session.

**Fix sketch.** Move `tokens.json` out of `Documents/` into `Library/Application Support/` (not surfaced by file
sharing) or store the session blob in the Keychain. If logs must stay user-retrievable, keep *only* logs in
Documents and never the credential — or drop `UIFileSharingEnabled`/`LSSupportsOpeningDocumentsInPlace` entirely
and pull diagnostics via the Xcode device container. **DEVICE-VERIFY:** confirm `tokens.json` shows under Files →
On My iPhone → HelloShell.

### ARCH-1 (P0) — JVM teardown leaves the reverse-JNI bridge and `CADisplayLink` live against a destroyed VM
**Evidence.** `JVMLauncher.m:1029-1030`:
```
atomic_store(&gVM, NULL);   // VM going away — stop the bridge before DestroyJavaVM
(*vm)->DestroyJavaVM(vm);
```
But `ensureBridge()` short-circuits on the cache *before* re-checking `gVM` — `JVMLauncher.m:144` `if
(gBridgeClass) return YES;` — so `gMainEnv` + ~70 cached `jmethodID`s keep working after `gVM` is NULL. The
display link is created (`ViewController.m:2339` `addToRunLoop:`) and **never invalidated** — `grep 'displayLink
invalidate'` over `ViewController.m` returns nothing; the JVM-thread-return handler only logs and releases the
bg task (`ViewController.m:2473-2475` `[self appendLine:@"[shell] JVM thread returned"]; [self endBgTask];`).

**Why it matters.** This is currently masked by an accident: `DeviceHeadlessBoot.main` returns at the
`-Dheadless.runSeconds=240` timeout (set at `JVMLauncher.m:848`; the boot prints `done: timeout after 240s` at
`DeviceHeadlessBoot.java:101`), so ~4 minutes into *every* session `gVM` is nulled and `DestroyJavaVM` is
called — but `DestroyJavaVM` blocks forever on the engine's non-daemon threads, so `"DestroyJavaVM done."` never
prints and the session keeps running on the bypassed cache. The moment any path lets `DestroyJavaVM` complete
(a clean AWT shutdown, an in-app "restart JVM" feature, or the engine making its threads daemon), the next
`onDisplayTick → latestFrame → CallStaticIntMethod` is a use-after-destroy crash.

**Fix sketch.** On JVM-thread return, dispatch to main: invalidate `displayLink`, stop the PiP pump, then null
`gBridgeClass`/`gMainEnv`/all cached facade classes in one `bridgeInvalidate()`; make `ensureBridge` re-check
`atomic_load(&gVM)` even when cached. Stop nulling `gVM` *before* the blocking `DestroyJavaVM` — null it after,
or replace the 240 s poll-return with an explicit park (the "minimise survival window" reason no longer applies
to a live interactive session). **DEVICE-VERIFY:** set `runSeconds=30` on a probe build and watch the tick after
the teardown line.

### PERF-1 (P0) — `appendLine`: O(n²) whole-string rebuild + per-line `fsync`, on main, unbounded growth
**Evidence.** `ViewController.m:1474-1481`:
```
dispatch_async(dispatch_get_main_queue(), ^{
    self.console.text = [self.console.text stringByAppendingString:out];
    if (self.console.text.length > 0) {
        [self.console scrollRangeToVisible:NSMakeRange(self.console.text.length - 1, 1)];
    }
    [self.logFile writeData:[out dataUsingEncoding:NSUTF8StringEncoding]];
    @try { [self.logFile synchronizeFile]; } @catch (__unused NSException *e) {}
});
```
Feeders: a heartbeat every 60 frames (≈2 lines/s at 120 Hz) plus a line per tap / pan / pinch-notch / chat
action / menu event.

**Why it matters.** Every append copies the *entire* console string (O(n)), `setText` re-fixes attributes (O(n)),
and `scrollRangeToVisible` forces glyph layout to the end (O(n)) *even while the debug panel is hidden*; plus
`synchronizeFile` is a real `fsync` (1–10 ms) on the main thread per line. `n` grows ~100–200 B/s forever → after
an hour it is ~0.5–1 MB → ~20–60 ms main-thread stalls twice a second = dropped frames in any long session, and
the fsync adds input-latency jitter from minute one. This is the default-renderer hot path.

**Fix sketch.** Cap the console (trim to the last ~64 KB), append via `textStorage` instead of whole-string
replace, only layout/scroll when the debug panel is visible, and drop `synchronizeFile` (the dup2'd
`jvm-stdio.log` is already the crash-proof channel). **DEVICE-VERIFY:** Instruments hitches after 30–60 min vs
`appendLine` wall time as a function of console length.

### MERGE-1 (P0) — `Hooks.java` carries a 282-line in-body fork diff through the hottest upstream callback
**Evidence.** `git diff <merge-base> master --numstat -- …/callback/Hooks.java` → **`282 0`**; the file now
contains 83 `net.runelite.client.headless.*` references (`grep -c`), almost all as inline
`if (X.isEnabled()) { X.tick(client); }` blocks inside `draw()` (`Hooks.java:391-600`, `:626-668`), plus edits
in `frame()` (`:281-289`, `HudVeneerBridge.preRender(client)`) and `drawScene()` (`:769-783`). ~22 of ~30 call
sites are default-off dev probes (see JAVA-7).

**Why it matters.** `Hooks.draw` is one of upstream's most actively maintained methods (GPU path, stretched mode,
overlay layers all live there). Every upstream edit to `draw()`/`frame()`/`drawScene()` now lands inside or
adjacent to fork hunks, so each `git fetch upstream && git merge upstream/master` is a hand-resolved conflict in
a ~750-line method — the central friction point for the project's "track unmodified upstream RuneLite" premise.

**Fix sketch.** Collapse to a single seam: one `HeadlessSeams.draw(client, mainBufferProvider)` +
`.postComposite(...)` + `.drawScene()` dispatcher in `headless/` that owns the whole chain (probes included),
leaving 3 one-line calls in `Hooks`. `HudVeneerBridge.preRender` needs *zero* Hooks edits: `frame()` already
posts `BeforeRender` upstream — register an EventBus subscriber with last-priority instead. Diff shrinks from 282
lines to ~6. (Second-most-likely conflict site after this: `OverlayRenderer.positionSnapcorners`, a `+23/-10`
in-body edit in a method upstream touched 2026-05-05.) **DEVICE-VERIFY:** the chrome/menu flash fix (`a60ed250b`)
still holds — `preRender` must still run after all other `BeforeRender` subscribers.

### UX-1 (P0) — the `▦` toggle hides the native tabs but leaves the engine stones hidden → no tab access at all
**Evidence.** `ViewController.m:2172-2181` `onToggleChrome` hides the native tab buttons and reveals the
(vestigial) stone covers but never un-hides the engine stones:
```
- (void)onToggleChrome {
    self.chromeVisible = !self.chromeVisible;
    if (!self.chromeVisible) {
        for (UIButton *btn in self.tabButtons) btn.hidden = YES;
        for (UIView *cv in self.stoneCovers) cv.hidden = YES;   // reveal the real stones in the fallback view
        self.tabRailBg.hidden = YES;
        [self hideChatTabs];
    }
```
The engine stones are hidden engine-side at `ViewController.m:2110` (`[JVMLauncher setEngineStonesHidden:YES]`,
re-asserted each tick by the chrome-apply path) and `:2533`, and again per-frame on the Java side in
`HudVeneerBridge.tick`. The only un-hide call is the unsupported-layout branch (`:2104`).

**Why it matters.** `▦` is an always-visible, unlabeled button in the player's bottom-left stack. One tap and
inventory / prayer / equipment / all 14 tabs vanish with no native tab UI and no message — and because the
stone-hide is re-applied every frame, even un-hiding here wouldn't survive. Official mobile has no equivalent
dead-end.

**Fix sketch.** On `chromeVisible → NO`, both call `setEngineStonesHidden:NO` *and* suppress the per-frame
re-hide in `HudVeneerBridge` (a `stonesHidden` flag the bridge honours), re-enabling on toggle-on; or simplest,
retire the `▦` A/B toggle into the debug panel (it exists for a fallback the engine-side hide already replaced).
**DEVICE-VERIFY:** stones do not reappear after `▦` off while logged in.

---

## P1 findings

### SEC-2 (P1) — logs in the exposed dir carry a keystroke transcript, account PII, and unfiltered Java stdout
**Evidence.** Keystroke transcript — `ViewController.m:3771`
`[self appendLine:[NSString stringWithFormat:@"[shell] key text '%@'", string]];` (every committed in-game text:
chat, PMs, search, enter-amount). Account PII — `JVMLauncher.m:524`
`log(@"[jx] JX_* set: displayName='%@' characterId=%@ session=%@…", display, character, session…6)` (display
name + full `JX_CHARACTER_ID` + 6-char session prefix). Java stdout — `ViewController.m:1466`
`self.jvmFd = open(jpath.UTF8String, O_WRONLY|O_CREAT|O_TRUNC, 0644);` then `:1492-1493` `dup2(self.jvmFd,
STDOUT_FILENO); dup2(self.jvmFd, STDERR_FILENO);` (all libjvm/`System.out`/plugin output, no data-protection
class). `hello-shell.log` is created with `attributes:nil` (`ViewController.m:1461`) = default protection only.
Java-side source of the worst single line: `HudDataBridge.java:273` `log.info("[huddata] SNAPSHOT {}",
snapshotJson)` dumps 40 chat lines incl. sender names and private-message text; `HeadlessCapture.java:158/168`
log `launcherDisplayName` on every state transition.

**Why it matters.** These files sit in the same Files/USB-exposed Documents dir as SEC-1, at weaker protection,
and `jvm-stdio.log` is an unfiltered sink — anything any installed plugin prints lands in a user-readable
plaintext file. `hello-shell.log` is effectively a keylogger transcript.

**Fix sketch.** Drop the keystroke text (log `len=%lu`, not the content); log `launcherDisplayName != null` as a
boolean and redact `characterId`; debug-gate the `[huddata] SNAPSHOT` line; create all log files with
`NSFileProtectionComplete` (and set the class on the POSIX fd via `setattrlist`); ideally move logs out of
Documents (SEC-1). Gate all of it behind `#if DEBUG`.

### PERF-2 (P1) — CPU present path copies the full canvas three times per frame (+ a fourth alpha pass), all on main
**Evidence.** `latestFrame` (`JVMLauncher.m:1087-1102`): `GetIntArrayRegion` (full copy) → `CFDataCreate`
(second copy) → `CGColorSpaceCreateDeviceRGB()` + `CGImageCreate` *every frame*; assigned at
`ViewController.m:2678`. Then `pipPumpFrame` (`JVMLauncher.m:1154-1170`): a third `GetIntArrayRegion` into the
CVPixelBuffer + a full per-pixel alpha stamp `for (…) px[i] |= 0xFF000000u`. PiP is armed unconditionally at
launch (`ViewController.m:2387`), and the PiP layer is deliberately the *visible* presenter above the
`UIImageView` (`:2894-2899`), so the `CGImage` path renders fully-occluded output whenever the layer is healthy.

**Why it matters.** Canvas ≈ 1173×540×4 ≈ 2.42 MiB/frame. Per engine frame on main: 3 full copies + a
4.8 MiB alpha read/write ≈ **~12 MiB touched**; at ~50 fps ≈ **~600 MiB/s** main-thread traffic + a 2.42 MiB
`CFData` malloc/free per frame (~121 MiB/s allocator churn) + an occluded ~121 MiB/s CA bitmap commit. Applies
to both `cpu` and `base-gpu` modes.

**Fix sketch.** Make `copyFramePixelBufferSinceSeq` the single fetch (one `GetIntArrayRegion` + stamp into the
pooled IOSurface buffer); let the PiP layer present it; stop assigning `screen.image` while the PiP layer is
healthy (refresh the `UIImageView` fallback at ~1 Hz / on status change); derive screenshots from the pixel
buffer on demand.

### PERF-3 (P1) — `base-gpu` readback: `glFinish` + `glReadPixels` + scalar swizzle ≈ 16 ms of the ~20 ms frame
**Evidence.** `routec_gl_jni.m:252-259` `glFinish(); … GetPrimitiveArrayCritical … [RouteCBridge readbackInto:…]`;
`RouteCBridge.m:574-589` `glReadPixels(0,0,w,h,GL_RGBA,…)` then a per-pixel swizzle/flip pass. Magnitude noted in
code: `ViewController.m:2391` "the measured ~16ms/frame bottleneck".

**Why it matters.** A full GPU sync stall + 2.42 MiB readback + 4.8 MiB scalar swizzle on the JVM render thread,
serialized inside `swapBuffers`, so `base-gpu` sits at/below 50 fps and can never reach 60+. The published `int[]`
then *also* pays PERF-2's double-feed.

**Fix sketch.** Route `base-gpu` through the same pip-present FBO blit as 117HD (zero readback; the plumbing
exists in `rcPipPresent`), or PBO double-buffer the readback + NEON/vImage the swizzle; drop the `glFinish`
(it's only there to split the timing). **DEVICE-VERIFY:** fps before/after.

### PERF-4 (P1) — background-keepalive audio recovery is single-shot; a failed restart silently kills the session
**Evidence.** `ViewController.m:2886-2892` `onAudioInterruption` restarts only on `…TypeEnded`; `:2877-2884`
`startKeepaliveEngine` logs `startAndReturnError` failure and gives up; the only retry is on foreground
(`:1235`). No `AVAudioSessionMediaServicesWereResetNotification` handling; `…InterruptionOptionShouldResume` not
consulted.

**Why it matters.** A phone call / Siri / alarm while backgrounded stops the engine; re-activating an audio
session right after an interruption frequently fails → no session → iOS suspends within seconds → the game
socket dies. This is the most likely 30-min-soak failure mode, and it is unverified.

**Fix sketch.** On restart failure retry with backoff on the bg pump's queue (the `bgKeepTask` grace still
holds); handle media-services-reset by rebuilding the engine; honour the `ShouldResume` option flag.
**DEVICE-VERIFY:** inject a call mid-background soak; confirm the session survives.

### PERF-5 (P1) — 30-minute background soak: concrete watch list (UNVERIFIED)
**Evidence / what to measure** (the existing `memWatchdog` stops after 30 s — `JVMLauncher.m:616-630`):
- `task_vm_info.phys_footprint` + `os_proc_available_memory` each 60 s → failure: jetsam, avail → 0.
- `pipLayer.status` + `requiresFlushToResumeDecoding` at every bg/fg transition → failure: layer `.failed` /
  flush-required → frozen/black PiP (PERF-9).
- JVM pixel-buffer pool residency — the pool is uncapped (PERF-8) → footprint steps of ~2.42 MiB.
- `keepaliveEngine.isRunning` + interruption notifications per minute (PERF-4).
- `frameSeq` vs `pipFrameSeq` divergence → engine alive but pump stalled.
- `base-gpu` only: EAGL GL continues from the JVM thread in background (readback present) — GL-in-background kill
  risk; the CPU renderer is the safe soak mode (consistent with the memory note that base-gpu readback is the
  background-GL risk).

### JAVA-1 (P1) — `HudVeneerBridge.tick`/`preRender` swallow *all* `RuntimeException`s → whole-HUD silent death
**Evidence.** `HudVeneerBridge.java:539-541` `catch (RuntimeException ignore)\n{\n}` wraps the entire per-frame
apply (chat relocate, tab hide, stone hide, openTab, world map); `preRender` same at `:337-339`; 17 `ignore`
catches in this one file, 23 across the production bridges. Inverse hazard: `MobileUiBridge.tick`
(`MobileUiBridge.java:1443`) and `MobileChromeBridge.tick`'s main body have *no* catch — an exception there
escapes into the engine draw loop via `Hooks.draw`.

**Why it matters.** After an upstream merge changes a widget id, the veneer can throw on the first statement
every frame and the HUD just stops mid-application (e.g. chat moved but `CHATAREA` never resized) with zero log —
exactly the class of regression this port keeps debugging on-device. The unguarded ticks have the opposite
failure: one NPE/frame into the gamepack's render loop.

**Fix sketch.** One catch per tick that logs once per fault site (rate-limited), not per frame, then continues;
add the same guard around `MobileUiBridge.tick` and `MobileChromeBridge.tick`.

### JAVA-2 (P1) — snapshot JSON is built per *engine frame* but consumed at ~10 Hz; ~80% is discarded
**Evidence.** All snapshot builders run unconditionally from `Hooks.draw` each frame into volatile caches; the
shell polls at ~10 Hz (`ViewController.m:2695` "~10 Hz"). Worst offender `HudDataBridge.tick`
(`HudDataBridge.java:186-196`) copies the *entire* engine message table per frame to keep the last 40, iterates
every player + NPC (`:155-183`), and serializes `new StringBuilder(2048)` with per-char escaping (`:203`). Also
per-frame: `MobileChromeBridge.tick`, `HudVeneerBridge.buildChatSnapshot` (8 tabs × 4 widget lookups + allocs),
`MenuPeekBridge.tick`.

**Why it matters.** At ~50 fps this is sustained client-thread CPU + GC churn (hundreds of KB/s of garbage) on a
heap-capped phone where the client thread is also the renderer; only every ~5th build is ever read.

**Fix sketch.** Throttle each builder to a frame counter (~every 5–6 frames), or invalidate-and-rebuild on the
client thread from events: chrome geometry → `WidgetLoaded`/`VarClientIntChanged(TOPLEVEL_PANEL)`/
`CanvasSizeChanged`; chat → incremental `ChatMessage`; orbs → `StatChanged`/`VarbitChanged`; `isTextInputOpen` →
`VarClientIntChanged(INPUT_TYPE)` into a volatile. Minimap dots are genuinely continuous — throttle those to the
poll rate. `MobileUiBridge`'s event ring (`ConfigChanged`/`PluginChanged`) is the model to copy.

### JAVA-3 (P1) — JNI/main-thread calls into client-thread-only RuneLite API (camera RMW is a live race)
**Evidence.** `JavaBridge.onCameraDrag` (`JavaBridge.java:1297`) read-modify-writes live camera state off the
client thread: `c.setCameraYawTarget((c.getCameraYawTarget() + yawStep) & 2047);` (+ the pitch pair `:1306-1308`)
— racing the engine's per-tick camera writes. Also off-thread reads: `isTextInputOpen` →
`c.getVarcIntValue(INPUT_TYPE)` (`:1355`), `canvasWidth/Height` (`:440-450`), `c.getGameState()` in
`onClick`/`onClickPrecise` (`:586`, `:1136`). Contrast: tap ingress correctly hops via `ct.invoke` (`:590`).

**Why it matters.** RuneLite's API is client-thread-only; the camera RMW is a real lost-update race (two-finger
pan fights the engine's interpolation → yaw jitter under load), and `getVarcIntValue` can read a torn varc array.

**Fix sketch.** Accumulate pan deltas in `AtomicInteger`s (the proven `panWorldMap` pattern,
`HudVeneerBridge.java:551-561`) and apply them in `tapTick`/`Hooks.draw` on the client thread; replace the live
`isTextInputOpen` read with a volatile snapshotted on the client thread. **DEVICE-VERIFY:** camera feel unchanged.

### JAVA-4 (P1) — `HeadlessCapture` runs in production: in-world client-thread PNG encodes + an endless tally log
**Evidence.** Capture is always armed (`JVMLauncher.m:796` `-Drunelite.headless.capture.dir=…` unconditional,
`interval=10` at `:845`, `preLoginMax=40` at `:846`); `HeadlessCapture.java:128-131` logs ~5 `log.info`/s for the
whole session; `capture()` (`:177-227`) `ImageIO.write`s up to 40 login + 20 LOGGED_IN PNGs on the client thread.
`Hooks.java:657-660` gates it only off `client.isGpu()`.

**Why it matters.** The login-phase PNGs are load-bearing (the shell shows them pre-bridge), but the 20 in-world
captures are vestigial (the live blit replaced that path) — so the CPU renderer eats 20 full-frame PNG encodes
(frame hitches) in the first seconds in-world, and every mode pays a permanent ~5 lines/s of log + Documents
growth.

**Fix sketch.** Set the logged-in budget to 0 in the device launch args and stop the tally log once the bridge
publishes its first frame (or gate it behind a separate diag flag). Keep pre-login capture as-is.

### UX-2 (P1) — JVM death is unhandled: native crash kills the app; clean exit freezes the last frame
**Evidence.** `ViewController.m:2473-2475` only logs `"[shell] JVM thread returned"` + `endBgTask`; `onDisplayTick`
keeps the last `UIImage` (`:2677` `if (img == nil) return;`). No watchdog, alert, or relaunch UI; a HotSpot
SIGSEGV (JIT detach, OOM) terminates the process.

**Why it matters.** The two real failure shapes: (a) the app vanishes to the home screen, then relaunch demands
the StikDebug attach ritual with no explanation; (b) a frozen last frame that still takes (dead) taps. Official
mobile shows a disconnect/reconnect dialog.

**Fix sketch.** After the JVM thread returns, present a "RuneLite stopped — restart the app" alert and freeze
input; persist a "crashed last session" marker and surface a one-line notice on the Tap-to-Play cover. (Pairs
with the ARCH-1 teardown.)

### UX-3 (P1) — the screenshot button saves a stale boot frame under 117HD, with a false success message
**Evidence.** `ViewController.m:3754-3758` `onScreenshot` uses `[JVMLauncher latestFrame]`; in `gpuPresent` mode
the CPU frame seq stops advancing (`:2655-2657` `self.screen.image = nil`, "GL presents directly"), so
`latestFrame` returns the last cached CPU login/loading frame. Feedback is console-only (`:3763` "screenshot
saved to Photos").

**Why it matters.** In the shipped HD renderer the player taps the camera, gets the Photos permission prompt and
a silent "save", and Photos contains the boot screen — and there's no visible confirmation in *any* mode (the log
line lives behind the hidden debug panel).

**Fix sketch.** In `gpuPresent` mode source the screenshot from the PiP `CVPixelBuffer` path (or a bridge FBO
readback); add a brief flash/toast on save and an alert on failure. **DEVICE-VERIFY:** what 117HD actually saves.

### UX-4 (P1) — cancelling Jagex login still poisons the session and shows a false "Logged in ✓" alert
**Evidence.** `ViewController.m:937` sets `self.webKitUsedThisSession = YES;` when the login sheet is *presented*
(not on success); `onLaunch` (`:2305`) then routes every Play tap to `promptRestartToPlay`, whose copy
(`:1032-1039`) reads `tokens.json` and falls back to "your account" under the title "Logged in ✓".

**Why it matters.** A user who opens login and taps Close (never authenticating) can no longer play without
restarting, and the explanation claims they signed in.

**Fix sketch.** Branch `promptRestartToPlay` on whether `tokens.json` exists/changed — "Login cancelled — restart
required before playing" vs the signed-in copy; only set `webKitUsedThisSession` after the web view actually
loads.

### UX-5 (P1) — renderer switching has no failure UX: a failed HD init silently keeps CPU while the panel says "selected"
**Evidence.** Renderer rows show the saved preference, never live state — `ViewController.m:1312-1326`
`status = enabled ? @"● selected — restart to apply" : @"○ off"`. The reveal gate (`:2655` `hasPresentedFrame &&
sceneWarmedUp`) simply never fires on HD-init failure, so the CPU blit continues with no message;
`RLHDStarter.java:136` acknowledges an unported shader "stops the plugin" with nothing surfaced.

**Why it matters.** The failsafe (CPU keeps rendering) is good, but the user sees vanilla graphics, a panel
asserting 117HD is selected, and no error. (The apply-on-next-launch *messaging* itself is good —
`ViewController.m:1377-1382`.)

**Fix sketch.** Flag "HD selected but `gpuPresent` never engaged within N s of `loggedIn`" → one-time alert
"117HD failed to start — running the software renderer" + flip the row to an error state (expose
`RLHDStarter`'s failure string). **DEVICE-VERIFY:** on-device shader-compile failure.

### UX-6 (P1) — Tap-to-Play drops the user onto a black screen for the whole JVM boot; surprise mic prompt
**Evidence.** `ViewController.m:507-515` fades the cover out immediately on tap; the screen is empty dark gray
until the first engine frame (`:2676-2677` returns until `latestFrame` is non-nil); no progress UI. First boot
also triggers the mic-permission dialog (`Info.plist:58-59`) and, with no saved session, lands on the engine's
own login screen with no native hint to use "Log in with Jagex" first.

**Why it matters.** Official mobile shows a branded splash with a progress bar; here the polished cover is
replaced by black for many seconds (JVM create + RuneLite init + login), which reads as a hang.

**Fix sketch.** Keep the cover up with a spinner/status line ("Starting Java… loading RuneLite… logging in…")
driven off the existing boot-log lines or `frameSeq`, dismissed on the first blitted frame; gate Play (or show a
notice) when `tokens.json` is absent. **DEVICE-VERIFY:** boot-gap duration; whether the mic prompt appears.

### UX-7 (P1) — rapid taps are coalesced: a new tap during the settle window replaces the pending one
**Evidence.** `JavaBridge.java:648-649` "a new tap during AWAIT_ENGINE REPLACES the pending tap"; constants
`MAX_SETTLE 12`, `settleStable 2`, `CLICKED_TIMEOUT 10` (`:220-222`) → finger-up→click ~2 frames (~40 ms),
worst-case ~240 ms, then truth-event latency; the native ring defers up to 0.25 s for walk classification
(`ViewController.m:2806`). Also: the relocated chat strip (`chatHitCanvas`, `:3130-3139`) routes taps to
`postClickPrecise` even while chat is collapsed — bypassing the settle gate/ring/haptic over bare scene.

**Why it matters.** Spam-clicking (food, fletching, walk corrections) is core OSRS muscle memory; the achievable
click rate is throttled to the settle+truth cycle and intermediate taps in different spots are dropped.

**Fix sketch.** Queue a small FIFO (2–3) instead of replacing in `AWAIT_ENGINE`, or fast-path same-spot repeat
taps; shrink `chatHitCanvas` to the pill rect while collapsed. **DEVICE-VERIFY:** taps/s ceiling vs official
mobile.

### UX-8 (P1) — background notifications stop exactly when the keepalive makes them valuable
**Evidence.** `drainNotifications`/`drainLinks` run only from `onDisplayTick` (`ViewController.m:2708-2713`); the
`CADisplayLink` stops in background, and the bg pump only feeds PiP frames (`startPipBgPump` `:3017-3025`). The
keepalive explicitly keeps the JVM + socket running backgrounded (`Info.plist:45-52`).

**Why it matters.** The AFK-glance case (idle notifier, logout-timer warning) is the whole point of the
keepalive, but the delivery half was never rewired — banners fire only after the user returns. `MOBILE-UX-AUDIT.md`
Finding #4 wrote this off under the *old* foreground-only lifecycle; the PiP/keepalive work on master resurrected
it (see the staleness note below).

**Fix sketch.** Call `drainNotifications` from the 15 Hz bg pump (same main-queue thread the bridge env is bound
to), throttled to ~0.5 Hz. **DEVICE-VERIFY:** lock-screen banner appears while backgrounded.

### MERGE-2 (P1) — repo-rule violation: tracked docs name an external code-review tool and embed prohibited attribution-trailer tokens
**Evidence.** `ios/AUDIT-BRIEF-w1-phase3-awt.md:5` and `:49` name a third-party automated code-review tool (the
`> "<tool>-reviewed; the review verdict is folded into §5/§6/§6b"` line and the "a `<tool>` audit of the
consolidated…" line) — the exact class of mention the repo conventions prohibit in any shipping doc. Separately,
`ios/dev-tools/gpu-routec/WATER-SHIMMER-DEEP-DIVE-PROMPT.md:203`, `ios/HD-PHASE-A1-HANDOFF.md:85`, and
`ios/dev-tools/gpu-routec/DEPTH-FIX-HANDOFF.md:120` contain the verbatim machine-authorship trailer tokens (in
instructions *not* to attribute) that the scrub pattern named in the repo's commit-conventions flags.

**Why it matters.** The repo conventions require the codebase to "read as entirely the user's own work" with no
third-party-tool or machine-authorship mention in any shipping doc; these are tracked on `master`, so the strings
are already in clonable history.

**Fix sketch.** Reword the named-tool lines (e.g. "externally reviewed" / "an external audit") and soften the
boilerplate so it drops the trigger tokens. Because they are in history, a fully clean state needs a history
rewrite (filter-repo / rebase) before the repo is ever made public; a tip edit only fixes the head. (To keep
this audit doc itself compliant, the offending tool name is not reproduced here — `git grep -in` the docs above
to scrub.) **NOTE:** commit author/committer pairs and bodies were checked and are clean —
`git log --all --format='%ae %ce'` is `sayhijordy@gmail.com` only, no machine-authorship trailers anywhere.

### ARCH-2 (P1) — renderer mode is six representations across two languages with no single owner
**Evidence.** `NSUserDefaults` string `ViewController.m:497` (`@"rendererPref" ?: @"cpu"`) → 5 pending BOOLs
(`pendingRouteCL1/Gpu/Zone/GpuNoResize/ZoneDirect`, `:68-72`) → 3 launcher BOOLs (`JVMLauncher.h:22,28,35`) →
Java `-D` flags (`JVMLauncher.m:880/891/894`) → RouteCBridge layer-pointer state (`sPresentLayer` vs `sPipLayer`
vs neither, `RouteCBridge.m:90,309`) → runtime `gpuPresent/gpuPresentArmed` (`ViewController.m:159-160`) →
build-time `RC_BACKEND_ANGLE`/`RC_ANGLE_WINDOW_PRESENT`. Concrete trap: the ANGLE TU stubs
`setPipPresentLayer:` to a no-op (`RouteCBridge.m:268`) — if pip-present were ever armed there, the reveal
(`:2667`) stops the CPU blit with nothing feeding the layer → black screen, no log.

**Why it matters.** The reveal gate, the PiP pump gates, `onPlayTapped`, `onLaunch`, `rendererKeyForClass`, and
the 117HD config whitelist each re-derive the mode independently; a fourth renderer or re-enabling ANGLE means
coordinated edits across ViewController + JVMLauncher + RouteCBridge + Java, failing to a silent black screen.

**Fix sketch.** One `RendererMode` enum in a shared header, decoded from `rendererPref` in exactly one function,
with *derived* capability flags (`wantsGLBridge`, `presentPath`, `resizable`, `javaFlags`). JVMLauncher takes the
struct; RouteCBridge takes only `presentPath` + a layer; the no-op ANGLE stubs become log-once traps.

### ARCH-3 (P1) — `gMainEnv` thread affinity is convention-only across a ~70-method facade
**Evidence.** `JVMLauncher.m:37` `static JNIEnv *gMainEnv = NULL;     // the UI thread's attached env (main
thread only)`; set on whichever thread first runs `ensureBridge` (`:156`). All ~70 class methods use it with no
`NSAssert([NSThread isMainThread])` and no dispatch guard. Today every caller is main-bound and the one bg worker
correctly attaches its own env (`:118`).

**Why it matters.** A `JNIEnv` is invalid off its thread. The first contributor who wraps a slow JSON read
(`mobileHubManifest`, `worldListJson`) in `dispatch_async(global_queue, …)` gets memory corruption with no
diagnostic.

**Fix sketch.** One line at the top of `ensureBridge`: `NSCAssert(NSThread.isMainThread, …)` (debug builds), and
document the contract in `JVMLauncher.h`'s class comment.

### ARCH-4 (P1) — the GLSL downport cast table is duplicated between the runtime bridge and the offline gate
**Evidence.** `RouteCBridge.m:731-801` `rlhdCasts` (68 rules) vs `ZoneShaderAssemble.java:143-244` `CASTS`
(68 rules) — byte-identical today (programmatic diff: 0 differences), but the *surrounding* logic already
diverges: the version-strip regex differs (`RouteCBridge.m:659` strips any `#version` line incl. trailing
comment; `ZoneShaderAssemble.java:253` misses `#version 330 core // x`), a stale third 4-rule copy lives in
`ios/dev-tools/gpu-zone/app/ZoneDownport.m:90-97`, and the runtime adds fix overlays the gate never sees
(`RouteCBridge.m:842-891`). Both files claim to be the "source of truth" and point at each other.

**Why it matters.** The next RLHD rev bump adds a cast site; whoever fixes it in the assembler sees the offline
gate go green, ships, and the device shader fails `glCompileShader` at runtime → 117HD dies, device-only, after a
green gate.

**Fix sketch.** Make the rules data, not code — a checked-in `downport-rules.txt` (LHS\tRHS) that
`ZoneShaderAssemble` loads and a build step in `stage-ios-zone-jar.sh` emits as the ObjC table (or bundles for
RouteCBridge to parse at first use); delete/redirect `ZoneDownport.m`; unify the version-strip regex. (The known
Android `.glsl` copy is a different mechanism, out of scope.) **DEVICE-VERIFY:** 117HD boot + the glslang gate
both green from the single source.

### ARCH-5 (P1) — 93 silent `ExceptionClear`s in `JVMLauncher.m` render a per-frame Java fault as a frozen frame + dead input, no log
**Evidence.** `grep -c ExceptionClear JVMLauncher.m` → 93; the pattern (e.g. `:1061` in `latestFrame`, `:1183` in
`postTouchType`, `:1296` in `chromeSnapshot`) clears and returns with no log/counter and no distinction between
"optional method missing" (legitimate) and "the facade throws every call".

**Why it matters.** If `getFramePixels`/`onClick` starts throwing (OOM, post-merge regression), every symptom —
frozen frame, dead taps, missing HUD — appears with an empty log; this project's history shows device debugging
is the expensive loop.

**Fix sketch.** A shared `bridgeClear(JNIEnv*, const char *site)` that clears, bumps a counter, and rate-limit-logs
once per ~5 s; `ExceptionDescribe` to the crash-safe stderr fd on first occurrence per site. Mechanical
replacement of the existing two-line pattern. (Java mirror: promote `JavaBridge.ensureTruthSubscribed`'s
`catch` from `log.debug` to a once-only `log.warn` — `JavaBridge.java:785-788` — so the click-feel/menu-gate
truth path doesn't die invisibly at the default INFO config.)

---

## P2 findings (condensed)

**Performance**
- **PERF-6** — 120 Hz display link doubles every poll for no gain in cpu/base-gpu (content is ~50 fps);
  `% 6` polls run at 20 Hz though commented "~10 Hz". `ViewController.m:2336-2338`, `:2697-2752`. Request 60 Hz
  unless `gpuPresentArmed`.
- **PERF-7** — `updateChrome`/`updateChatTabs` rebuild UIKit content unconditionally at 20 Hz (≈160 attributed
  strings/s in `paintChatButton`, `ViewController.m:1702-1728`); no snapshot-equality gate; `reloadData` on the
  plugin table every 6th tick while open (`:2729-2733`). Add `isEqualToString:` gates; cache the two `UIFont`s.
- **PERF-8** — JVMLauncher's `CVPixelBufferPool` has no allocation cap (`JVMLauncher.m:1131-1147`) while the
  bridge's pool is capped at 6 (`RouteCBridge.m:359-364`); soak-leak candidate. Add the same threshold + skip on
  failure.
- **PERF-9** — PiP foreground recovery checks `.status==failed` but not `requiresFlushToResumeDecoding`
  (`ViewController.m:2982-2984`, `RouteCBridge.m:408-411`) → silent frozen/black PiP after backgrounding. Add the
  2-line flush. **DEVICE-VERIFY.**
- **PERF-10** — STRIP-marked diagnostics on hot paths: every shader compile writes in+out GLSL to Documents
  (`RouteCBridge.m:893-907`, fires on every 117HD recompile); `[perf]` trace fopen/append/close 1×/s on the
  render thread (`routec_gl_jni.m:266-276`); `dumpFaultState`×3 + 30 s `memWatchdog` at launch. Gate behind one
  debug define.
- **PERF-11** — background 15 Hz pump feeds the PiP layer even when PiP never engaged / was dismissed
  (`startPipBgPump` gates only on `pipController` existing, `ViewController.m:3017-3025`) — ~109 MiB/s of
  invisible background work. Gate on `isPictureInPictureActive`.
- **PERF-12** — `listPlugins` JSON is built on the iOS main thread via JNI (`JVMLauncher.m:1461-1465` →
  `MobileUiBridge.java:929+`, reflective descriptor builds per plugin) → a 5–20 ms main-thread hitch per refresh.
  Publish a volatile snapshot from the client thread on `PluginChanged`.

**Architecture / Java**
- **ARCH-6** — `RouteCBridge` cross-thread `sPresentedFrames` is a plain `long` raced between the JVM render
  thread (`:518/:524`) and main (`:552`); make it `_Atomic`, and add a 3-line threading-contract comment at the
  shared-state block (`:83`). `setPipPresentLayer:nil` should be the documented teardown call.
- **ARCH-7** — world-map zoom mutates `magnificationFilter` on three layers incl. the PiP layer from a 10 Hz
  poll (`ViewController.m:2609-2620`); since the PiP layer is a sublayer of `screen`, it inherits the map zoom
  transform (backgrounding with the map open shows a zoomed PiP). Route through one presenter-owned setter.
- **JAVA-5** — `MenuPeekBridge` has no idle gate (`MenuPeekBridge.java:86-140`) — full menu serialization every
  frame even when nothing is peeking; add a `setPeekActive` flag (the `uiOpen` pattern). `tickWorldMap` writes 4
  varcs every frame, map open or not (`HudVeneerBridge.java:593-596`); seed once + on `GameStateChanged`.
- **JAVA-6** — `String.replaceAll("<[^>]*>", "")` compiles a fresh `Pattern` per call in two per-frame paths
  (`HudVeneerBridge.java:1182`, `MenuPeekBridge.java:213`) → up to ~1.7k `Pattern.compile`/s. Hoist a
  `static final Pattern`, or reuse `net.runelite.client.util.Text.removeTags`.
- **JAVA-7** — ~20 dead dev-probe classes ship in the production jar (`ChatRelocateProbe`, `MobileChromeProbe`,
  `MobileUiSmokeRunner` (1118 lines), `WorldMapProbe`, …), all referenced from `Hooks.draw` behind default-off
  `static final` flags (JIT-folded, ~0 runtime cost) but the bulk of the `Hooks.draw` conflict surface (MERGE-1);
  `MobileUiSmokeRunner.arm()` runs unconditionally on boot (`RuneLiteHeadless.java:51`). Move to a `dev` source
  set excluded from the device jar.
- **JAVA-8** — native "reset to default" on a 117HD config restores the plugin's *desktop* defaults
  (`MobileUiBridge.resetConfig`, `MobileUiBridge.java:1389-1411`) — e.g. MSAA_8 / 8192 shadow map — and the
  `iosGfxSeeded` sentinel (`RLHDStarter.java:158-170`) blocks re-seed on relaunch → fps/heap blowout. Special-case
  the `hd`/`gpu` groups to re-apply the iOS seed. **DEVICE-VERIFY.**
- **JAVA-9** — `focused` is non-volatile but written from both the JNI thread and the client thread
  (`JavaBridge.java:267`, `:500-507`); `entered` got `volatile` for exactly this reason (`:268`). Make `focused`
  volatile. Also: `RLHDStarter.tick`/`RouteCGpuStarter.tick` are each called twice per frame from `Hooks.draw`
  (`:456`+`:628`, `:464`+`:635`) — delete the pre-composite pair (folds into MERGE-1).

**Security / tooling**
- **SEC-3** — a plugin-controlled URL auto-opens an in-app browser every frame with no user gesture:
  `drainLinks` (`ViewController.m:3722-3736`) pulls `mobileNextLink` and presents `SFSafariViewController`; scheme
  is checked (`:3727` https/http only, so no `file://` escape). Any Plugin-Hub plugin can enqueue an https URL →
  a phishing/drive-by primitive. Require a user gesture or a confirm sheet; rate-limit; foreground-only.
- **SEC-4** — OAuth `state`/`nonce` are generated and sent (`JagexLoginViewController.m:108-109`) but never
  validated on the intercepted redirect (`:149-159` reads only `id_token`). Low impact (the redirect is
  intercepted in-app pre-network), but a deviation from the flow. Compare `state` to `self.oauthState`; check the
  `id_token` nonce.
- **SEC-5** — the dev-machine throwaway `tokens.json` is uploaded to third-party cloud test farms
  (`android/dev-tools/firebase/fb-gate.sh:41/62`, `android/dev-tools/browserstack/bs-run.py:74-77`), and in the
  "baked into the APK asset" path embedded in the uploaded artifact. Android-side but it's the shared session
  secret; push at runtime, never bake, re-mint after cloud runs. (Out of the iOS scope strictly, included because
  it's the same `ios/dev-tools/tokens.json`.)
- **SEC-6** — `serve-ipa.sh` backgrounds a server (`:138-141` `nohup … disown` + `tailscale serve --bg`) with no
  timeout/trap (a stray nested 118 MB copy exists under the served dir); tailnet-scoped only and the IPA is
  secret-free, so low-sev. Serve an allow-listed file set, add a trap, delete the stray copy.
- **SEC-7** — tracked evidence logs under `ios/app/evidence/` (63 `*.log`) leak throwaway display names + the
  stable `JX_CHARACTER_ID` + a 6-char session prefix; no usable secret (access/refresh empty, session truncated),
  so PII hygiene. Gitignore the evidence `*.log`; redact the capture logger.
- **SEC-8** — `fetch-shield-tokens.sh` passes the session token on the `curl` command line (`:173/:296` `-H
  "Authorization: Bearer $SESSION_ID"` → visible via `ps`), writes the file at default umask before `chmod 600`
  (`:337-338`, brief world-readable window), and echoes the consent URL (carrying the id_token) on the error
  branch (`:154`). Single-user Mac, so low. Use `curl --config`/`-H @file`, `umask 077` before the write, drop the
  echo.

**UX polish** (all still-open; `MOBILE-UX-AUDIT.md` cross-refs noted)
- **UX-9** — OAuth error states dead-end inside the login sheet (only a 96-pt log strip + Close;
  `JagexLoginViewController.m:153/178/198`). Add a Retry button and plain-language HTTP-failure copy.
- **UX-10** — world-map close feels slow (~0.8 s) and the "native ✕" the code comments describe doesn't exist
  (`mapCloseCanvas` is written at `ViewController.m:2575` and never read); close goes through the engine X →
  game-tick + ~10 Hz poll latency. Optimistically restore chrome on a tap inside `mapCloseCanvas`. **DEVICE-VERIFY.**
- **UX-11** — the map-search keyboard never auto-dismisses (the map doesn't flip `INPUT_TYPE`, so the
  edge-triggered dismiss can't fire — `ViewController.m:3119-3125`) and likely covers the bottom-anchored search
  field. Resign first responder on map-open→closed and on a tap outside the search rect.
- **UX-12** — chat-pill `kChatHoldDelay = 0.15` (`ViewController.m:304`) invites accidental fan-out on slow taps,
  and the whole gesture vocabulary (tap collapse / hold fan / pull-down filter) has zero discoverability. Raise
  to ~0.25–0.3 s; add a one-time coach overlay. **DEVICE-VERIFY** the misclassification rate.
- **UX-13** — debug chrome in the player HUD: the always-visible `⌨⇧📷📺` + `⚙▦≡` stack
  (`ViewController.m:871-889`), the `≡` panel whose primary button still reads "Run AWT activation (§6.0)"
  (`:690`), emoji-on-black styling. Move `▦`/`≡` behind a dev affordance; restyle the four player-facing buttons
  as OSRS stones. (`MOBILE-UX-AUDIT.md` #1 closed the launch half.)
- **UX-14** — touch-hostile plugins (Custom Cursor, Key Remapping, Stretched Mode) are still listed as
  toggleable (`MobileUiBridge.java:1176-1178` defers the hide list), and the 4 s `kPluginStartGrace`
  (`ViewController.m:270`) false-flags slow legit starters as "✕ couldn't start". Ship the curated hide list;
  only show the failure on an actual start error. (`MOBILE-UX-AUDIT.md` #25/I3.)
- **UX-15** — the plugins panel has no search over ~250 rows (the Hub view has one,
  `PluginHubViewController.m:51-57`) and a blank empty state pre-launch (`refreshPluginList` →
  `pluginList = @[]`). Add the Hub's search bar; show "Plugins appear once the game is running".
- **UX-16** — heavy plugin panels remain the zoomable-image fallback with no typing path (GE/Notes/HiScore
  typing is dead — `PluginPanelsViewController.m:461-475`, `:580-587`); only Quest Helper + World Switcher are
  native. Route `postText` to the focused Swing component when a panel is open; surface "panel too large" instead
  of a silent blank. (`MOBILE-UX-AUDIT.md` #12/C10/R5.)
- **UX-17** — the keepalive arms unconditionally with no toggle and no in-app explanation that backgrounding
  keeps the character in-world up to the 30-min logout timer (only `Info.plist` comments) — a hardcore player who
  swipes home is online and killable while draining battery. Add a "Keep playing in background" toggle (default
  on) + one line of copy; detect interruption-induced logout on foreground. **DEVICE-VERIFY.**
- **UX-18** — single-device tuning: landscape-only (`Info.plist:40-44` + `UIRequiresFullScreen`), a `renderH = 540`
  magic constant (`ViewController.m:2362`), an iPhone-only action sheet with no popover anchor
  (`JagexLoginViewController.m:226`). Decide portrait before more HUD work; expose `renderH`/UI-scale/FPS as
  native settings. (`MOBILE-UX-AUDIT.md` OQ2/OQ5/R3.) **DEVICE-VERIFY** iPad / smaller iPhone.

---

## Architecture: the `ViewController.m` god object — concrete seams (no rewrite)

Numbers: **143 `@property`** (one class extension, lines 24–267), **1 ivar**, **30 file-scope `static`s**,
**13 `#pragma mark`s**, **121 methods**, one `@implementation` (329–3794); `buildUI` alone is 409 lines. The
change-coupling hub is `onDisplayTick` (`:2651-2810`), which interleaves ~10 subsystems on `% N` cadences — every
feature added since A3 has edited it. Proposed seams, each independently landable, behaviour-preserving:

1. **Private-header + categories (zero behaviour change).** Move the class extension to `ViewController+Private.h`;
   split implementations into categories along the existing pragma lines — `+ChatPill` (1597–2082), `+WorldMap`
   (2547–2645), `+Menu` (3209–3497), `+PiP` (2812–3077), `+TouchInput` (3079–3208, 3499–3793), `+PluginPanel`
   (1048–1455). State stays on the class.
2. **Extract `PipKeepaliveController : NSObject`** — the most self-contained cluster: `pipLayer`/`pipController`/
   `pipBgPump`/`pipFrameSeq`/`keepaliveEngine`/`keepaliveSource`/`bgKeepTask` + both AV delegate protocols + the
   KVO context; the VC keeps only the reveal decision and passes a host layer + an `isGpuPresent` block.
3. **Extract `ChatPillController`** — the ~30 `chat*` properties + `kChat*` statics; input = `chatSnapshot` JSON
   per tick, output = `JVMLauncher switch/scroll/collapse`. Already documented main-thread-only.
4. **Extract `WorldMapController`** — `mapOpen` + 11 `map*` props + `updateWorldMap`/`applyMapZoom`; pan/tap
   routing stays in the VC via `isOpen`/`hitTest` queries (and takes the ARCH-7 narrow display-transform interface).
5. **A poll-scheduler table** in `onDisplayTick` — an array of `{modulo, block}` replacing the inline `% 6 / % 30`
   ladder — makes the hub declarative without moving logic.
6. **A `RendererLaunchConfig` struct/enum** replacing the 5 `pending*` BOOLs (the shell half of ARCH-2).

These are low-risk because each cluster's inbound coupling is narrow (PiP: `screen.layer` + `gpuPresent`; chat:
one JSON + host view; map: `isOpen`/`hitTest`).

**Polling → events.** The whole ObjC↔Java surface is poll-only (`JVMLauncher.h` has no upcall API). The
`RegisterNatives` pattern is already proven here (the RouteC GL natives, `JVMLauncher.m:1009`). Register one
`JavaBridge.nativeNotify(int kind, long seq)` whose C impl just `dispatch_async(main, …)` — scalar args, so the
client-thread env only *receives*, respecting the `gMainEnv` affinity (ARCH-3); main-side handlers then pull
details via the existing getters. Convert in order: `interactTapSeq` (kills the 120 Hz poll), `textInputOpen`
edges, notification/link enqueue. Leave the 10 Hz geometry snapshots polled (cheap, state-shaped, self-healing).

---

## What's solid (verified, not problems)

- **Frame publish protocol.** `JavaBridge.snapshot`/`gpuPublish` write w/h then `frame` then bump `frameSeq` last
  (`JavaBridge.java:344-349`, `:385-391`), all volatile, single-writer (client thread); the native reader
  re-validates `len >= w*h` before copying — the resize multi-field race is guarded.
- **Tap state machine.** Every field documented client-thread-only holds — ingress via `ct.invoke`, `tapTick`
  from `Hooks.draw`, truth handlers on the EventBus thread; settle-gate allocations only during an in-flight tap;
  fail-open cap + CLICKED timeout present. No leaked taps.
- **CF release balance.** All five frame paths (`latestFrame`, `copyFramePixelBufferSinceSeq` incl. error legs,
  `pipPumpFrame`, `rcPipPresent`, `mobilePanelImage`) release CGImage/provider/colorspace/CVPixelBuffer/
  CMSampleBuffer correctly; no unbalanced retain found.
- **PiP feeder exclusivity + teardown.** Exactly one feeder at a time (shell pump gated `!gpuPresent`, bridge
  enqueue gated `sPipEnqueueOn` flipped only at the reveal); KVO removed in `teardownPictureInPicture`; the bg
  pump is a main-queue dispatch source (env affinity holds); `jitTimer` invalidated at launch.
- **OAuth internals.** Ephemeral `nonPersistentDataStore` (no persisted Jagex cookies); localhost redirect
  intercepted pre-network (no TLS-weakening delegate, no ATS exception — the only `http://` is the loopback
  redirect); logout deletes tokens + purges Jagex cookies; tokens written atomic + protection-complete.
- **Entitlements.** Only `com.apple.developer.kernel.increased-memory-limit`; `UIBackgroundModes` = `audio` only;
  mic/photo usage strings justified (Java Sound enumeration; screenshot add-only); no unused capability.
- **Git history.** `tokens.json` and the local-only conventions file (excluded via `.git/info/exclude`) never
  committed on any branch; no JWT/token literals in history
  (`git grep 'eyJ…'`, `git log -S` all empty); no `.p12`/`.mobileprovision`/keystore in history; the IPA bundle
  carries no `tokens.json`/`*.log`; commit author+committer are `sayhijordy@gmail.com` only with no
  machine-authorship trailers (the only such tool-name strings are the docs in MERGE-2).
- **LWJGL buffer semantics** are consistent (`position()/remaining()` for uploads in both forwarders — the
  historical capacity bug is fixed); the one `GetDirectBufferCapacity` left is the correct out-buffer case.
- **Plugin/config write lanes** land on the documented Java lanes (EDT for lifecycle, client thread for config)
  with ack/`writeId` polling; secret config values are masked in `getPluginConfig`
  (`MobileUiBridge.java:1081`). Many UX flows are genuinely well built — the long-press menu with its build-timeout
  fallback, the chat-pill state machine, the world-map gestures, the contextual keyboard, the Hub install
  progress, and the config editor are all clean (see `MOBILE-UX-AUDIT.md` items #5/#6/#8/#17/#27, closed in code).

---

## Note: `MOBILE-UX-AUDIT.md` is partly stale against `master`

That doc's "Hard constraints" still assert **CPU/CACIO only** ("GPU plugin and 117HD are shelved and immovable")
and a **foreground-only lifecycle** with "background notification / AFK-glance value is near-zero". `master`
contradicts both: three selectable renderers including 117HD pip-present ship today, and PiP + background
keepalive keep the JVM and socket alive backgrounded. The constraint list should be updated, and its Finding #4
(background notifications, written off under the old lifecycle) reopened — that's UX-8 above. The per-finding
gap analysis remains accurate where it cites code.
