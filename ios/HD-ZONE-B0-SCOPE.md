# iOS Zone B0 — scope (117HD ZoneRenderer bring-up on native EAGL ES 3.0)

> Branch `gpu`. Concrete, file-level scope for **Phase B0** of `HD-PORT-BLUEPRINT.md` §6 — getting RLHD's
> ZoneRenderer to draw the scene (even flat-shaded) on the device. Written **after** the 2026-06-09 device A/B
> locked **EAGL + `gl_FragDepth`** as the iOS renderer (not ANGLE — see `dev-tools/gpu-routec/DEVICE-KIT.md`).
> The blueprint's §5/§6 assume MetalANGLE (Path C); **§0 below reconciles that to EAGL.**

## 0. EAGL reconciliation — what the backend decision changes vs the blueprint

The blueprint's blocker list (§5) and phasing (§6) were drafted for ANGLE. With EAGL locked:

| Blueprint assumption (ANGLE) | EAGL reality (now) | Effect on B0 |
|---|---|---|
| Depth precision via ANGLE `glClipControlEXT` reverse-Z | **`gl_FragDepth = clip.z/clip.w`** (already wired + hardwired ON in the base path, `RouteCBridge.depthFixActive`) | **RLHD's `scene_vert`/`scene_frag` need the same gl_FragDepth depth-write** the base plugin got — carry `clip.z` as a `flat` varying, write `gl_FragDepth`. This is net-new shader surgery in B0, not a blueprint line item. |
| MSAA via ANGLE `GL_MAX_SAMPLES=8` | EAGL ES3 core `glRenderbufferStorageMultisample` (Apple GPU = 8x) | Cosmetic; **defer to B7**. Not a B0 gate. |
| Blocker #4 `RGBA16UI` 2D-array color attach — "probe on ANGLE-Metal" | Probe on **EAGL** | Tiled lighting is **B5 (last)** anyway — defer the probe with it. |
| Blocker #3 `glFramebufferTexture` layered attach | EAGL has `glFramebufferTextureLayer` (ES3 core) | Same fix as blueprint; only the tiled-lighting array FBO needs it → **B5**, not B0. |
| Facade `glClipControl` no-op on EAGL | Correct + intended | We do **not** use clip_control; depth is gl_FragDepth. The no-op shim stays. No wedge bug (that was ANGLE-only). |
| Blocker #5 `glMultiDrawArrays` covered by Route C loop shim | Same shim works on EAGL | Verify it covers RLHD's call sites (`Zone.java:831`, `DynamicModelVAO.java:292`) during B0. |

**Net:** EAGL *simplifies* B0 (no ANGLE window-surface/wedge work, no vendored ANGLE frameworks), at the cost of one
extra known task — applying the gl_FragDepth depth-write to the RLHD scene/shadow shaders. Everything ES-3.0-core in
the blueprint's "not blockers" list holds identically on EAGL (both are ES 3.0).

## 1. Prerequisite P — vendor RLHD source (BLOCKING, do first)

RLHD is **not in the repo** — it's a design reference (`github.com/117HD/RLHD` audited at `master@7dded59`). B0/Z0
need the real shaders + `ZoneRenderer` Java. Action:
- Vendor RLHD at a pinned rev under `ios/dev-tools/gpu-zone/rlhd-src/` (mirror how the base gpu plugin source is
  used). Pin the **same rev the blueprint/tbo2d audited (`7dded59`)** so the §5 line numbers hold; bump deliberately.
- This is **source** (shaders + Java), not assets. The **asset** licensing gate is separate and later (blueprint
  **B1b** — textures/JSON data; do not vendor assets in B0).
- Capture the rev + a `git` provenance note in `gpu-zone/RLHD-PROVENANCE.md`.

## 2. Phase Z0 — Zone ES3 conformance gate (before any renderer work)

Fail-closed proof that the whole zone shader surface compiles on EAGL ES 3.0 **before** investing in the renderer.
Extends the proven `gpu-b1` harness pattern (xcodegen + xcodebuild + simctl/device + md5 verdict).

