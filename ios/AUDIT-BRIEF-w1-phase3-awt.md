# Audit brief — W1 Phase 3: AWT backend (Caciocavallo + fake-xawt) on device

> **For adversarial review before we build it** — same brief→review→build rhythm as
> `AUDIT-BRIEF-w1-jvm-shell.md` / `AUDIT-BRIEF-w1-txm-mirror.md`. Self-contained; a reviewer need not have the
> repo loaded. Companions: `REALJVM-DEV-PLAN.md` (Phase 3), `REALJVM-FEASIBILITY-SCOPE.md` §4 (why CACIO),
> `FINDINGS.md` ("Desktop CACIO overlay gate", "Gate 1c", "Platform findings").
>
> **What we want from this review:** pressure-test the locked approach, find the cheapest experiment that
> fails fast, and adjudicate Claims A–F (§5) with a verdict + reasoning each. Challenge "CACIO is the right
> call on this JRE" — don't assume it.

---

## 1. TL;DR

Phase 1 is **done**: a JIT HotSpot (our patched `jre21`, StikDebug-attached) runs our Java on the iPhone 17
Pro Max (iOS 26.5, TXM), and `GlyphSmoke` proved the iOS **headless** Java2D + font rasterizer works on
device. W1 Phase 3 supplies the **one missing layer**: a **non-headless AWT toolkit** so the OSRS engine's
`java.awt.Canvas` (`Client.getCanvas()`) gets a realized peer + a drawable surface, with the rendered pixels
reachable as a buffer we own and synthetic input reaching AWT listeners — all with **no native windowing
backend** (iOS has no X11/Cocoa AWT peer).

**Locked approach:** the **PojavLauncherTeam/caciocavallo** fork (CACIO) + the "fake-xawt" shim, selected as
a non-headless toolkit, reusing the iOS native 2D/font stack we already validated. **Top risk:** CACIO
predates JPMS; whether this fork builds + loads against our **patched 21.0.8** on device — and binds its
offscreen surface to the *iOS* rasterizer — is unproven by us. **Scope of this phase:** stand up the backend
and prove it with a **synthetic AWT smoke** (`AwtSmoke`, the non-headless analogue of `GlyphSmoke`), the same
probe-first method Phase 1 used — *not* yet booting real RuneLite (that's the W2 merge, Rung 3).

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
working iOS natives (`GlyphSmoke`: AA text + shapes, `font=SansSerif.bold`, headless, on device). We supply
**only the missing windowing layer**: a Canvas peer + drawable surface + event source. That is CACIO's job.

- **CACIO** = pluggable pure-Java AWT peers rendering into a backend-provided surface, selected via
  `-Dawt.toolkit` / `-Djava.awt.graphicsenv`. The **iOS/arm64 backend is the PojavLauncherTeam/caciocavallo
  fork** (GPLv3 — **patterns only**, license-review before any copy), bundled by Amethyst for exactly this.
- **"fake-xawt"** = the shim that satisfies the dynamic `libawt_xawt` lookups with no X11 present.
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

## 5. Claims for adjudication (the meat — verdict + reasoning each)

**Claim A — The PojavLauncherTeam/caciocavallo fork builds + loads against our patched `jre21` (21.0.8) on
device, with a known flag set.**
*Our position: PLAUSIBLE but UNPROVEN — the dominant risk.* CACIO's active era is JDK 6–11; JPMS (9+)
encapsulates the `sun.awt`/`sun.java2d`/`sun.font` internals it subclasses, and several internal signatures
(`SunGraphicsEnvironment`, `SurfaceManagerFactory`, peer interfaces) drifted 9→21. Desktop used a *different*
artifact (`CaciocavalloSilano:cacio-tta` 1.18, which ran on 21 with `--add-exports/--add-opens` +
`-Djdk.attach.allowAttachSelf=true`); the iOS fork is the unknown. *Reviewer:* what JDK baseline does the
Pojav fork target, and where is its exact flag/patch set? Does it need `--patch-module java.desktop=…`?

**Claim B — CACIO's offscreen surface binds to the iOS native 2D/font path `GlyphSmoke` exercised (not a
cacio-internal rasterizer).** *Position: LIKELY but the load-bearing unknown.* On desktop, `BufferedImage`
`Graphics2D` (`SunGraphics2D`) + the font scaler (`CFontManager`) are **platform-chosen, independent of the
toolkit** — which is *why* the desktop CACIO test was byte-identical and could **not** validate the iOS
rasterizer. So whether a CACIO **Canvas** surface on device routes through the same `libawt`/`libfontmanager`
path is genuinely untested. *Reviewer:* does the Pojav cacio Canvas peer use a `SurfaceData` backed by the
platform `BufferedImage` rasterizer, or its own?

