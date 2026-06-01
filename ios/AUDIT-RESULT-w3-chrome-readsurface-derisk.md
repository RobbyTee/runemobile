# Audit result — W3 hybrid-chrome de-risk: read surface + tab-switch primitive (2026-06-01)

First step of `ios/AUDIT-BRIEF-w3-hybrid-mobile-chrome.md`, settling the single thing the reviewer verdict said
to prove before any native UIKit chrome work. Self-contained. Desktop OpenJDK 21 + CACIO CTC, md5-gated.

## VERDICT — GREEN (`CHROME_HYBRID_GREEN`, md5 `d5e94d645321dd8840be2d1d618caae8`)

The cover-and-proxy hybrid is viable. Proven on desktop, read-only where it matters, regressing nothing:

1. **Read surface reachable.** A read-only per-frame snapshot (`MobileChromeBridge`, client thread, mutates no
   widget) resolves the entire chrome geometry the native side needs: the live tab-stone bounds, side panel,
   minimap, orbs, chat, the world-viewport rect, and the active-tab varc — every frame, under headless CACIO.
2. **Synth-click switches the active tab** — the reviewer's preferred proxy primitive. A synthesized left click
   on the live desktop tab stone (at its canvas bounds, the proven `HeadlessLogin` click shape) flips
   `VarClientID.TOPLEVEL_PANEL` to that tab. Confirmed on two unrelated tabs across two runs (STONE3→3, STONE10→10).
3. **`setVarcIntValue(TOPLEVEL_PANEL, n)` moves the varc and it sticks** for the observe window (no engine
   revert). See the caveat below — this is NOT yet a green light to use it as the switch primitive.
4. **Snap leak fixed** (reviewer sequence #1): viewport-gated snap in `JavaBridge` (below).

### Evidence (verbatim, `out-login-unsafepoke/run.log`, CACIO CTC autologin to LOGGED_IN)
```
[chrome-probe] baseline frame=3 top=164 resized=true baselineTab=3 tClick=10 (center 750,13) tVarc=11
[chrome-probe] click STONE10=10747938 -> activeTab=10 reached=true
[chrome-probe] setVarc TOPLEVEL_PANEL=11 -> immediate=true sticks=true (now=11)
[chrome-probe] CHROME_SWITCH result=DONE baselineTab=3 tClick=10 clickReached=true tVarc=11 varcImmediate=true varcSticks=true finalTab=11
[chrome] CHROME_READ rootKind=PRE_EOC topLevel=164 resized=true activeTab=11 tabsTotal=14 tabsPresent=3 tabsHidden=11 tabsAbsent=0 tabsZeroSize=0 sidePanel=1 minimap=1 orbs=1 chat=1 viewport=1
```

### Reading `tabsPresent=3 tabsHidden=11 tabsAbsent=0 tabsZeroSize=0`
The throwaway account is on **Tutorial Island**, where tabs unlock progressively. The census proves this is
account state, not a read bug:
- `tabsAbsent=0` — all 14 stone widgets exist (`getWidget` non-null).
- `tabsZeroSize=0` — **every stone that is shown resolved to real bounds.** This is the decisive gate: zero
  "visible but unread" stones, so the read code is correct regardless of how many tabs are unlocked.
- `tabsHidden=11` — the locked tutorial tabs (engine `isHidden`). Legitimate, not a failure.
- (The first run, mid-different-tutorial-step, read `tabsPresent=7`; same `absent=0/zeroSize=0`.) The verdict
  criterion is `tabsZeroSize==0 && containers all read`, which is account-independent.

### The varc-set caveat (honest scope)
The probe proved `setVarcIntValue` flips `TOPLEVEL_PANEL` and the value sticks for 25 frames. It did **not**
prove the side-panel content actually rebuilds via the same listener/script paths a real click fires — exactly
the reviewer's worry. So: **use synth-click as the switching primitive** (a real click definitionally fires
everything), and **`TOPLEVEL_PANEL` as the read source of truth for the active tab** (highlight the native tab
from it). `setVarcIntValue` stays a possible shortcut whose rebuild-completeness is unverified.

## What this confirms from the verdict (against the actual code)
- **Overlay anchoring depends on desktop widgets** — `OverlayRenderer.getViewportLayer()` (`:776`) reads the
  desktop root's `HUD_CONTAINER_FRONT`, and `positionSnapcorners()` (`:792`) anchors every overlay snap-corner
  off that widget + the chatbox. So leading with cover/proxy (read-only) and deferring widget mutation is right.
- **The event-driven relayout hook is proven in-repo** — `MinimapPlugin.onScriptPostFired` (`:151`) reacts to
  `TOPLEVEL_REDRAW`/`TOPLEVEL_RESIZE_CUSTOMISE` then hides widgets. That is the hook to use for any later
  hide/reposition (step 7), not per-frame thrash.

## The snap fix (reviewer sequence #1)
`JavaBridge.onClick` ran `resolveSnap` for every in-game left tap with no viewport check — a near-edge tap on
the minimap/tabs/chat/inventory (all outside the 3D viewport) could drag a click onto a world entity. Now snap
resolves only when the tap is inside the world-viewport rect (`getViewportXOffset/YOffset/Width/Height`); the
literal click still routes outside it (minimap tap-to-walk, inventory taps unaffected — we never swallow it).
Behind `-Drunelite.touch.snapViewportGate` (default true; false = legacy snap-everywhere).

## Artifacts
- Read surface: `runelite-client/.../headless/MobileChromeBridge.java` (gated `-Drunelite.mobilechrome=true`,
  read-only; per-frame JSON snapshot for the native chrome + one-shot `CHROME_READ` census line).
- Switch experiment: `runelite-client/.../headless/MobileChromeProbe.java` (gated
  `-Drunelite.mobilechrome.probe=true`, **mutates — desktop-only, never on the device launch path**; one-shot).
- Snap fix: `runelite-client/.../headless/JavaBridge.java` (`inWorldViewport` + `SNAP_VIEWPORT_GATE`).
- Wiring: `runelite-client/.../callback/Hooks.java` (`draw`).
- Harness: `ios/dev-tools/headless/run-chrome-probe.sh` (arms both gates over the UnsafePoke autologin path;
  recomputes the verdict in bash + md5-gates; verbatim log `out-login-unsafepoke/run.log`, gitignored output).

## Next (the build, now de-risked)
1. Native UIKit opaque tab rails drawn over the desktop tabs (cover, no hide) reading bounds from the snapshot.
2. Proxy tab taps -> synth-click the live stone bounds; highlight from `TOPLEVEL_PANEL`.
3. Native chat affordance over the real chatbox (snapshot already carries chat bounds).
4. Minimap/orb veneers only after proxy taps + state reads are solid on device.
5. Hide/reposition LAST, one widget family at a time, via the `ScriptPostFired` hook + tick verification.
Device confirm the read+switch path on the iPhone before drawing native rails (one launch).
