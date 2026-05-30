# Audit brief — W1 Phase 3: AWT backend (Caciocavallo + fake-xawt) on device

> **For adversarial review before we build it** — same brief→review→build rhythm as
> `AUDIT-BRIEF-w1-jvm-shell.md` / `AUDIT-BRIEF-w1-txm-mirror.md`. Self-contained; a reviewer need not have the
> repo loaded. Companions: `REALJVM-DEV-PLAN.md` (Phase 3), `REALJVM-FEASIBILITY-SCOPE.md` §4 (why CACIO),
> `FINDINGS.md` ("Desktop CACIO overlay gate", "Gate 1c", "Platform findings").
>
> **What we want from this review:** pressure-test the locked approach, find the cheapest experiment that
> fails fast, and adjudicate Claims A–F (§5) with a verdict + reasoning each. Challenge "CACIO is the right
> call on this JRE" — don't assume it.
>
> **REVIEW INCORPORATED (2026-05-29).** Verdict: *CACIO + fake-xawt stays the right first path, but Claim A
> was mis-specified.* Corrections baked in below: **the target is Amethyst's Java-21 `cacio17`
> (`com.github.caciocavallosilano` `cacio-shared`/`cacio-tta`, source/target 17) — the SAME lineage as our
> desktop jars — NOT the old `PojavLauncherTeam/caciocavallo` 1.10 / Java-8 tree.** The danger is not Java2D
> pixels (Claim B is now **Supported** — CTC's screen buffer is a `BufferedImage(TYPE_INT_ARGB)`, routing
> through the exact stack `GlyphSmoke` proved); it is **activation/classloading before AWT init**
> (`CTCPreloadClassLoader` + `-Xbootclasspath/a` + the exact JPMS flag set + a fake `libawt_xawt`). Experiment
> order reordered (§6); "what breaks first" added (§6a).

---

## 1. TL;DR

Phase 1 is **done**: a JIT HotSpot (our patched `jre21`, StikDebug-attached) runs our Java on the iPhone 17
Pro Max (iOS 26.5, TXM), and `GlyphSmoke` proved the iOS **headless** Java2D + font rasterizer works on
device. W1 Phase 3 supplies the **one missing layer**: a **non-headless AWT toolkit** so the OSRS engine's
`java.awt.Canvas` (`Client.getCanvas()`) gets a realized peer + a drawable surface, with the rendered pixels
reachable as a buffer we own and synthetic input reaching AWT listeners — all with **no native windowing
backend** (iOS has no X11/Cocoa AWT peer).

