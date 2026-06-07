# A-1 — bridge/flag census

The first Android phase (see `android/PORT-PHASING.md` §3 Phase A-1). Proves, before any UI is built, that the
shared Java seam the A0–A8 mainline rides on is actually present and callable — so "almost no new *shared* Java"
is evidence, not assumption. **Scope: A-1a (below) proves *static resolution* only; A-1b (runtime gate-flip)
is still TODO — until it's GREEN this shows the methods *exist*, not that each `-D` gate activates its bridge.**

Two halves:

## A-1a — static method census (no device; DONE / GREEN)
`BridgeCensus.java` loads each `net.runelite.client.headless.*` bridge **without running its static
initializer** (so no CACIO/AWT activation is needed) and checks that every method in the expected contract
resolves in the embedded shaded jar, matched by name + ordered parameter simple-type names.

Run on desktop OpenJDK 21 against the Android-staged jar:

```
./run-census-desktop.sh                 # defaults to android/app/src/main/assets/jvm/<client-shaded>.jar
./run-census-desktop.sh /path/to.jar    # or check any staged copy
```

**GREEN baseline (jar `client-1.12.28-SNAPSHOT-shaded`):**

```
BRIDGE_CENSUS verdict=GREEN resolved=79 missing=0 total=79
census_md5=fd2c599160bfe1da1323709f02378286
```

The sentinel md5 is over the sorted `RESOLVED/MISSING` block, so a jar update that changes the bridge surface
flips the md5 — re-run and reconcile. (The staged Android jar is currently `1.12.28-SNAPSHOT` while the
source tree is `1.12.29-SNAPSHOT`; restaging to 1.12.29 and re-running this census is the first action item
in `BLUEPRINT.md` §G.) If the contract array in `BridgeCensus.java` falls behind the bridges,
the census under-reports; keep it in sync with `runelite-client/.../headless/*` (the source of truth).

## A-1b — runtime gate-flip census (emulator; TODO)
The static half proves the methods exist. It does **not** prove each `-D` gate flips the bridge from inert to
active at runtime (that needs the booted engine with injected singletons). All six gates are confirmed present
in source — `runelite.menupeek` / `huddata` / `mobilechrome` / `mobileui` / `hudveneer` / `headless.autologin`
(plus `runelite.hub`, `runelite.pluginhub.version`). A-1b boots `DeviceHeadlessBoot` on the `rl_arm64` emulator
under each gate and md5-gates an `isEnabled()`-true + first-`getSnapshot()`/`pollMobileUiEvents` readout from
`adb logcat` (normalized line, per §2.2). Tracked as the emulator follow-up to this phase.
