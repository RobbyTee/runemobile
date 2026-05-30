# awtsmoke — W1 Phase 3 (AWT-on-iOS) probes

The Phase-3 analogue of `dev-tools/jvmbench/`: small, probe-first experiments that retire the
`AUDIT-BRIEF-w1-phase3-awt.md` claims one at a time, cheapest first. `AwtActivation` is `javac --release 8`
(bundles into the device probe jar).

The brief's reviewed verdict: *the danger in Phase 3 is not Java2D pixels (the iOS rasterizer is proven by
`GlyphSmoke`); it is **activating the CACIO CTC toolkit + graphicsenv before AWT init** on device.* §6.0
(activation) comes first, and it is **desktop-pre-checkable** — which is what this dir does.

## §6.0 — activation gate — DESKTOP PRE-CHECK. Status: SOLVED no-instrumentation (mode e), 2026-05-30.

`AwtActivation.java` asserts (no pixels drawn): non-headless, `Toolkit.getDefaultToolkit()` == `CTCToolkit`,
`GraphicsEnvironment.getLocalGraphicsEnvironment()` == `CTCGraphicsEnvironment`, screen device queryable.
Greppable verdict: `AWT-ACTIVATION = CTC ACTIVE` / `= FAILED`.

```sh
./run-activation-desktop.sh all     # OpenJDK 21 aarch64; needs ../cacio-smoke/lib jars
```

### Verbatim results — OpenJDK 21 aarch64 macOS (`run-activation-desktop.sh all`, exit 0)

| Mode | How | Toolkit | GraphicsEnvironment | Verdict |
|---|---|---|---|---|
| (a) classpath | `-cp <cacio>` + 2 props + full JPMS | `LWCToolkit` | `CGraphicsEnvironment` | **FAILED** |
| (b) bootclasspath | `-Xbootclasspath/a:<cacio>` + 2 props | `LWCToolkit` | `CGraphicsEnvironment` | **FAILED** |
| (c) agent | `CacioExtension.injectCTCGraphicsEnvironment()` + byte-buddy{,-agent} (instrumentation redefine) | `CTCToolkit` | `CTCGraphicsEnvironment` | **CTC ACTIVE** |
| (d) naive field-poke | pure reflection assuming a `GraphicsEnvironment.localEnv` field | `CTCToolkit` | `ExceptionInInitializerError` (LocalGE) | **FAILED** |
| **(e) Unsafe field-poke** | **no byte-buddy / no attach / no JVMTI** — seed SMF + headless + `Toolkit.toolkit`, then Unsafe-overwrite `LocalGE.INSTANCE` | **`CTCToolkit`** | **`CTCGraphicsEnvironment`** | **CTC ACTIVE** |

Findings, exactly as observed (each quoted from captured output, not memory):

1. **Property/bootclasspath selection does NOTHING on JDK 21.** `-Dawt.toolkit` / `-Djava.awt.graphicsenv` are
   not honored — toolkit AND GE stay the platform default. *Why:* the GE is built by the build-time-generated
   `sun.awt.PlatformGraphicsInfo.createGE()`, which hardcodes the platform GE and ignores the property.
2. **The agent's byte-buddy redefine SUCCEEDS the first time and IS load-bearing — corrected.** Earlier note
   ("the redefine throws yet activation succeeds, so field injection carries it") was a **MISREAD**.
   `StaticOnlyProbe` loads `CacioExtension` to run its `static{}` ONCE (no 2nd `inject()`): **no stack trace,
   `CTC ACTIVE`, both toolkit and GE swapped.** The `UnsupportedOperationException: change the schema` only
   appeared in mode (c) because `AwtActivationAgent.main` called `inject()` a **second** time, re-redefining an
   already-redefined method. So the GE swap fundamentally needs the redefine (instrumentation) — which is
   exactly what the device JRE can't do.
3. **The naive field-poke (d) FAILED** because there is no `GraphicsEnvironment.localEnv` field. The GE
   singleton lives in the lazy holder `java.awt.GraphicsEnvironment$LocalGE.INSTANCE`. `FieldPokeActivation.java`
   stands as the negative record.