**Locked approach:** **Amethyst's Java-21 `cacio17`** (`com.github.caciocavallosilano` `cacio-shared` +
`cacio-tta`, source/target 17 — the same artifact our desktop Gate 1a/1b/1c already ran) + the "fake-xawt"
shim, selected as a non-headless toolkit, reusing the iOS native 2D/font stack we already validated. **Top
risk (corrected):** not Java2D — it's **activating the CTC toolkit + graphicsenv before AWT init on device**
without the desktop's JUnit/byte-buddy `CacioExtension` (Amethyst uses `CTCPreloadClassLoader` +
`-Xbootclasspath/a` instead), plus satisfying the dynamic `libawt_xawt` lookup with a fake stub. **Scope of
this phase:** stand up the backend and prove it with a **synthetic AWT smoke** (`AwtSmoke`, the non-headless
analogue of `GlyphSmoke`), then pull the **real-RuneLite buffer smoke** forward as early as possible (the
reviewer's nudge — a synthetic `BufferProvider` is the weaker proxy) — short of the full W2 merge (Rung 3).

---

## 2. Where this sits (settled — don't relitigate)

| Rung / phase | Question | Status |
|---|---|---|
| Rung 0 | Real JVM fast enough? | ✅ 45–184× miniJVM |
| Rung 1 / W1 P1 | JIT on iOS 26.5 TXM? | ✅ device GREEN (`JitProbe`/`Bench`; mirror via patched `brk #0x69`) |
| — | iOS **headless** 2D + fonts on device? | ✅ `GlyphSmoke` (libawt_headless + libfontmanager + libfreetype) |
| W2 (desktop) | RuneLite headless + overlays + live login? | ✅ Gate 1c / 1c-ii GREEN on desktop (native **and** CACIO CTC, no window) |
| **W1 P3** | **Non-headless AWT/Canvas on device, buffer we own + input?** | **◀ this brief** |
| Rung 3 | Real headless RuneLite on the device JVM | ⬜ the merge (after P3) |

Settled and **out of scope to re-examine:** the JIT mechanism (StikDebug + `runelite-jit.js`, `brk #0x69`
mirror, `patch-jre-txm.sh`), VM embedding (`JNI_CreateJavaVM`, **not** `JLI_Launch` — see
`AUDIT-BRIEF-w1-txm-mirror.md`, retired), JRE provenance (`dev-tools/JRE21-INVENTORY.md`).

---

## 3. The seam — what the engine needs from AWT (grounded)

Survey of `runelite-client` + `runelite-api` (the post-W2 surface; Swing `ClientUI` + config panels are
W2-stripped). The render seam:

- `net.runelite.api.Client.getCanvas()` → **`java.awt.Canvas`** (`Client.java:217`); same on
  `GameEngine.getCanvas()` (`GameEngine.java:42`). The obfuscated gamepack owns this Canvas and drives the
  frame loop into it; `OverlayRenderer` draws overlays onto the **same** canvas graphics each frame.
- `Client.getBufferProvider()` → **`BufferProvider`** (`Client.java:1216`); `MainBufferProvider extends
  BufferProvider`. **This is our buffer-ownership handle** — the engine's pixels are read via
  `BufferProvider.getPixels()` (an `int[]`), which Gate 1c already captured **toolkit-independently** on
  desktop (native **and** CACIO).

Most-used `java.awt.*` (engine + overlays; counts across the source set):

| Class | Hits | Needs a peer/toolkit? |
|---|---:|---|
| `Color`/`Dimension`/`Rectangle`/`Point`/`Insets` | 288/189/67/39/11 | No — value types, free |
| `image.BufferedImage` | 140 | No — iOS natives (proven by `GlyphSmoke`) |
| `Graphics`/`Graphics2D`, `FontMetrics`, `Font`, `BasicStroke`, `Polygon`, `AffineTransform`, `AlphaComposite` | 22/26/14/15/28/10/6 | Offscreen: no. **On-canvas: yes** |
| `event.MouseEvent`/`KeyEvent` + listeners | 47/33 + adapters | **Yes** — event source + dispatch |
| `Canvas` | 5 | **Yes** — realized peer + drawable surface |
| `Toolkit` | 15 | clipboard/cursor/tray — mostly W2-stripped or stub-able |

**Notably absent from `runelite-client` main:** no `java.awt.Robot` (grep: none). `BufferStrategy` appears
**nowhere in our source** — if the engine page-flips, it does so inside the gamepack (Claim C).

---

## 4. The locked approach (and the evidence behind it)

We are **not** building AWT 2D — `Graphics2D`/`BufferedImage`/font rasterization/color management all have
working iOS natives (`GlyphSmoke`: AA text + shapes, `font=SansSerif.bold`, headless, on device). The
embedded `jre21` ships `libawt`, `libawt_headless`, `libjawt`, `libfontmanager` + `libfreetype`, `liblcms`,
`libmlib_image`, `libjavajpeg` **and** `lib/fontconfig.properties` + `lib/fonts/` (Lucida TTFs) — so logical
font names already resolve (that's what backed GlyphSmoke's `SansSerif`). What it does **not** ship is any
windowing backend: **no `libawt_xawt`, no `libawt_lwawt`**. We supply **only that missing layer** — a Canvas
peer + drawable surface + event source. That is CACIO's job.

- **CACIO** = pluggable pure-Java AWT peers rendering into a backend-provided surface. The artifact is
  **`com.github.caciocavallosilano` `cacio-shared` + `cacio-tta` (cacio17, source/target 17)** — the **same
  lineage we already run on desktop** (`dev-tools/cacio-smoke/lib/cacio-{tta,shared}-1.18.jar`, verified:
  `groupId com.github.caciocavallosilano`, contains the `com.github.caciocavallosilano.cacio.ctc.*` `CTC*`
  classes). Amethyst's Java-21 path uses this, **not** the old `PojavLauncherTeam/caciocavallo` 1.10 / Java-8
  tree (that targets Java 8, only enables `cacio-shared`+`cacio-androidnw`, and can't host a condy/JDK21
  gamepack). GPLv3 → **patterns/jars only**, license-review before copying source.
- **Activation differs desktop vs device.** Desktop installs CTC pre-AWT-init via the JUnit `CacioExtension`
  (byte-buddy self-attach → `-Djdk.attach.allowAttachSelf=true` + the JPMS set in `run-cacio.sh`). **On
  device Amethyst uses `CTCPreloadClassLoader` + `-Xbootclasspath/a`** to get CTC ahead of AWT without the
  test harness — that's the mechanism P3 must reproduce, and the §6.0 spike's real content.
- **"fake-xawt"** = a stub `libawt_xawt.dylib` that satisfies AWT's dynamic windowing-backend lookup with no
  X11 present (else `UnsatisfiedLinkError` / silent fallback to the default toolkit).
- **Why not hand-roll a `Toolkit`/peer set:** a full `sun.awt` peer set is a known dead-end (`FINDINGS.md`);
  CACIO already abstracts the surface into a small subclassable set. A native Quartz/UIKit AWT peer does not
  exist for iOS and we will not write one.

**Two halves already proven separately — Phase 3 is their intersection (untested):**
1. *The offscreen-realized-Canvas pattern* — Gate 1c's `HeadlessClientUI` realizes **one minimal AWT `Frame`
   hosting the gamepack `Component`** so the `Canvas` gets a peer + non-null `Graphics`; under **CACIO CTC the
   Frame realizes offscreen (no window)** — "exactly what the device path needs." This is the template P3
   reproduces on device.
2. *The iOS native rasterizer* — `GlyphSmoke`, on device, headless.
   P3 must show **(1) + (2) together**: a CACIO-realized Canvas whose `Graphics2D` drives the *iOS* natives.

---

## 5. Claims for adjudication — with the review verdict folded in (2026-05-29)

**Claim A — CACIO builds + loads + *activates* against `jre21` (21.0.8) on device, with a known flag set.**
**Verdict: PARTLY REFUTED AS ORIGINALLY WRITTEN → respecified; remains the dominant risk.** The original
brief named the wrong artifact (`PojavLauncherTeam/caciocavallo`, the Java-8 1.10 tree). **Correct target:
Amethyst's Java-21 `cacio17` = `com.github.caciocavallosilano` `cacio-shared`/`cacio-tta`, source/target 17**
— the same lineage as our desktop jars (verified locally). The real risk is **not** compilation but
**activation/classloading before AWT init**: on device Amethyst uses **`CTCPreloadClassLoader` +
`-Xbootclasspath/a`** (not the desktop JUnit `CacioExtension`), plus a long JPMS `--add-exports/--add-opens`
set (the one in `run-cacio.sh`) and a fake `libawt_xawt`. *Reviewer's nudge:* the §6.0 experiment must be
**"reproduce Amethyst's cacio17 activation path,"** not "build the Pojav fork."

**Claim B — CACIO's surface binds to the iOS native 2D/font path `GlyphSmoke` exercised (not a cacio-internal
rasterizer).** **Verdict: SUPPORTED.** CTC's screen buffer is a `BufferedImage(TYPE_INT_ARGB)` and it draws
via `screenBuffer.getGraphics()` then extracts the raster — so it routes through the **same Java2D/font stack
`GlyphSmoke` already proved on device**, not a separate renderer. (Desktop byte-identical output is consistent
with this.) Lower risk than the brief assumed.

