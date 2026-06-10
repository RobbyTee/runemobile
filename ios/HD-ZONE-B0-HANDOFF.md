# Handoff → Phase B0 (iOS 117HD ZoneRenderer bring-up)

> Branch `gpu`. Self-contained kickoff for a fresh session. Project memory auto-loads, so this is just the focused
> "where we are + what's next." Everything below is pushed to `origin/gpu`.

## Where we are (one paragraph)
The GPU track is on iOS native **EAGL GLES 3.0** (decision locked: EAGL + `gl_FragDepth`, not ANGLE). The base
RuneLite `gpu` plugin already renders the live OSRS 3D world on the iPhone via the Route C JNI-GL bridge. The goal is
**117HD (RLHD)** on iOS. **Phase Z0 (shader conformance) is DONE and GREEN on the real device** (iPhone 17 Pro Max,
A19 Pro): all 6 RLHD zone shaders (scene/shadow/ui × vert/frag) compile to GLSL ES 3.00 and all 3 programs link —
verdict `ALL_ZONE_PROGRAMS_LINK_ES300`, sealed in `ios/dev-tools/gpu-zone/`. So the entire zone shader surface is
proven portable. **Next is Phase B0: actually bring up the RLHD ZoneRenderer.**

## Progress this session (host-only; all pushed to `origin/gpu`)
- **B0.1 GREEN** (`stage-ios-zone-jar.sh`, commit `c76cb1c7b`): unmodified RLHD `@7dded59` (full src vendored +
  gitignored, see `rlhd-src/PROVENANCE.md`) compiles against `[gpu-zone/facade/ : lwjgl-stripped client jar]` →
  `client-1.12.29-SNAPSHOT-rlhd.jar` (304 classes, 0 lwjgl leak, 47 shaders, 9 json; md5
  `04e0b29fa4761cca3386d8eb6ca457d3`). `facade/` (35 classes, seeded from Android's validated RLHD facade-src) is
  the RLHD-complete superset + the spec for the native delta.
- **B0.3 facade-delta scan** (`scan-facade-delta.sh` → `facade-delta.md`/`.txt`, `used-descriptors-rlhd.txt`,
  commit `2fd63d395`): 209 RLHD facade calls − base 100 = 117 delta, categorized → genuine native surface ≈ **31
  GLES-core forwarders + 3 off-heap + the glTexBuffer→2D repack + 4 shims**; the rest is facade pure-Java or safe
  B0 stub.
- **B0.4 conformance analysis** (`B0-conformance-notes.md`, commit `725e85442`): source-proved that unmodified
  RLHD takes the ES-3.0-core path by **caps gating** — zone is default; `supportsGpu` only needs `OpenGL33`
  (already true); OpenCL/compute never run on the zone path; **indirect draw is caps-gated OFF** (→ auto-fallback
  to direct `glMultiDrawArrays`, Route C shim covers) leaving only the unconditional `GL_DRAW_INDIRECT_BUFFER`
  alloc to remap in the facade; storage-buffers off (`glBufferData` path); `glDrawBuffer`→plural via facade shim.
  **No RLHD source patches needed.**

**What's left for B0 (device-coupled):** implement the native delta in `RouteCBridge.m` (port the Android **AG3-a**
Zone GL-delta forwarder bodies) + B0.2 TBO 2D upload + the 4 shims; wire the zone bridge after `JNI_CreateJavaVM`;
boot RuneLite with RLHD. First device tap is still the **base-gpu render re-confirm** (handoff step 1, StikDebug).
The bridge is JVM-only-testable (no JVM on the Simulator) → needs the device attached.

## Read first
- `ios/HD-ZONE-B0-SCOPE.md` — the step-by-step B0 plan (this is the spec to execute).
- `ios/dev-tools/gpu-zone/README.md` — what Z0 produced (the assembler, ios-overrides, the device runbook).
- `ios/HD-PORT-BLUEPRINT.md` §5 (ES-3.0 blocker list) + §6 (phasing).
- `ios/dev-tools/gpu-tbo2d/README.md` — the proven `textureFaces` TBO→2D RGB32I repack (blocker #1).
- `ios/dev-tools/gpu-routec/stage-ios-gpu-jar.sh` — the base-gpu staging script to clone for B0.1.

## What to do (B0, in order)
1. **Cheap first check (device):** confirm the freshly-stripped base Route C build still renders the scene
   (hardwired `gl_FragDepth` = the config that ran clean in the EAGL A/B; unverified since the device-kit strip).
   This needs StikDebug (it's the full JVM app). One tap.
2. **B0.1 — stage the RLHD jar:** author `ios/dev-tools/gpu-zone/stage-ios-zone-jar.sh` (analogue of
   `stage-ios-gpu-jar.sh`): vendor/compile RLHD's `ZoneRenderer` (+ GL-touching classes) against the facade, ship the
   RLHD shader resources, re-zip. Note: B0 needs the RLHD **Java** too (Z0 only vendored shaders) — pull from RLHD
   `@7dded59` (see `gpu-zone/rlhd-src/PROVENANCE.md`).
3. **B0.2 — facade TBO upload:** replace `glTexBuffer(GL_RGB32I)` with a 2D `glTexImage2D(GL_RGB32I, W, ceil(N/W))`
   of the same bytes (gpu-tbo2d proved this byte-exact), bound as `isampler2D`. Pair it with the shader-side TBO→2D
   rewrite (already implemented in the Z0 assembler's downport — rules 1/2/3 for `textureFaces`).
4. **B0.3 — facade-delta scan (Z0.4):** static-scan RLHD for GL calls not in the facade (`GLALL.java` /
   `bridge-manifest.md`); implement the delta (known adds: `glDrawBuffers`, `glFramebufferTextureLayer`, conditional
   `GL_DRAW_INDIRECT_BUFFER`).
5. **B0.4 — Java conformance fixes:** make draw-indirect bind conditional (#2), `glDrawBuffer`→`glDrawBuffers` (#11),
   facade `OpenGL33=true` so `supportsGpu` passes (#14), minimal UBO + stub material/water plumbing.
6. **B0.5 — wire + boot:** register the zone bridge after `JNI_CreateJavaVM`, boot RuneLite with RLHD as the
   renderer. **Gate B0:** the ZoneRenderer renders the scene (even flat-shaded) on device.

## Gotchas
- **Z0 used a MINIMAL flat-scene config** (shadows/tiled-lighting/dynamic-lights/displacement/POM OFF, in
  `ZoneShaderAssemble.java buildConfig()`). Turning features on in B1+ surfaces their shaders' own #13 cast sites —
  the assembler config + CAST map grow per phase. The override mechanism (`ios-overrides/`) + the glslang fast-filter
  (`glslang-check.sh`, works for shadow/ui, NOT scene) are there to reuse.
- **Device UDID:** `xcrun devicectl list devices` (current iPhone = `AEA9E1BC-E149-57AB-A9DD-C4AEA668CD9B`).
- The `gpu-zone` Z0 probe needs **no StikDebug** (pure EAGL). The full RuneLite app (HelloShell / Route C) **does**
  (JIT). `devicectl install` shows a non-fatal "No provider was found" line — ignore it.
- Commit/push under the user's name, no AI attribution (repo convention).

## Suggested opener for the new session
> "Continue the iOS GPU track on branch `gpu`. Z0 is done (green on device). Start Phase B0 per
> `ios/HD-ZONE-B0-HANDOFF.md` + `ios/HD-ZONE-B0-SCOPE.md`."
