# Audit result — the Jagex-Mobile layout (ToplevelOsm / interface 601): flip vs re-create (2026-06-01)

Spike outcome for `ios/AUDIT-BRIEF-mobile-layout-toplevel-osm.md`. Self-contained.

## VERDICT

- **Path 1 (adopt `ToplevelOsm` / build interface group 601 as the root toplevel): RED.**
- **Recommended path: the HYBRID** — keep the desktop resizable root (`ToplevelOsrsStretch`/`ToplevelPreEoc`)
  alive so unmodified plugins keep working, and build native Jagex-Mobile-style chrome over it (edge tab
  columns / orbs / mobile chat) proxied through `MobileUiBridge`. Use group 601's component layout in the
  cache as a free, pixel-accurate **spec** for the native chrome — not as a live toplevel.

Two independent reasons, either of which is sufficient:

### 1. The build trigger is NOT reachable on the desktop gamepack (Q1) — device-pointless, settled on desktop
The only desktop-reachable lever is the toplevel-build script `TOPLEVEL_REDRAW` (group 907). Forcing it toward
601 does **not** build 601 — script 907 rebuilds the **engine-chosen** toplevel and ignores the group argument.

**Evidence (md5-gated, verbatim from `out-login-unsafepoke/run.log`, desktop OpenJDK 21 + CACIO CTC, autologin
to LOGGED_IN):**
```
[osm-probe] frame=1 topLevel=-1  osmComponents=0/6 phase=WAIT_BASELINE
[osm-probe] frame=2 topLevel=164 osmComponents=0/6 phase=WAIT_BASELINE
[osm-probe] baseline established frame=2 topLevel=164 osmComponents=0/6 -> forcing 601
[osm-probe] OSM_PROBE topLevelBefore=164 topLevelAfter=164 osmComponentsBefore=0/6 osmComponentsAfter=0/6 RESULT=NOBUILD
MEGAVERDICT=OSM_NOBUILD_ON_DESKTOP (topLevelAfter=164 osmComponentsAfter=0/6) -> trigger mobile-binary-gated; Path 1 RED
```
- `topLevel=164` = `ToplevelPreEoc` (modern resizable) — a **valid desktop baseline** (the first probe attempt
  read `-1` because the engine had not yet built its toplevel on the very first LOGGED_IN frame; the multi-frame
  probe waits for `getTopLevelInterfaceId() > 0` before forcing, so the test is clean).
- `runScript(907, 601, 1)` and `(907, 601, 0)` **neither threw nor had any effect**: over a 40-frame observe
  window the toplevel stayed 164 and **0/6** of group 601's representative components
  (`CONTROL/VIEWPORT/SIDE_RIGHT_TABS/CHAT_CONTAINER/MAP_CONTAINER/ORBS`) instantiated.

The desktop client's only toplevel selection is `OverlayRenderer.getViewportLayer()`:
`isResized() ? (RESIZABLE_STONE_ARRANGEMENT==1 ? ToplevelPreEoc(164) : ToplevelOsrsStretch(161)) : Toplevel(548)`.
There is **no code path that selects 601**, no `clientType`/`platformType` getter or setter in the API, and no
varbit/varcint that flips it. 601's selection is gated on a native `clienttype` the desktop gamepack reports as
desktop — confirmed mobile-binary-gated. A device run cannot change this (the gate is in the gamepack, not the
JVM/JIT/CACIO layer), so **no device launch was spent** — desktop settled it.