**Z0.1 — extend the downporter to RLHD.** Today's downporter (`RouteCBridge.m` `downportShaderSource:` lines
486–650, mirrored in `gpu-b1/app/B1Downport.m`) implements 5 rules for the base plugin. RLHD needs:
- **Extend rule #1**: match `#version 420` and `#version 430` (RLHD injects these via `HdPlugin.java:343`), not just
  `#version 330`. Replace all with `#version 300 es`.
- **Add the TBO→2D repack** (blueprint blocker #1, spec proven in `gpu-tbo2d/README.md`) as downporter rules:
  1. `uniform isamplerBuffer textureFaces;` → `uniform highp isampler2D textureFaces;`
  2. inject `int tfW = textureSize(textureFaces, 0).x;` at `main()` entry (only in files declaring `textureFaces`).
  3. `texelFetch(textureFaces, EXPR)` → `texelFetch(textureFaces, ivec2((EXPR)%tfW, (EXPR)/tfW), 0)` (swizzle kept).
- **Class-13 int↔float casts**: RLHD scene/shadow shaders will need their own explicit-cast set (same class as the
  base-gpu rule #5, but RLHD-specific). Prefer **hand-maintained iOS shader variants** over a brittle keyed string
  pass for the cast-heavy files (blueprint B1 note) — discover the exact set by compiling and reading ES errors.
- **gl_FragDepth depth-write** for `scene_*` (the EAGL reconciliation item, §0): carry `clip.z` flat, write
  `gl_FragDepth = vClipDepth * gl_FragCoord.w`.

**Z0.2 — compile every zone shader.** Bundle the full RLHD zone set (`scene_*`, `shadow_*`, `tiled_lighting_*`,
`ui_*`, `utils/*`, `scaling/*`, `uniforms/*`; **avoid** `comp*`/`*_geom` — blueprint #8/#9), assembled by RLHD's own
`Template`/`#include`, downport each, `glCompileShader`+link+validate on a real ES 3.0 driver. **Fail on any non-ES3
symbol.** Start on the **Simulator** (cheap inner loop, like gpu-b1), then a **device** confirm (Simulator compiler ≠
A19 bit-for-bit — the standing B1 caveat).

**Z0.3 — renderability/precision probes (EAGL).** Extend `gpu-angle`/`gpu-routec` device probes: `DEPTH_COMPONENT24`
shadow sampling, clamp-to-edge border emulation (#6). **Defer `RGBA16UI` 2D-array color attach (#4) to B5.**

**Z0.4 — facade delta scan.** Static-scan vendored RLHD for GL calls not in the facade (`GLALL.java` — 100 methods,
81 ES3 symbols verified; manifest `bridge-manifest.md`). Known adds for the zone path: `glDrawBuffers` (plural, #11),
`glFramebufferTextureLayer` (#3, B5), conditional `GL_DRAW_INDIRECT_BUFFER` bind (#2), `glBindBufferRange` (present),
clamp-to-border emulation (#6). Output the exact delta → drives the facade work in B0.3.

**Gate Z0:** every zone shader compiles on device + EAGL probes green + facade delta enumerated. md5-sealed verdict
(`gpu-zone-z0-verdict.sh`), same discipline as `gpu-b1-verdict.sh`.

## 3. Phase B0 — renderer bring-up (after Z0 green)

**B0.1 — stage the RLHD jar.** Author `ios/dev-tools/gpu-zone/stage-ios-zone-jar.sh` as the RLHD analogue of
`stage-ios-gpu-jar.sh`: strip `org/lwjgl/*` + `rlawt`, recompile `ZoneRenderer` (+ RLHD GL-touching classes) against
the facade, preserve + ship the RLHD shader resources (downported at `glShaderSource` time, same seam as base gpu),
re-zip with manifest intact, md5-assert the class/shader inventory.

**B0.2 — facade `glTexBuffer` → `glTexImage2D` upload** for `textureFaces` (the host half of blocker #1): on the
facade's texture-buffer upload, `numTexels = bytes/12; W = min(MAX_TEXTURE_SIZE, cap); H = ceil(numTexels/W);
glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB32I, W, H, 0, GL_RGB_INTEGER, GL_INT, bytes)` — **same bytes, no transform**
(proven byte-exact in gpu-tbo2d). Bind as a normal `isampler2D` to `TEXTURE_UNIT_TEXTURED_FACES`.

**B0.3 — implement the facade delta** from Z0.4 (the GL calls RLHD needs beyond base gpu). Generated JNI forwarders
via `gen/routec_jni_gen.py`; shims in `RouteCBridge.m` for any non-1:1 call.

**B0.4 — Java conformance fixes** (blueprint, Java-side blockers): make `GL_DRAW_INDIRECT_BUFFER` bind/alloc
conditional (#2, `ZoneRenderer.java:252`/`RenderState.java:106`); `glDrawBuffer`→`glDrawBuffers` (#11); facade
`OpenGL33=true` so `supportsGpu` passes (#14, same trick as base-gpu L1). Minimal UBO + a stub material/water plumbing
so even a flat scene has its required inputs (blueprint: "B0 is not shader-only").

**B0.5 — wire + boot.** Register the zone bridge after `JNI_CreateJavaVM` (the `routecGpuMode` analogue), boot
RuneLite with RLHD as the active renderer, drive one scene.

**Gate B0:** ZoneRenderer renders the scene (even flat-shaded, no materials/lighting/shadows/water) on device. First
concrete check is even cheaper — **confirm the freshly-stripped base build still renders** (hardwired fragdepth =
the config that ran clean in A/B run 2, but unverified post-strip). That's the very first device tap of this phase.

## 4. Sequencing & the immediate next action

```
P  vendor RLHD source            (blocking; no device)
Z0 conformance gate              (Simulator inner loop -> 1 device confirm; md5-sealed)
   └─ first: re-confirm stripped base build renders on device (1 StikDebug tap)
B0 renderer bring-up             (stage jar -> facade tbo upload -> facade delta -> Java fixes -> boot)
```

**Immediate next action (no new device round needed):** vendor RLHD at `7dded59` into `ios/dev-tools/gpu-zone/
rlhd-src/`, then stand up the Z0 harness skeleton (`gpu-zone/` xcodegen project cloned from `gpu-b1/`, downporter
extended for `#version 420/430` + the TBO rules). The base-build render re-confirm is one StikDebug tap whenever the
device is next attached.

## 5. EAGL-specific risks / unknowns for B0

- **gl_FragDepth across RLHD's depth-dependent passes.** The base plugin had one scene pass; RLHD has scene + shadow
  + (later) water depth color. Each pass that relies on depth precision needs the gl_FragDepth treatment, and writing
  gl_FragDepth disables early-Z (measured 1.31× on the base scene — RLHD's heavier fragment load makes this cost
  matter more; watch it at the B0 gate).
- **Simulator ≠ device** for the ES3 compiler (standing caveat) — Z0 must end on a device compile, not just Simulator.
- **RLHD `Template`/`#include` assembly** off-JVM: the gpu-b1 harness pre-assembles shaders with RLHD's own Template.
  Confirm the zone shader set assembles the same way (the `uniforms/*` UBO includes especially).
- **Facade completeness is scan-driven** (blueprint §8): the Z0.4 static scan is the source of truth for the delta —
  do not assume the base-gpu 100-method surface covers RLHD.

## 6. Key files

- `ios/HD-PORT-BLUEPRINT.md` §5 (blocker list), §6 (phasing), §7 (asset pipeline — B1+, not B0).
- `ios/dev-tools/gpu-routec/stage-ios-gpu-jar.sh` — the base staging to clone for B0.1.
- `ios/dev-tools/gpu-routec/app/RouteCBridge.m` `downportShaderSource:` (486–650) — the downporter to extend.
- `ios/dev-tools/gpu-tbo2d/README.md` — the proven TBO→2D transform (Z0.1 rules + B0.2 upload).
- `ios/dev-tools/gpu-b1/` (`project.yml`, `app/B1*.m`, `gpu-b1-verdict.sh`) — the harness pattern to clone for Z0.
- `ios/dev-tools/gpu-routec/facade/org/lwjgl/opengl/GLALL.java` + `bridge-manifest.md` — the facade surface + manifest.
- `ios/dev-tools/gpu-routec/gen/routec_jni_gen.py` — the JNI generator for the facade delta (B0.3).
