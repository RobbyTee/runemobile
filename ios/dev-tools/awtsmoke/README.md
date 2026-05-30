# awtsmoke — W1 Phase 3 (AWT-on-iOS) probes

The Phase-3 analogue of `dev-tools/jvmbench/`: small, probe-first experiments that retire the
`AUDIT-BRIEF-w1-phase3-awt.md` claims one at a time, cheapest first. `AwtActivation` is `javac --release 8`
(bundles into the device probe jar). The agent harness needs cacio + byte-buddy on the classpath.

The brief's reviewed verdict: *the danger in Phase 3 is not Java2D pixels (the iOS rasterizer is proven by
`GlyphSmoke`); it is **activating the CACIO CTC toolkit + graphicsenv before AWT init** on device.* §6.0
(activation) comes first, and it is **desktop-pre-checkable** — which is what this dir does.

## §6.0 — activation gate — DESKTOP PRE-CHECK done (2026-05-29). Key negative result.

`AwtActivation.java` asserts (no pixels): non-headless, `Toolkit.getDefaultToolkit()` == `CTCToolkit`,
`GraphicsEnvironment.getLocalGraphicsEnvironment()` == `CTCGraphicsEnvironment`, screen device queryable.
Greppable verdict: `AWT-ACTIVATION = CTC ACTIVE` / `= FAILED`.

```sh
./run-activation-desktop.sh all     # OpenJDK 21 aarch64; needs ../cacio-smoke/lib jars
```

### What works (and what does NOT) — measured on OpenJDK 21.0.5 aarch64 macOS

| Mode | Flags | Toolkit | GraphicsEnvironment | Verdict |
|---|---|---|---|---|
| (a) classpath | `-cp <cacio>` + 2 props + full JPMS | `LWCToolkit` (native) | `CGraphicsEnvironment` (native) | **FAILED** |
| (b) bootclasspath | `-Xbootclasspath/a:<cacio>` + 2 props | native | native | **FAILED** |
| (c) agent | `CacioExtension.injectCTCGraphicsEnvironment()` + `-Djdk.attach.allowAttachSelf=true` + byte-buddy{,-agent} + JPMS | `LWCToolkit` (native) | **`CTCGraphicsEnvironment`** ✅ | **FAILED** (GE only; toolkit not swapped) |

**No standalone mechanism reached full CTC activation.** Two findings:

1. **Property/bootclasspath selection does NOTHING on JDK 21.** `-Dawt.toolkit` / `-Djava.awt.graphicsenv` are
   not honored — toolkit AND GE stay the platform default, with or without `-Xbootclasspath/a` or JPMS exports.
   (Confirms the FINDINGS "bare `-Dawt.toolkit` silently falls back" note; refutes my earlier guess that a
   two-prop + bootclasspath recipe fixes it.)
2. **The ByteBuddy agent swaps the GE but NOT the Toolkit.** `CacioExtension.injectCTCGraphicsEnvironment()` is
   `public static` (runs without JUnit) and — decompiled (`javap -c`) — does `ByteBuddyAgent.install()`
   (attach-self → `Instrumentation`) → **JVMTI `redefine`** of `GraphicsEnvironment.getLocalGraphicsEnvironment()`
   → `ClassInjector$UsingUnsafe` into the bootstrap loader. Observed result: `GE = CTCGraphicsEnvironment`, but
   `Toolkit.getDefaultToolkit()` stays `LWCToolkit` → our full-activation gate still says FAILED.

**Full toolkit+GE activation was only ever observed under the `@CacioTest` JUnit harness** (the prior Gate
1b/1c runs: `toolkit=CTCToolkit` + `graphicsenv=CTCGraphicsEnvironment`). Reducing that to a standalone,
no-JUnit recipe is unfinished — but it doesn't change the device conclusion below, because the mechanism is
JVMTI class-redefinition / instrumentation-attach either way.

### Why this MATTERS for the device (the real, now-sharper §6.0 risk)
The device §6.0 does not hinge on "thread some flags through the launcher" — flags don't work at all. It
hinges on whether the **iOS JRE supports**:
1. the **attach API / attach-self** (`ByteBuddyAgent.install()` needs an attach provider — stripped mobile
   JREs frequently omit `jdk.attach`), and
2. **JVMTI `RedefineClasses`/`RetransformClasses`** (class redefinition may be disabled in the mobile build).

Both are needed even for the *partial* (GE-only) activation observed in mode (c). If either is missing on the
device JRE, the desktop mechanism does **not** port and we need a **no-JVMTI** activation. **Strong
hypothesis:** Amethyst's `CTCPreloadClassLoader` exists *precisely because* the agent path doesn't work on
iOS — i.e. it wires CTC via a custom classloader / boot path without redefinition. **Investigate that next,
before any device build** (and it likely also solves the toolkit-not-swapped gap that mode (c) left open).

### Scope / honesty boundary
This proves **selection on desktop macOS JDK only**, via the agent. It does NOT prove (a) the iOS JRE can run
that agent (the open question above), nor (b) peer realization / the fake `libawt_xawt` question (§6.1).

## Open questions to settle before §6.0-on-device
1. Does the embedded `jre21` ship `jdk.attach` + allow attach-self? (`java -XX:+PrintFlagsFinal` / check
   `lib/` for the attach provider; try `ByteBuddyAgent.install()` on device.)
2. Does it allow JVMTI `RedefineClasses`? (capabilities probe.)
3. What does Amethyst's `CTCPreloadClassLoader` actually do — is it a no-JVMTI CTC installer we can reproduce
   clean-room? (read Amethyst, GPLv3, patterns only.)

## Planned next probes (per brief §6)
- `AwtSmoke` (§6.1): once activation is solved on device — realize `Frame`+`Canvas`, assert
  `Canvas.getGraphics()` non-null, draw markers + RL TTF text, read CTC screen pixels back (CRC+PPM).
  Retires Claims B, D + the fake-`libawt_xawt` question.
- RuneLite buffer smoke (§6.2): the Gate-1c idea on device (real engine → `BufferProvider.getPixels()`).
- Input smoke (§6.3); sustained bench (§6.4).
