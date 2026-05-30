# awtsmoke — W1 Phase 3 (AWT-on-iOS) probes

The Phase-3 analogue of `dev-tools/jvmbench/`: small, probe-first experiments that retire the
`AUDIT-BRIEF-w1-phase3-awt.md` claims one at a time, cheapest first. `AwtActivation` is `javac --release 8`
(bundles into the device probe jar).

The brief's reviewed verdict: *the danger in Phase 3 is not Java2D pixels (the iOS rasterizer is proven by
`GlyphSmoke`); it is **activating the CACIO CTC toolkit + graphicsenv before AWT init** on device.* §6.0
(activation) comes first, and it is **desktop-pre-checkable** — which is what this dir does.

## §6.0 — activation gate — DESKTOP PRE-CHECK (2026-05-29). Status: works only via the agent so far.

`AwtActivation.java` asserts (no pixels drawn): non-headless, `Toolkit.getDefaultToolkit()` == `CTCToolkit`,
`GraphicsEnvironment.getLocalGraphicsEnvironment()` == `CTCGraphicsEnvironment`, screen device queryable.
Greppable verdict: `AWT-ACTIVATION = CTC ACTIVE` / `= FAILED`.

```sh
./run-activation-desktop.sh all     # OpenJDK 21 aarch64; needs ../cacio-smoke/lib jars
```

### Verbatim results — OpenJDK 21.0.5 aarch64 macOS (`run-activation-desktop.sh all`, exit 0)

| Mode | How | Toolkit | GraphicsEnvironment | Verdict |
|---|---|---|---|---|
| (a) classpath | `-cp <cacio>` + 2 props + full JPMS | `LWCToolkit` | `CGraphicsEnvironment` | **FAILED** |
| (b) bootclasspath | `-Xbootclasspath/a:<cacio>` + 2 props | `LWCToolkit` | `CGraphicsEnvironment` | **FAILED** |
| **(c) agent** | `CacioExtension.injectCTCGraphicsEnvironment()` + `-Djdk.attach.allowAttachSelf=true` + byte-buddy{,-agent} + JPMS | **`CTCToolkit`** | **`CTCGraphicsEnvironment`** | **CTC ACTIVE** |
| (d) field-poke | pure reflection (`Toolkit.toolkit`, `GraphicsEnvironment.localEnv`, …) | `CTCToolkit` | `ExceptionInInitializerError` (LocalGE) | **FAILED** |

Two findings, exactly as observed:

1. **Property/bootclasspath selection does NOTHING on JDK 21.** `-Dawt.toolkit` / `-Djava.awt.graphicsenv` are
   not honored — toolkit AND GE stay the platform default. (Confirms the FINDINGS "bare `-Dawt.toolkit`
   silently falls back" note; refutes the "two-prop + bootclasspath" idea.)
2. **The ByteBuddy agent fully activates CTC (toolkit + GE), and does so even though its JVMTI redefine
   THROWS.** Mode (c) prints `injectCTCGraphicsEnvironment() THREW: UnsupportedOperationException: class
   redefinition failed: attempted to change the schema` — and *still* ends `CTC ACTIVE` with both toolkit and
   GE swapped. So the activation is carried by `CacioExtension`'s static-field injection (its `<clinit>` /
   `ClassInjector$UsingUnsafe` into the bootstrap loader), with the GE-method redefine as a non-fatal extra.

**My naive field-poke (d) FAILED** because the GE singleton is not a `GraphicsEnvironment.localEnv` field — it
lives in a lazily-initialized holder class `java.awt.GraphicsEnvironment$LocalGE`. Poking `headless=false`
without correctly seeding that holder triggered `ExceptionInInitializerError` on `LocalGE`. So a *correct*
no-instrumentation field-poke is **not yet found** — `FieldPokeActivation.java` stands as the (negative)
record of why the obvious approach doesn't work.

### Device implication (honest, current)
The only desktop recipe that works is the **byte-buddy agent** (mode c). It needs the **attach API /
attach-self** and the byte-buddy `Instrumentation` install — capabilities a stripped mobile JRE may lack
(note the JVMTI *redefine* itself is non-essential, since it throws yet activation succeeds; what's load-bearing
is `Instrumentation` install + the unsafe bootstrap class injection). So the open device questions are:
1. Does the embedded `jre21` ship `jdk.attach` + allow `ByteBuddyAgent.install()` (attach-self)? **Untested.**
2. Can we instead find the **correct no-instrumentation field-poke** — seed `GraphicsEnvironment$LocalGE`'s
   actual holder field + `Toolkit.toolkit` + `SurfaceManagerFactory.instance` before AWT init? (Promising,
   not yet working — the GE holder is the missing piece.)
3. Or reproduce Amethyst's `CTCPreloadClassLoader` (GPLv3, patterns only) — possibly the no-instrumentation
   installer that sidesteps both.

Pick this up before any device build. **Do not assume the agent ports to iOS.**

### Process note (kept deliberately)
While doing this gate I wrote a confident verdict **before reading the test output four times** (all wrong:
"flags-only GREEN" → "agent GE-only FAILED" → "field-poke is the recipe" → reality: agent works, field-poke
fails). The fix that finally worked: capture output to a file, grep the verdict lines, and make the docs quote
that. Rule for this dir: **no verdict without the matching captured output line.**

## Files
- `AwtActivation.java` — the no-pixel assertion + greppable `AWT-ACTIVATION` verdict (release 8; bundles to device).
- `AwtActivationAgent.java` — mode (c): calls `CacioExtension.injectCTCGraphicsEnvironment()` (the working path).
- `FieldPokeActivation.java` — mode (d): the naive pure-reflection attempt that FAILS (GE-holder record).
- `run-activation-desktop.sh [a|b|c|d|all]` — the desktop harness.

## Next
- Settle the device activation question (the 3 options above) **before** building §6.0-on-device.
- Then §6.1 `AwtSmoke` (realize `Frame`+`Canvas`, `getGraphics()` non-null, draw + read CTC pixels, fonts;
  the fake-`libawt_xawt` test); §6.2 RuneLite buffer smoke; §6.3 input; §6.4 bench.
