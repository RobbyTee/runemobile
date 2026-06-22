# Android GPU / 117HD — Execution Blueprint

Phased, dependency-ordered plan derived from `ANDROID-GPU-117HD-AUDIT.md` (read it for the full
evidence + `file:line` for every item; this doc is the *order of operations*). Honors the audit's §9
second-opinion adjustments.

## Ground rules (apply to every phase)
- **Never modify the plugin or `net.runelite.rlawt.AWTContext`.** Fixes land in `librlawt.cpp`,
  the LWJGL facade, `jvm_bootstrap.cpp`, `RLHDStarter.java`, `MainActivity.kt`, config, or jar-staging.
- **Measure or it didn't happen.** No tuning change ships without an A/B on the Phase-0 harness
  (frame-time percentiles, not a single smoothed fps). Quote before/after in the commit.
- **Device of record:** Pixel-8 / Mali-G715 (tile-based). Re-probe extensions before relying on them.
- **One phase at a time, each gated by its exit criteria.** Don't start a later phase until the
  earlier one's gate is green — later phases assume earlier wins are in place.

---

## Phase 0 — Instrumentation & safety net  *(prerequisite for everything)*
**Why first:** the audit can't currently tell a 2–3 fps delta from noise, and the live A/B knob is a
no-op. Tuning before this is guesswork.

| Task | File(s) | Effort |
|---|---|---|
| Fix the MCP A/B knob (`--ei` extras ignored) | `harness_server.py:401` ↔ `MainActivity.kt:294` | M |
| Frame-time min/avg/95p + thermal in the fps probe; `GL_TIME_ELAPSED` timer queries around shadow vs scene passes | `fps-measure.sh`, `Hooks.java:291`, `librlawt.cpp` | M |
| Align debug "new" preset `drawDistance` 25→30 so A/Bs aren't skewed | `MainActivity.kt:295` | S |
| One-time WARN when the slow `glReadPixels` present path is selected (silent-regression detector) | `librlawt.cpp:570/633` | S |
| Device-verify direct-present is live + upright (boot line + screenshot) | runtime check | S |
| **`eglSwapInterval` control + boot prop** (0=uncapped bench, 1=default, 2=30 cap) — *pulled up from Phase 3:* lock the interval so every later A/B is frame-rate/thermally bound, not Mali-boost noise | `librlawt.cpp:564`, `rcEnsureWindowSurface` | S |

**Exit gate:** a trustworthy A/B harness reports per-pass GPU time + frame-time percentiles + thermal
on device, **with a locked swap interval**; direct-EGL present confirmed live and right-side-up.

---

## Phase 1 — Free perf win + correctness must  *(config/native, low-risk, high-impact)*
**Why now:** biggest fps win is a reversible config change; the depth fix is a correctness landmine.
Depends on Phase 0 to quantify.