**Claim C — Buffer ownership is via `BufferProvider.getPixels()`, independent of how the engine draws
(BufferStrategy vs offscreen `Image`).** *Position: STRONG.* Gate 1c captured the engine's pixels via
`getPixels()` on desktop under both toolkits, so W3's blit source is the RuneLite `BufferProvider` `int[]`,
not the cacio surface — which de-risks the "does the engine page-flip?" question. *Reviewer:* any reason the
device cacio peer would leave `getPixels()` stale/empty where desktop didn't?

**Claim D — Font provisioning needs nothing beyond RL's bundled RuneScape TTFs + the iOS rasterizer.**
*Position: LIKELY OK — partially proven.* `GlyphSmoke` resolved logical **`SansSerif.bold`** on device with
no fontconfig bundled, so *some* physical font backs the logical names already. RL registers its own
`runescape*.ttf` via `Font.createFont`/`registerFont` for overlay text. *Open:* does `registerFont` work on
the **cacio** toolkit (vs the headless one), and does any RL logical-font draw hit a name `GlyphSmoke` didn't
exercise? *Reviewer:* is there a fontconfig/physical-font dependency we're not seeing?

**Claim E — UIKit touch → AWT `MouseEvent`/`KeyEvent` posted to the cacio `EventQueue` reaches the engine
Canvas listeners on device.** *Position: LIKELY — desktop-proven analogue.* Gate 1c-ii crossed
`LOGIN_SCREEN → LOGGED_IN` by posting a synthesized focus+mouse sequence into the gamepack Canvas via the
toolkit `EventQueue` under CACIO CTC. P3 device test: post a synthetic event, confirm a listener fires on the
**offscreen** cacio peer. *Reviewer:* focus/realization preconditions for dispatch on an offscreen peer?

**Claim F — The non-headless toolkit + buffer(s) at 765×503 fit the free-cert budget alongside the JIT code
cache.** *Position: LIKELY.* We already run with `-XX:-UseCompressedClassPointers` (free cert),
`-XX:+MirrorMappedCodeCache`, `-XX:ReservedCodeCacheSize=64m`, and the `increased-memory-limit` entitlement.
765×503×4B buffers are trivial. *Reviewer:* buffer count/precision (ARGB `int[]`) and heap retune for P3.

---

## 6. Sub-steps (fail-fast — cheapest experiments retire the biggest unknowns first)

| # | Step | Retires | Cost |
|---|---|---|---|
| **0** | **CACIO-load spike (device):** build the Pojav cacio fork against patched `jre21`; select it via `-Dawt.toolkit`/`graphicsenv`; `Toolkit.getDefaultToolkit()` returns it, `GraphicsEnvironment` non-headless, no crash. | **Claim A** — the gate | **Do first** |
| 1 | **`AwtSmoke` (device):** realize a `Canvas` on an offscreen cacio `Frame`; `Graphics2D` draws shapes + RL TTF text; read pixels back (CRC + PPM, like `GlyphSmoke`). | Claims B, D | Med |
| 2 | **Input smoke (device):** post a synthetic `MouseEvent`/`KeyEvent` to the cacio `EventQueue`; a listener on the Canvas fires. | Claim E | Low |
| 3 | **Buffer-ownership smoke:** drive the draw through a `BufferProvider`-shaped surface; confirm `getPixels()` returns the rendered `int[]`. | Claim C | Low |
| 4 | **Sustained bench at 765×503:** fill+blit loop; no GC/JIT cliff; capture numbers. | Claim F | Med |

Probes live in `dev-tools/awtsmoke/` (built `javac --release 8`, run via the HelloShell `JNI_CreateJavaVM`
path that already runs `probes.jar`). This mirrors Phase 1's `dev-tools/jvmbench/` exactly. **Real RuneLite is
not loaded in P3** — that's the W2 merge (Rung 3), gated on these smokes passing.

**Activation gotcha to carry over (FINDINGS):** bare `-Dawt.toolkit=…` **silently falls back** to the default
toolkit. On desktop, CACIO installed pre-AWT-init only via byte-buddy self-attach
(`-Djdk.attach.allowAttachSelf=true` + the `--add-exports/--add-opens` set). The device install mechanism is
part of the Claim-A spike.

---

## 7. Alternatives (challenge these)

| Option | Verdict |
|---|---|
| **Pojav cacio fork + fake-xawt** (locked) | Precedent on our exact JRE lineage; small subclass surface. Risk = Claim A/B on device. |
| Hand-rolled minimal `Toolkit`/peer set (only Canvas+Graphics+events) | Fallback if Claim A fails; the 2D/font natives already exist, so the scope is "windowing only." More code, full control, no JPMS fight against a 3rd-party fork. |
| `cacio-tta` (the desktop artifact) on device | Built for headless Swing-test; not the arm64/iOS backend — likely the wrong surface model. |
| Stay `headless=true` + feed the engine a Canvas we fully control | The engine instantiates/uses its Canvas via the toolkit; we don't own its construction → likely infeasible without a non-headless peer. |

**Reviewer:** if the Claim-A spike shows the fork needs heavy patching for 21.0.8, is the **hand-rolled
minimal peer set** the better bet given we need only Canvas + Graphics + events and the rasterizer is done?

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