4. **(e) is the device recipe: it activates CTC with ZERO instrumentation.** Sequence, run as the first Java
   step before any AWT call:
   - `sun.java2d.SurfaceManagerFactory.instance = new CTCSurfaceManagerFactory()` (reflection; non-final)
   - `GraphicsEnvironment.headless = .defaultHeadless = Boolean.FALSE`
   - `java.awt.Toolkit.toolkit = new CTCToolkit()` (plain non-final static field)
   - force `LocalGE.<clinit>`, then **Unsafe-overwrite the static-final `LocalGE.INSTANCE` = `CTCGraphicsEnvironment.getInstance()`**.
     `getLocalGraphicsEnvironment()` re-reads `INSTANCE` every call, so subsequent calls return the CTC GE.
   Result is repeatable; the queried screen reports CTC's `1024x768` virtual device (not a real display),
   confirming it's genuinely the CTC GE. Unsafe is reached purely via reflection, so the probe still compiles
   `javac --release 8` and bundles to the device jar.

### Device implication — agent OUT (offline), Unsafe poke is the path
The agent (mode c) needs **attach-self** (`ByteBuddyAgent.install()` → `VirtualMachine.attach`). The embedded
`jre21` cannot self-attach (offline check): `jdk.attach` is **not** in the JRE `release` `MODULES`, and there
is **no `libattach.dylib`** in the image (the Darwin native bridge `VirtualMachine.attach` needs). `java.instrument`
is present but only enables a launched `-javaagent`, not runtime self-attach. **So mode (e) is the device path.**

**The ONE remaining device unknown:** mode (e) forces `GraphicsEnvironment$LocalGE.<clinit>` once, which runs
the iOS `jre21`'s hardcoded `sun.awt.PlatformGraphicsInfo.createGE()` (building a throwaway platform GE) BEFORE
we clobber `INSTANCE`. On desktop that builds a `CGraphicsEnvironment`; on the iOS JRE it runs that build's
`createGE()` — must not crash/hang. That is the single short device confirm; everything else is standard
java.desktop 21, identical on device. (Do NOT re-probe attach-self — already answered NO offline.)

### Process note (kept deliberately)
While doing this gate I wrote a confident verdict **before reading the test output four times** (all wrong:
"flags-only GREEN" → "agent GE-only FAILED" → "field-poke is the recipe" → reality: agent works, field-poke
fails). The fix that finally worked: capture output to a file, grep the verdict lines, and make the docs quote
that. Rule for this dir: **no verdict without the matching captured output line.**

## Files
- `AwtActivation.java` — the no-pixel assertion + greppable `AWT-ACTIVATION` verdict (release 8; bundles to device).
- `UnsafePokeActivation.java` — **mode (e): the no-instrumentation device recipe** (release 8; bundles to device). THE winner.
- `AwtActivationAgent.java` — mode (c): calls `CacioExtension.injectCTCGraphicsEnvironment()` (works on desktop; needs attach — OUT for device).
- `FieldPokeActivation.java` — mode (d): the naive pure-reflection attempt that FAILS (GE-holder negative record).
- `StaticOnlyProbe.java` — proves the agent's redefine is load-bearing & succeeds first-time (corrects the old mode-c misread).
- `run-activation-desktop.sh [a|b|c|d|e|all]` — the desktop harness (cleans `out/` + fails-fast on compile errors).

## Next
- **§6.0 desktop = DONE** (mode e, `CTC ACTIVE`, no instrumentation). Remaining for §6.0: one short device run
  of `UnsafePokeActivation` on the iOS `jre21` to confirm `LocalGE.<clinit>` (the throwaway platform
  `createGE()`) survives there. Needs the device.
- Then §6.1 `AwtSmoke` (realize `Frame`+`Canvas`, `getGraphics()` non-null, draw + read CTC pixels, fonts;
  the fake-`libawt_xawt` test); §6.2 RuneLite buffer smoke; §6.3 input; §6.4 bench — these need the device.

## Process note — flapping caught (2026-05-30)
Mode (e) appeared to flap `FAILED`<->`CTC ACTIVE`. Root cause was NOT the recipe: the old harness compiled with
`2>/dev/null` and never cleaned `out/`, so a transient `--release 8` compile miss left a **stale `.class`** that
threw `NoSuchFieldException: INSTANCE` at runtime. Fixed by `rm -rf out` + fail-fast compiles. Lesson reinforced:
capture to a file, read the file (not the scrollback), and never trust a verdict over a stale build.