(The probe cannot distinguish "601 absent from the desktop cache" from "601 present but the build trigger is
gated." Both yield NOBUILD on desktop, so the distinction does not change the verdict.)

### 2. Even if it built, 601 breaks the "unmodified RuneLite plugins" guarantee (Q3) — the deciding tension
Switching the root to 601 changes every UI widget ID. Under 601, `getWidget(<desktop toplevel id>)` returns null,
so:
- **Core overlay anchoring breaks** (these are our fork, but load-bearing for *all* plugin overlays):
  - `OverlayRenderer.java:776` `getViewportLayer()` returns only desktop roots → **null under 601 → every
    screen-anchored plugin overlay loses its anchor.**
  - `WidgetOverlay.java:43` registers 11 overlays bound to desktop `CHAT_CONTAINER`/`SIDE_MENU`/`MAP_CONTAINER`/
    `SIDE_*`/`MULTIWAY_ICON` → all null under 601.
  - `ClientUI.java:1035` logout button (`ToplevelPreEoc.STONE10`).
- **~9 plugins reference desktop toplevel IDs directly** (no shim short of forking *them*): minimap
  (`MAP_MINIMAP`/`ORBS`), fps (`ICON10`), spellbook (`STONE6`), prayer-reorder (`STONE5`), itemstats (`SIDE3`),
  statusbars (`SIDE_CONTAINER`/`SIDE_PANELS`), worldhopper-ping (`ICON10`), interfacestyles ×2 (150+ refs).
- **Likely survive** (separate interface groups, not toplevel children at the API level): Chatbox plugins
  (group `0xa2` — chathistory, keyremapping, GE, reportbutton, screenshot) and Orbs plugins (`Orbs`/`OrbsNomap`
  — prayer, runenergy, poison, regenmeter, wiki). Untested, since 601 never built.

A 601 shim would mean forking `OverlayRenderer` + `WidgetOverlay` **and** patching the hardcoded IDs in ~9
plugins — i.e. forking the plugin ecosystem, which is precisely the guarantee this project exists to keep.

## Answers to the brief's questions
1. **Reachable?** No — mobile-binary/clienttype-gated; the one reachable lever (907) ignores the 601 arg. RED.
2. **Does 601 function?** Unanswerable — it never built. Moot given (1) and (3).
3. **Breaks unmodified plugins?** Yes, structurally (core anchoring + ~9 plugins). THE deciding factor. RED.
4. **Stack interaction.** Blit transparent; `HeadlessResize` sizes the desktop toplevel fine (164 rendered
   resizable-filled). One real pre-existing bug surfaced regardless of this verdict: the touch **snap** in
   `JavaBridge.onClick`/`resolveSnap` fires for *every* in-game left tap with **no world-viewport exclusion** —
   under any edge-tab chrome (601 or native) a tab tap could snap to a world entity. Fix when building the
   native chrome: gate snap on the world-viewport bounds. `MobileUiBridge.tick` already prefers the mobile chat
   hit-region (`Chatbox.INPUT_CLICKAREA`), so it is partly mobile-ready already.
5. **Login/EULA.** Unaffected — autologin clicks pre-toplevel login coords, and we never set a global mobile
   clienttype (we can't, and shouldn't pre-login).
6. **Reversibility.** The whole spike was gated `-Drunelite.mobileui.toplevel=osm` (default `desktop` = inert).
   The device launch path never carries the flag, so device builds stay desktop. Fully A/B-able.
7. **Sequencing.** The cheapest-decisive thing was settled first (does 601 build) and came back NOBUILD, so no
   native work was wasted and no device launch was spent. Next UI work proceeds on the hybrid plan: native
   Jagex-Mobile chrome over the desktop resizable root + the native plugin-config panel (reading
   `MobileUiBridge.listPlugins()`/`getPluginConfig()` over JNI), with 601's cache layout as the visual spec.

## Artifacts
- Probe: `runelite-client/src/main/java/net/runelite/client/headless/MobileToplevelProbe.java` (gated,
  default-inert; one-shot multi-frame state machine; wired in `Hooks.draw`).
- Harness: `ios/dev-tools/headless/run-osm-probe.sh` (arms the gate via `EXTRA_JVM_ARGS`, md5-gates the verdict);
  `run-headless-login-unsafepoke.sh` gained an `EXTRA_JVM_ARGS` passthrough.
- Verbatim run log: `ios/dev-tools/headless/out-login-unsafepoke/run.log` (gitignored output dir).