**Claim C — Buffer ownership is via `BufferProvider.getPixels()`, independent of how the engine draws.**
**Verdict: STRONGLY SUPPORTED.** Desktop Gate 1c/1c-ii captured real RuneLite login + in-game overlay via
`getPixels()` under CACIO with `nullGraphics=0`, peer-independent. W3's blit source is the RuneLite `int[]`,
not the cacio surface.

**Claim D — Font provisioning needs nothing extra: bundled `fontconfig.properties` + Lucida back logical
names, RL's TTFs cover overlays.** **Verdict: SUPPORTED, but the flags are brittle.** The `jre21` bundles
`lib/fontconfig.properties` + `lib/fonts/Lucida*.ttf` and `GlyphSmoke` rendered `SansSerif.bold` on device.
*The smoke must exercise the brittle surface:* `X11FontManager`/`FreetypeFontScaler` (the non-mac iOS path),
logical fonts, RL `registerFont(runescape*.ttf)`, shadow/outline/`<col>` overlay ops, and the JPMS access
flags — i.e. the full `cacio-smoke` `OverlaySmoke` op-set, on the cacio (not headless) toolkit.

**Claim E — UIKit touch → AWT event reaches the engine Canvas listeners on device.** **Verdict: PLAUSIBLE —
LEAST PROVEN; broaden the test.** Direct in-process event posting (what Gate 1c-ii did on desktop) does **not**
prove *UIKit ingress*. The §6 input smoke must drive the real path: UIKit touch callback → post AWT events to
the canvas on the EDT, and cover `dispatchEvent` + `EventQueue.postEvent`, focus, mouse move/click/drag, wheel
(if used), and key events — with listener counters + focus assertions.

