# Vendored RLHD source — provenance

- **Upstream:** https://github.com/117HD/RLHD
- **Pinned rev:** `7dded591a21d4c17c2d8c077c8ac0ecfbbd7313f` (`master`, 2026-06-03, "Update gamevals to 2026-06-02 (#1107)")
  — the same rev the HD port blueprint (`ios/HD-PORT-BLUEPRINT.md` §5) and the `gpu-tbo2d` harness audited, so the
  blocker line numbers in those docs line up with this tree. Bump deliberately (re-run the Z0 gate after any bump).
- **License:** BSD 2-Clause (`./LICENSE`, Copyright (c) 2021, 117) — same family as RuneLite; vendoring with the
  notice retained is compliant. This is a **personal/technical build** (see project risk note); not redistributed.

## What's vendored (and what is NOT)

- `shaders/hd/**/*.glsl` — the full RLHD zone-path shader source (47 files: `scene_*`, `shadow_*`, `tiled_lighting_*`,
  `ui_*`, `overlays/*`, `scaling/*`, `uniforms/*`, `utils/*`, and the `comp*`/`*_geom` files we will **not** port).
  These use `#include` + `#define` placeholders resolved at build time by RLHD's `ShaderIncludes` (see below).
- `java/rs117/hd/opengl/shader/{ShaderIncludes,ShaderTemplate,ShaderException}.java` — RLHD's shader **assembler**
  (the `#include`/`#define`/UBO-block resolver), vendored as the reference for the off-JVM Z0 assemble step.
- **NOT vendored:** assets (textures, JSON data, gamevals) — those are the separate licensing/bundle gate (blueprint
  **B1b**), needed for materials/lighting (B1+), not for the B0 flat-scene bring-up. Also not vendored: the rest of the
  RLHD Java (`ZoneRenderer`, `HdPlugin`, managers) — pulled in when **B0.1** (jar staging) starts; recover any of it
  from the pinned rev above.

To re-fetch the full tree: `git clone https://github.com/117HD/RLHD && git -C RLHD checkout 7dded59`.