**Do the depth fix FIRST and re-baseline after it** (a correct projection is the foundation for every
later measurement; it's also the brick-risk item, so de-risk it before investing).

| Order | Task | File(s) | Effort |
|---|---|---|---|
| 1 | **Reverse-Z `glClipControl(ZERO_TO_ONE)` — RUNTIME-GATED + FAIL-SAFE** (§9 + review): enable in `createGLContext` → `glClearDepth(1)` + center-pixel readback → **auto-revert to standard depth if wrong**; **fail-safe:** if readback shows zero-variance/NaN for ~3 frames, force standard depth via `-Drunelite.routec.clipControl=0` (recovery flag) so a lying driver can't brick to black; fix the misleading "GEQUAL fallback" comment. **Establish the corrected-depth baseline before measuring anything below.** | `librlawt.cpp:420/664/createGLContext`, `:39` | S |
| 2 | **Disable POM / wind / character displacement** (mirror iOS) — **+8–15 fps** open world; measure on the corrected-depth baseline. *Verify POM-off doesn't regress visuals first.* | `RLHDStarter.java:111` | S |
| 3 | Present-path cleanup bundle: NEAREST 1:1 blit + cached surface dims; gate `gpuframedump` to `BuildConfig.DEBUG` | `librlawt.cpp:615/622`, `MainActivity.kt:2428/2440` | S |
| 4 | `eglMakeCurrent` guard — skip only when the **native surface pointer** is unchanged (never a bare flag) | `librlawt.cpp:613` | M |
| 5 | `glMapBufferRange` reuse contract + cache `rc_native_order` jclass/methodIDs (smoother pacing). **Gate:** verify explicit-flush semantics — if the map uses `GL_MAP_FLUSH_EXPLICIT_BIT`, confirm `glFlushMappedBufferRange` fires pre-draw (Mali reads garbage otherwise; check via readback/Android GPU Inspector, not desktop tools) | `librlawt.cpp:355/231` | S |

**Exit gate:** corrected-depth baseline captured first; **clipControl fail-safe proven** (forcing the
recovery flag restores a clean frame); measured open-world fps gain (Phase-0 harness); depth
banding/z-fighting gone with no clipped geometry on device; no frame-pacing regressions.

---

## Phase 2 — Unlock & ship quality  *(the seed-gap enabler + safe levers)*
**Why now:** every quality lever is dead until edits persist. Depends on Phase 1 (headroom) + Phase 0
(cost measurement).

| Task | File(s) | Effort |
|---|---|---|
| **Fix the seed-once gap** — split Android forces into *every-launch safety* vs *seeded-once tunables*; reuse the iOS `GFX_SEEDED_KEY` seed-and-persist path. **Enabler for all quality.** | `RLHDStarter.java:109–198` | M |
| **Verify shader-recompile-on-toggle** (§9 new): confirm 117HD doesn't recompile programs per setting-flip on the render thread; ensure any recompile is once-at-startup | `HdPlugin` (rlhd-src) | M |
| **Enable MSAA_4** (seeded) — biggest visible quality gap vs iOS (device `maxSamples=4`) | `RLHDStarter.java:119` | S |
| Expose seeded-tunable: `sceneScale` (A/B ship 90), `anisotropicFilteringLevel` 16, `dynamicLights` MANY | `RLHDStarter.java:127/131/114` | S |

**Exit gate:** panel edits survive restart; MSAA on with no per-flip recompile stutter; each lever's
fps cost quantified; quality A/B screenshots captured. *(2K shadows intentionally NOT here — see Phase 5.)*

---

## Phase 3 — Frame pacing & utilization reach
**Why now:** with the floor raised and measurable, control pacing and put the median user on the GPU.

> `eglSwapInterval` moved to Phase 0 (it's a measurement prerequisite).

| Task | File(s) | Effort |
|---|---|---|
| `getFramebuffer` dirty-flag (drop per-frame JNI probe) + in-place RBO resize | `librlawt.cpp:553/211` | M |
| Seq-gate the remaining overlay-snapshot republishes (replicate the menu-peek pattern) | `jvm_bootstrap.cpp:976/995/1013/1031/1050` | M |
| **Auto-seed GPU on capable hardware** behind a *solid* capability gate (reuse AG0 probe); CPU stays the safe fallback. **Strictly gated on Phase 1 (depth correct) + Phase 2 (config persists)** — never auto-promote a user to a renderer with broken depth or a preset that won't stick | `MainActivity.kt:794/797` | M |

**Exit gate:** sustained (post-thermal) fps measured under swap-interval caps; first-run on a capable
device lands on GPU without crashes; per-frame overhead trimmed.

---

## Phase 4 — Draw-call / bandwidth deep wins  *(CLOSED — probe complete, no L-effort warranted)*
**Why now:** these only pay off in draw/upload-bound scenes and depend on extensions not in AG0 recon.

> **CLOSED on Mali-G710** (probe commit de54c99622). The probe killed both levers as not-worth-it on
> this device: (1) `multi_draw_indirect` is **absent** (cap can't flip in any scene) *and* the draw
> profile shows it'd be ~12% in light scenes anyway; (2) `buffer_storage`/persistent-mapping is the only
> survivor but pays only when upload-bound — and every track measurement says the renderer is
> **fill/GPU-bound** (~25% GPU headroom capped, ~77 fps uncapped = the *display present ceiling*, ~120
> draw calls/frame = not draw-bound). On a unified-memory tiler `glBufferData` orphaning is already
> cheap, so persistent mapping is speculative, L-effort, and — being GPU-bound — wouldn't raise fps
> (best case is minor battery). Decision: **do not implement; track is at its perf ceiling.** The
> `[gpuprobe-caps]` + `[drawcount]` instruments ship, so this can be re-opened with data if a future
> device/scene proves draw- or upload-bound. The higher-value remaining item is the *product* call
> (ship uncapped 50→~74 fps vs keep the 50-cap), not engineering.

| Task | File(s) | Effort |
|---|---|---|
| **Probe `EXT_buffer_storage` + `EXT_multi_draw_indirect` on Mali-G715** at context creation (gate prerequisite) — **and** verify the mapped-buffer lifetime contract before building persistent mapping (the facade's mapped buffers are native/off-heap so GC won't move them, but confirm map/orphan/unmap lifetime holds across frames; if not, skip persistent mapping) | `librlawt.cpp:535/355` | S |
| Implement `rc_glMultiDrawArraysIndirect` + flip `OpenGL43`/`indirectDraw` — **only if the forwarder is real** | `librlawt.cpp:498`, `RLHDStarter` | L |
| Implement `rc_glBufferStorage` + persistent-mapped buffers — **only if `EXT_buffer_storage` present** | `librlawt.cpp:499/421` | L |

**Exit gate:** draw-bound (GE crowd) and upload-bound scenes show measured improvement; caps only
flipped where the extension is confirmed and the forwarder is implemented.

**PROBE DONE + device-verified on Mali-G710** (commit de54c99622): `rcProbePhase4Caps` at context
creation (extension-string AND non-null `eglGetProcAddress`, + persistent+coherent map lifetime
check) and a draw-call counter (`setprop debug.routec.drawcount 1` → `[drawcount]` per-frame profile).
Verdict:
- `multi_draw_indirect = 0` (string absent; the fn pointer resolves but the string check rejects it) →
  **lever unavailable on Mali-G710.** And the draw profile shows it'd be a tiny win anyway: open
  Lumbridge ≈ **120 GL draw calls/frame** (drawArrays 93, drawElements 10, mdaRanges 17, indirect 0),
  multi-draw-indirect would collapse only mdaRanges→mdaCalls = **~14/frame (~12%)**. Not draw-bound
  (77 fps at the present ceiling). → **drop the `rc_glMultiDrawArraysIndirect` + `indirectDraw` L-task.**
- `buffer_storage = 1` + `persistent_map_ok = 1` → the **only viable Phase 4 lever** here. Next step if
  pursued: measure upload-bound-ness (per-frame `glBufferData` re-upload cost in NPC-dense scenes)
  before implementing `rc_glBufferStorage` + persistent-mapped `GLBuffer`. Gated on that A/B.

> Caveat: the multi-draw drop is driven by `multi_draw_indirect=0` (extension **absent** → the cap
> cannot be flipped in *any* scene), NOT by the 12% figure. The ~120-call / ~14-saved numbers are
> **open-Lumbridge only** (a light scene); the `[drawcount]` counter ships, so re-run it in a GE crowd /
> PvM to confirm those scenes aren't draw-bound either before treating draw-call cost as closed.

---

## Phase 5 — Quality ceiling  *(deferred until the floor is solid)*
**Why last:** these are bandwidth-heavy on a tiler; only safe once fps is stable and measurable.

| Task | File(s) | Effort |
|---|---|---|
| **2K shadows** (deferred per §9) — A/B cost at the bounded `drawDistance=30` shadow footprint | `RLHDStarter.java:123` | S |
| Port `tiled_lighting_*.glsl` to the Android overlay, validate via `glsl_es_validate`, flip `tiledLighting=true` (1000-light ceiling, iOS parity) | overlay + `RLHDStarter.java:114` | L |
| `mediump` precision for trivial UI shaders — only with an `image_diff` A/B (banding risk) | `librlawt.cpp:149` | M |

**Exit gate:** iOS-parity lighting + shadow quality, ES-validated, with the Phase-1/2 fps floor intact.

---

## Dependency summary
```
Phase 0 (measure + vsync) ──> Phase 1 (depth FIRST, then perf) ──> Phase 2 (quality unlock)
                                          │                                │
                                          └──────────> Phase 3 (pacing + auto-GPU*)
                                                                  │
                                          Phase 4 (draw/bandwidth, probe + pinning gated)
                                                                  │
                                                        Phase 5 (quality ceiling)
```
\* auto-GPU is gated on Phase 1 (depth correct) **and** Phase 2 (config persists).
Phases 4 and 5 can interleave once 0–3 are green, but neither starts before the Phase-0 harness exists.

## Sequencing adjustments (second-opinion review)
- **`eglSwapInterval` → Phase 0** (was Phase 3): a locked swap interval is a prerequisite for any
  trustworthy A/B on a boost-happy Mali; measuring MSAA/POM without it yields noise.
- **Reverse-Z depth fix → first task of Phase 1 + fail-safe:** it's the foundation for all later
  measurement and the only brick-risk item — de-risk it before investing, with a force-revert flag.
- **mapBuffer task gets an explicit-flush gate;** **auto-GPU explicitly gated** on Phase 1 + 2;
  **Phase 4 adds a mapped-buffer lifetime check** before persistent mapping.
- **Rejected:** a full "quality before perf" reorder. POM-off (Phase 1) must precede quality so it
  creates the fps headroom *and* lets us re-sweep `sceneScale` on the lighter pipeline; the review's
  real worry (noisy quality numbers) is addressed by moving vsync to Phase 0, not by reordering.