**Claim F — The non-headless toolkit + 765×503 buffer fit the free-cert budget.** **Verdict: LOW RISK.** A
765×503 ARGB buffer is ~1.5 MB. The real memory cost is loading **`java.desktop` + CACIO + RuneLite**, not the
pixel buffer — track that in the §6.4 bench, not the buffer size. We already run
`-XX:-UseCompressedClassPointers` / `+MirrorMappedCodeCache` / `ReservedCodeCacheSize=64m` +
`increased-memory-limit`.

---

## 6. Sub-steps — reordered per review (cheapest correct order; activation first)

| # | Step | Retires | Cost |
|---|---|---|---|
| **0** | **Activation gate.** **DESKTOP PRE-CHECK DONE (2026-05-29, `dev-tools/awtsmoke/`) — key negative result, see §6b.** Flags-only (property/bootclasspath) selection **FAILS** on JDK 21; CTC activates **only via a ByteBuddy instrumentation agent** (JVMTI class-redefinition), which runs without JUnit. **Device §6.0 is therefore not "thread some flags" — it's "can the iOS JRE run that agent (attach-self + JVMTI RedefineClasses)?"** If not, we need Amethyst's `CTCPreloadClassLoader`-style no-JVMTI path. **Device next:** probe `jdk.attach` + JVMTI-redefine on the embedded `jre21`; in parallel read what `CTCPreloadClassLoader` does. | **Claim A** — the gate | **Desktop done; device risk re-scoped** |
| 1 | **`AwtSmoke` (device):** realize `Frame`+`Canvas`, **assert `Canvas.getGraphics()` non-null**, draw marker pixels, read CTC screen pixels back (CRC+PPM), then the full font op-set (logical + `createFont` RL TTF + shadow/outline/`<col>`). | Claims B, D | Med |
| 2 | **RuneLite buffer smoke (device) — pulled FORWARD:** reuse the Gate-1c `HeadlessClientUI` idea on device as early as possible (real engine → real `BufferProvider.getPixels()`). The reviewer flags a synthetic `BufferProvider` as the **weaker** proxy — prefer the real one once §0–1 pass. | Claim C (real) | Med |
| 3 | **Input smoke (device):** **UIKit touch callback** posts AWT events to the canvas on the EDT; cover `dispatchEvent`/`EventQueue.postEvent`, focus, move/click/drag, wheel, keys; listener counters + focus checks. | Claim E | Med |
| 4 | **Sustained bench (~10 min):** draw + capture + input loop; log **GC + total footprint** (java.desktop+CACIO+RL load, not the buffer). | Claim F | Med |

Probes live in `dev-tools/awtsmoke/` (built `javac --release 8`, run via the HelloShell `JNI_CreateJavaVM`
path that already runs `probes.jar`). This mirrors Phase 1's `dev-tools/jvmbench/`. **The full W2 merge (Rung
3) is still out of scope** — but §6.2 deliberately pulls a *real-engine buffer* read forward (per the review)
rather than leaning on a synthetic `BufferProvider`.

### 6b. Activation mechanism — measured on desktop (2026-05-29). The hypothesis I tested was WRONG.
I expected a flags-only recipe to suffice (we control launch flags). **It does not.** Measured on OpenJDK
21.0.5 aarch64 with `dev-tools/awtsmoke/run-activation-desktop.sh`:

| Mode | Flags | Verdict |
|---|---|---|
| (a) classpath + 2 props + full JPMS set | `-cp <cacio>` … | **FAILED** — toolkit/GE stay native |
| (b) `-Xbootclasspath/a:<cacio>` + 2 props | … | **FAILED** — same |
| **(c) agent** | `CacioExtension.injectCTCGraphicsEnvironment()` + `-Djdk.attach.allowAttachSelf=true` + byte-buddy{,-agent} + JPMS | **`CTC ACTIVE`** |

