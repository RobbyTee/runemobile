# Audit brief — Phase 4 (W3): interactive login on device — reaching LOGGED_IN, then real touch (2026-06-01)

Self-contained; a reviewer needs only this doc. Asking for an adversarial review of a **plan + sequencing**
before we invest in device input plumbing. §6.2 just closed GREEN: **real RuneLite JIT-runs on the
on-device OpenJDK 21 and renders the live-OSRS login screen** (RS:OSRS banner + EULA Accept/Decline dialog
+ live world list), captured to PNG, corruption-free over a sustained 1950+-draw / 40s+ run. The client is
parked at `LOGIN_SCREEN` showing the EULA dialog and goes no further on its own. The next milestone is to
make it **playable**: drive it from `LOGIN_SCREEN` → `LOGGED_IN` (game world), then build real touch
control. We want the cheapest correct path adjudicated, and the device-specific failure modes pressured,
before we write native input code.

## Platform (all device-proven unless noted)
- **Device:** iPhone 17 Pro Max, iOS 26.5, TXM. JIT via StikDebug per-launch attach.
- **JVM:** Amethyst-CDN jre21 (OpenJDK 21.0.8, HotSpot, G1, `MirrorMappedCodeCache`), embedded via
  `JNI_CreateJavaVM` in a native app (`HelloShell`). Canonical StikDebug JIT script services the single
  `brk #0x69` 64 MB code-cache reservation, no fault net (md5 `b2e86ed924d56032bc5f194dce61660f`).
- **AWT backend:** CACIO `caciocavallosilano` cacio17 CTC toolkit + a rebuilt `libawt_xawt` (reexports
  libawt_headless + no-op stubs for the absent headful `initIDs`). Activated on device with
  `UnsafePokeActivation` (no agent/JVMTI). §6.0 (CTC activation) and §6.1 (a real headful `java.awt.Canvas`
  realizes under CTC, `getGraphics()` → live `SunGraphics2D`) are device-GREEN, md5-gated.
- **Entry:** `DeviceHeadlessBoot.main` (`ios/dev-tools/awtsmoke/`) = `UnsafePokeActivation.activate()` →
  `RuneLiteHeadless.main(--safe-mode)` → a **read-only** capture/keep-alive poll loop. **No login or input
  automation exists in the device entry path.**
- **Process lifecycle:** the W3 shell (`ios/app/HelloShell/ViewController.m`) keeps the process alive
  (foreground `beginBackgroundTask` + an active `CADisplayLink`) and **displays captured frames in a
  display-only `UIImageView`** (`self.screen`, `contentMode = ScaleAspectFit`, source frame 765×503). iOS
  TCC needs `NSMicrophoneUsageDescription` (libjsound enumerates audio devices at RuneLite audio init) —
  now present.

