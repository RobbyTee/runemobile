# Zone B0 conformance notes — how unmodified RLHD takes the GLES-core path

> Branch `gpu`, Phase **B0.4 analysis** (host source audit of vendored RLHD `@7dded59`). We compile RLHD
> **unmodified** (B0.1), so every "blocker" fix must live in the **facade / bridge / config**, not a source patch.
> Good news: RLHD's own runtime branches are **GL-capability-gated**, so reporting the right caps steers it onto the
> ES-3.0-core path with no source changes. The iOS facade caps already mirror Android's **validated** Zone set.

## 1. Renderer selection — zone is the default (no work)

`HdPlugin.java:559` — `var rendererClass = config.legacyRenderer() ? LegacyRenderer.class : ZoneRenderer.class;`.
Fresh config → `legacyRenderer()==false` → **ZoneRenderer**. (Wire-up B0.5 can also force it via config.)

## 2. `supportsGpu` (#14) — trivially satisfied (no work)

`ZoneRenderer.supportsGpu(GLCapabilities) { return glCaps.OpenGL33; }` (`ZoneRenderer.java:167`). The facade's
`GL.createCapabilities()` already returns `OpenGL33=true` (proven on device by the base-gpu **L1** kill-test).
Nothing to do.

## 3. OpenCL / GPU-compute — never runs on the zone path (symbols-only stub)

- `OpenCLManager` is referenced only by `LegacyRenderer.java` and `utils/buffer/SharedGLBuffer.java`. In
  `SharedGLBuffer` every CL call is guarded by `OpenCLManager.context != 0` — and nothing on the zone path ever
  sets it (no `OpenCLManager.initialize()` from `ZoneRenderer`/`HdPlugin.startUp`). So `context` stays `0` → CL
  branches are skipped. The facade `org/lwjgl/opencl/*` is **symbols-only**; no `cl*` native binding needed.
- `glDispatchCompute`/`glMemoryBarrier` appear only in `LegacyRenderer.java` (verified by grep) → never on the
  zone path. Bucket-B stub is safe.

## 4. Indirect draw (#2) — caps-gated OFF; RLHD auto-falls-back to direct `glMultiDrawArrays`

The draw-mode is chosen at runtime by GL caps (`DynamicModelVAO.java:285-296`, `Zone.java:813-832`):
```
drawRangeCount == 1: (GL_CAPS.OpenGL40 && SUPPORTS_INDIRECT_DRAW) ? DrawArraysIndirect : DrawArrays
else:                (GL_CAPS.OpenGL43 && SUPPORTS_INDIRECT_DRAW) ? MultiDrawArraysIndirect : MultiDrawArrays
```
- The facade reports `OpenGL40=false`, `OpenGL43=false`, `OpenGL45=false` (Java boolean defaults; only `OpenGL33`
  is set true by the bridge). **Either operand false ⇒ the direct branch is taken.**
- Belt-and-suspenders: `SUPPORTS_INDIRECT_DRAW = config.indirectDraw().get(NVIDIA_GPU && !APPLE)`
  (`HdPlugin.java:577`). On iOS `NVIDIA_GPU=false` (renderer string isn't "nvidia") **and** `APPLE` is true if
  `os.name`→`MacOS` — so the default is **false** regardless of which operand decides it. (Fresh iOS config; we
  control it at B0.5.)
- ⇒ the zone scene draws via **`glMultiDrawArrays`/`glDrawArrays`** — the Route C base shim already covers
  `glMultiDrawArrays` (loop over `glDrawArrays`). **No indirect draw fires.**
- **BUT** `ZoneRenderer.initializeBuffers()` (`:252`) allocates `indirectDrawCmds` on `GL_DRAW_INDIRECT_BUFFER`
  **unconditionally** (and `.orphan()`/`.upload()` it each frame at `:662-665`). `GL_DRAW_INDIRECT_BUFFER` (0x8F3F)
  is an **ES 3.1** enum; EAGL is ES 3.0. → **facade shim job:** in the `glBindBuffer`/`glBufferData` forwarders,
  remap `GL_DRAW_INDIRECT_BUFFER` to a harmless valid target (e.g. `GL_ARRAY_BUFFER`/`GL_COPY_WRITE_BUFFER`) so the
  GLBuffer just lives as a normal VBO. The data is never consumed by a draw (see above).

## 5. Storage buffers — gated OFF (no work)

`SUPPORTS_STORAGE_BUFFERS = GL_CAPS.GL_ARB_buffer_storage && … ` (`HdPlugin.java:578`); facade reports
`GL_ARB_buffer_storage=false` → false. RLHD uses `glBufferData` orphaning, not `glBufferStorage`. `GL44.glBufferStorage`
bucket-B stub is safe (never called).

## 6. `glDrawBuffer` → `glDrawBuffers` (#11) — facade shim

`HdPlugin.java:1438` `glDrawBuffer(GL_NONE)` (+ MRT setup elsewhere). ES3 has only the **plural**
`glDrawBuffers`. The facade's `glDrawBuffer(int)` native forwarder calls `glDrawBuffers(1, &buf)`. RLHD unchanged.

## 7. Net B0 native-bridge surface (RouteCBridge.m)

Putting §3–§6 together with the facade-delta scan (`facade-delta.md`), the genuine native work atop the base 100:
- **Bucket A GLES-core forwarders** (~31): FBO attach (`glFramebufferTexture*`), uniforms, buffer up/copy/map,
  3D-texture uploads, sync fences, introspection getters. Mostly 1:1 `glXxx`.
- **B0.2 `glTexBuffer`→2D repack**: `textureFaces` TBO as `glTexImage2D(GL_RGB32I,W,ceil(N/W))`, bound `isampler2D`
  (byte-exact, `gpu-tbo2d`); paired with the shader-side TBO→2D rewrite (already in the Z0 downport).
- **Bucket D off-heap** (3): `nmemAllocChecked`/`nmemFree`/`memAddress0` (`ModelCache`) → `malloc`/`free`/
  `GetDirectBufferAddress`.
- **4 shims**: `glDrawBuffer`→plural (#11); `GL_DRAW_INDIRECT_BUFFER` bind/alloc remap (§4); the NIO size/offset
  marshallings (base manifest cats 3-6); the bucket-B no-op stubs (indirect/compute/timer-query/debug).
- **Caps**: keep the facade `GLCapabilities` exactly as Android's validated Zone set (`OpenGL33=true`, everything
  higher + `GL_ARB_*` false). The iOS facade copy already matches.

**Closest reference for the forwarder bodies:** the Android native Zone GL-delta (`AG3-a`: "librlawt Zone GL-delta
+ MemoryUtil natives register clean") services this exact RLHD `@7dded59` delta — port those bodies into
`RouteCBridge.m` rather than re-deriving.

## 8. Still genuinely unknown until boot (device)

- Exact UBO std140 layout acceptance + the `MATERIAL_CONSTANTS`/getter includes with a minimal material set (B0.4
  "minimal UBO + stub material/water plumbing").
- `gl_FragDepth` depth-write on RLHD `scene_*`/`shadow_*` (EAGL §0) — net-new shader surgery vs the Z0 conformance
  shaders (Z0 proved *compile*, not the depth-correct render).
- Whether the direct `glMultiDrawArrays` path + the streaming uploader actually paints a frame (vs just clearing).
- The first cheap check is still the **base-gpu render re-confirm** (1 StikDebug tap) per the handoff.