**Property/bootclasspath selection is not honored on JDK 21** — `-Dawt.toolkit`/`-Djava.awt.graphicsenv` do
nothing; the toolkit stays the platform default. (This *confirms* the FINDINGS "bare `-Dawt.toolkit` silently
falls back" note.) The **only** thing that worked is the ByteBuddy agent: `injectCTCGraphicsEnvironment()` is
`public static` (no JUnit needed) and does `ByteBuddyAgent.install()` (attach-self) → **JVMTI redefine** of
`GraphicsEnvironment.getLocalGraphicsEnvironment()` → `ClassInjector$UsingUnsafe` into the bootstrap loader.

**This re-scopes the device §6.0 (and it's the honest, sharper risk):** success now depends on whether the
embedded `jre21` supports **(1) the attach API / attach-self** (`jdk.attach`; often stripped from mobile
JREs) and **(2) JVMTI `RedefineClasses`/`RetransformClasses`**. If either is absent, the desktop mechanism
does **not** port and we need a **no-JVMTI** installer — likely what Amethyst's `CTCPreloadClassLoader` is
(strong hypothesis: it exists *because* the agent path fails on iOS). Settle that before any device build.

**Scope honesty:** §6.0 proved **selection on desktop, via the agent**. It does NOT prove the iOS JRE can run
that agent (the new open question), nor peer realization / fake `libawt_xawt` (§6.1).

### 6a. What breaks first (review — order of likelihood)
Activation/classloading, by far. Concretely, watch for: `ClassNotFoundException` for `CTCPreloadClassLoader`;
`IllegalAccessError`/`InaccessibleObjectException` on `sun.*` (missing an `--add-exports/--add-opens`);
`UnsatisfiedLinkError` for `libawt_xawt` (fake stub missing/mis-signed); **silent fallback to the native
toolkit** (the desktop gotcha — bare `-Dawt.toolkit=…` doesn't install CTC); `HeadlessException`; or
`SurfaceManagerFactory already initialized` (CACIO installed too late, after AWT init). The §6.0 gate asserts
the toolkit/graphicsenv/headless state explicitly so a silent fallback fails loud.

---

## 7. Alternatives (challenge these)

| Option | Verdict |
|---|---|
| **Amethyst cacio17 + fake-xawt activation path** (locked, post-review) | The reviewer's recommendation: reproduce Amethyst's Java-21 `cacio17` + `CTCPreloadClassLoader`/`-Xbootclasspath/a` + fake-xawt — same jar lineage we run on desktop. Risk concentrated in §6.0 activation. |
| `PojavLauncherTeam/caciocavallo` 1.10 source tree | **Rejected** — Java-8 targets, only `cacio-shared`+`cacio-androidnw`; wrong baseline for a condy/JDK21 gamepack. (The brief's original mis-spec.) |
| Hand-rolled minimal `Toolkit`/peer set (only Canvas+Graphics+events) | **Fallback only** if the cacio17 activation can't be made clean/licensable. 2D/font natives already exist → scope = windowing only. Reviewer: this is the fallback, *not* the first move. |
| Stay `headless=true` + feed the engine a Canvas we fully control | The engine instantiates/uses its Canvas via the toolkit; we don't own its construction → likely infeasible without a non-headless peer. |

**Resolved by review:** do **not** bet W1P3 on the Pojav 1.10 tree; bet it on reproducing Amethyst's Java-21
cacio17 + fake-xawt activation. Hand-rolled peers stay the fallback.

---

## 8. Out of scope for P3 (named so reviewers don't flag them)

- Real RuneLite booting (the W2 merge / Rung 3) — P3 uses synthetic smokes.
- W3 JNI → `CAMetalLayer` blit and the real UIKit→AWT input mapping (Phase 4).
- App background/foreground, JIT re-attach across launch, focus lifecycle.
- The GPU plugin / LWJGL OpenGL path — separate track, not the base AWT 2D seam.

## 9. Artifacts this phase produces
- `dev-tools/awtsmoke/` — the smoke sources + a `PHASE3-AWT.md` run procedure (style of `jvmbench/PHASE1-PROBE.md`).
- The Pojav-cacio build recipe + the JDK-21 flag/patch set (from §6.0), under `dev-tools/`.
- Numbers + verdict appended to `REALJVM-DEV-PLAN.md`; dead-ends to `FINDINGS.md`.