## What is PROVEN (with cites) vs UNPROVEN
**Proven — desktop, full toolkit + CACIO CTC:**
- **Synthesized login click drives LOGIN→LOGGED_IN.** `runelite-client/.../headless/HeadlessLogin.java`
  (gated `-Drunelite.headless.autologin=true`): waits for `loginIndex == 10` (the authenticated "Welcome /
  Play Now" screen) after a warmup, then posts to `Toolkit.getDefaultToolkit().getSystemEventQueue()`
  (HeadlessLogin.java:213) a full sequence — `FocusEvent FOCUS_GAINED` → `MOUSE_ENTERED`/`MOUSE_MOVED` →
  `MOUSE_PRESSED`(BUTTON1)→`MOUSE_RELEASED`→`MOUSE_CLICKED` (lines 218–230) — at the Play Now button center
  (382,248), into `client.getCanvas()` (line 170). Retries ≤5 with cooldown; for a throwaway **F2P** account
  it `client.changeWorld()`s to an F2P world (EnumSet.noneOf(WorldType)) first to avoid the members bounce.
  This is **in-process event posting** — NOT a real desktop mouse — so the gamepack accepts EventQueue-sourced
  input regardless of origin.
- **JX_ launcher handoff auto-AUTHENTICATES but does NOT auto-LOGIN.** `tokens.json` →
  `JX_SESSION_ID`/`JX_CHARACTER_ID`/`JX_DISPLAY_NAME` (+access/refresh, may be empty) env vars
  (`ios/dev-tools/headless/run-headless-login.sh:33–44`). The gamepack mints its own play token and renders
  `loginIndex=10` (welcome), then **parks at `LOGIN_SCREEN`** — the synthesized click is what crosses to
  LOGGED_IN. tokens.json is minted by `ios/dev-tools/fetch-shield-tokens.sh` (interactive PKCE, gitignored,
  throwaway account).
- **CTC EventQueue dispatches on device** (inferred, strong): §6.1/§6.2 logs show `AWT-EventQueue-0`
  running (`HeadlessClientUI` "show done" is logged from it), so the CTC event-dispatch thread is live on
  device.
- **Device render/capture GREEN:** the gamepack itself runs on the device JVM and renders the login screen
  (incl. the EULA dialog) to `mainBufferProvider` → PNG.

**Unproven / not implemented (the milestone):**
- **The device shows the EULA Accept/Decline dialog at `LOGIN_SCREEN`**, NOT the desktop's `loginIndex=10`
  welcome screen. RuneLite does not persist EULA acceptance (the gamepack owns it; `.runelite` on device is
  fresh each install). So the device has an **extra gate the desktop proof did not hit** (or had already
  accepted), and `HeadlessLogin` as written waits for `loginIndex==10` and would never click the EULA.
- **No device input path of any kind.** `ViewController.m`'s `self.screen` is display-only — no gesture/touch
  handlers (only the two UIButtons have actions). `JVMLauncher.m` uses the JNI Invocation API one-way
  (native→`DeviceHeadlessBoot.main`); **there is no reverse JNI callback from native into Java**, no
  UIKit-touch→AWT bridge, no UIImageView→game coordinate mapping.
- **`DeviceHeadlessBoot` has zero automation** — pure capture/keep-alive poll.

## The two distinct sub-goals (the crux the review should separate)
- **(A) Reach LOGGED_IN / the game world on device — the cheap win.** Hypothesis: since the desktop proof
  used *in-process* `EventQueue` posting (not real input), the SAME in-process path should work on device
  with **no native touch bridge at all** — set `JX_*` env in `JVMLauncher.m`, dismiss the EULA with a
  synthesized Accept click, then run `HeadlessLogin`'s synthesized Play-Now click. If true, "real RuneLite
  *plays* on device" is bankable cheaply, entirely in Java, reusing proven code.
- **(B) Real interactive touch control — the product, bigger.** Map device touches on `self.screen` →
  game coordinates → AWT `MouseEvent`/`KeyEvent` into the Canvas, for genuine tap-to-walk/menus. Needs a
  reverse path from the native touch handler into the JVM (reverse JNI, or a native→Java event bridge),
  ScaleAspectFit↔765×503 coordinate mapping, focus handling, and correct threading onto the CTC EventQueue.

## Questions to adjudicate (verdict + reasoning each)
1. **Is sub-goal (A) really that cheap?** Can we reach LOGGED_IN on device purely in-process — `JX_*` env +
   a synthesized EULA-Accept click + the existing `HeadlessLogin` Play-Now click — with **no native input
   code**? What breaks on device that didn't on desktop? Candidates to weigh: (a) the CTC `getSystemEventQueue`
   on device dispatches our posted events to the Canvas peer the same way desktop does (vs the CTC peer
   needing a realized/focused window we don't have window-managed on device); (b) the EULA sub-state —
   what `loginIndex`/coords is the Accept button, and does accepting it advance to `loginIndex=10`; (c)
   threading — posting from the JVM worker thread vs the EventQueue dispatch thread; (d) `changeWorld` to an
   F2P world working headless on device; (e) does the gamepack require a *non-synthesized* focus/Activation
   the CTC peer can't supply off-window.
2. **EULA handling.** Cheapest robust approach: a second synthesized click at the Accept button (extend
   `HeadlessLogin` to handle the pre-`loginIndex==10` EULA state), or can we **pre-persist acceptance**
   (where does the gamepack store it — a cookie/jagex pref under `user.home`/.runelite? a setting we can seed
   before boot)? Is per-launch re-accept acceptable for now?
3. **Architecture for (B) — the real touch bridge.** Best pattern for native UIKit touch → AWT event on this
   stack: reverse JNI (cache `JavaVM*`, `AttachCurrentThread`, call a static Java `void onTouch(int type,
   float x, float y)` that builds + posts the AWT event on the CTC EventQueue) vs a native→Java pipe/queue
   the Java side polls. How to map `UIImageView` ScaleAspectFit coords → 765×503 game coords robustly. Which
   thread must post to the CTC EventQueue, and how to gain/keep Canvas focus. Anything CACIO-CTC-specific
   about input ingress (does CTC route EventQueue events to peers without a real window)?
4. **Sequencing.** Confirm: prove (A) first (banks "RuneLite plays on device", reuses proven code, isolates
   the device EventQueue-ingress question), THEN build (B). Or is there a reason (B)'s bridge is needed even
   to reach LOGGED_IN (e.g. the CTC peer rejects EventQueue posts off-window and only a native ingress works)?
5. **Auth logistics on device.** tokens.json is a gitignored secret minted interactively on the Mac. How
   should it reach the device for the `JX_*` env (bundle it into the app — but it's a secret and the build is
   shared via git; or push to the app container via devicectl at test time; or a Files.app drop)? What's the
   least-bad path that keeps the throwaway secret out of git and the shared artifact?
6. **What single device experiment best de-risks (A)** before writing much code (e.g. add `-Drunelite.headless.autologin`
   + the EULA-Accept click to `DeviceHeadlessBoot`, set `JX_*` env, F2P world, and watch the `gameState`
   transitions in the verbatim `jvm-stdio.log` — does it reach `LOGGING_IN`/`LOADING`/`LOGGED_IN`)?

## Constraints / trust rules
- **Device-only** for the real verdict; desktop is the develop-first mirror (W2 rule). The login click is
  proven on desktop — the open question is the **device** EventQueue→peer ingress and the EULA gate.
- **Trust only verbatim device artifacts** (`jvm-stdio.log`/`hs_err`/crash `.ips` pulled via `devicectl`) +
  bash-computed md5-gated verdicts. This project has a documented false-GREEN history from fabricated logs.
- **GPLv3 sources (Amethyst/Pojav/StikDebug) are patterns-only**, never vendored.
- **Throwaway Jagex account only**; tokens.json is a secret that must stay out of git and the shared build.
- Self-built client on live OSRS is **ban-risk / not shippable** — personal technical build (accepted).

## Pointers (for the reviewer, all in-repo)
- Proven login automation: `runelite-client/src/main/java/net/runelite/client/headless/HeadlessLogin.java`
  (click synth L213–230, canvas L170, world L178–201, gating L64–71/137–161).
- JX handoff: `ios/dev-tools/headless/run-headless-login.sh:33–44`; minting `ios/dev-tools/fetch-shield-tokens.sh`.
- Canvas seam: `runelite-api/.../Client.java:217` (`Canvas getCanvas()`); desktop realize
  `runelite-client/.../ui/HeadlessClientUI.java:98–122`.
- Device entry (no automation yet): `ios/dev-tools/awtsmoke/DeviceHeadlessBoot.java`.
- Device shell (display-only, no touch): `ios/app/HelloShell/ViewController.m:72–77,201,224–247`; one-way
  JNI `ios/app/HelloShell/JVMLauncher.m`.
- §6.2 close-out + the full device trail: `ios/app/PHASE3-6.2-DEVICE-CRASH.md` (UPDATES 15–19 + sustained run).
- Captured login frames (incl. the EULA dialog): `ios/app/evidence/login-frames-6.2-20260601/`.
