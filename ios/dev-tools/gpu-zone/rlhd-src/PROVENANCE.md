# Vendored RLHD source — provenance

- **Upstream:** https://github.com/117HD/RLHD
- **Pinned rev:** `f5be256078b5e398089aa73e48116c7f95dc329f` (tag `v1.6.0.1`, 2026-10-19, "Strip out day & night from
  shaders when disabled"), the release Plugin Hub serves. Both stage scripts fetch this rev.
- **Previous rev:** `7dded591a21d4c17c2d8c077c8ac0ecfbbd7313f` (`master`, 2026-06-03, "Update gamevals to 2026-06-02
  (#1107)"), the rev the HD port blueprint (`ios/HD-PORT-BLUEPRINT.md` §5) and the `gpu-tbo2d` harness audited, so the
  blocker line numbers in those docs line up with that tree. The tracked Z0 subset below (`shaders/hd/**`, `java/`)
  stays at this rev as the record of the B0 bring-up; it is not refreshed on a bump. Bump deliberately: after any
  bump re-run `tools/shader-matrix/run-matrix.sh ios` (the shader gate since v1.6.0.1) and `../check-std140.sh`.
- **License:** BSD 2-Clause (`./LICENSE`, Copyright (c) 2021, 117) — same family as RuneLite; vendoring with the
  notice retained is compliant. This is a **personal/technical build** (see project risk note); not redistributed.

## What's vendored (and what is NOT)

- `shaders/hd/**/*.glsl` — the full RLHD zone-path shader source (47 files: `scene_*`, `shadow_*`, `tiled_lighting_*`,
  `ui_*`, `overlays/*`, `scaling/*`, `uniforms/*`, `utils/*`, and the `comp*`/`*_geom` files we will **not** port).
  These use `#include` + `#define` placeholders resolved at build time by RLHD's `ShaderIncludes` (see below).
- `java/rs117/hd/opengl/shader/{ShaderIncludes,ShaderTemplate,ShaderException}.java` — RLHD's shader **assembler**
  (the `#include`/`#define`/UBO-block resolver), vendored as the reference for the off-JVM Z0 assemble step.
- `src/` — **B0.1 (jar staging)** vendors the **full** RLHD `src/` (`src/main/java`, 159 files incl. `ZoneRenderer`,
  `HdPlugin`, managers; `src/main/resources`, 256 files: shaders + loader JSON) **plus the repo-root `lombok.config`**
  (`lombok.accessors.chain = true` — without it `Environment.java`'s builder-style `.setKey(...).setArea(...)` chain
  fails to compile, since lombok defaults to `void` setters). This full tree is **gitignored** (`../.gitignore`:
  `rlhd-src/src/`) — it's large and recoverable from the pinned rev; the Z0 shader/assembler subset above stays
  tracked. `stage-ios-zone-jar.sh` requires `rlhd-src/src/main/java` present and fails closed otherwise.
- **NOT vendored:** assets (textures, JSON beyond loaders, gamevals binaries) — the separate licensing/bundle gate
  (blueprint **B1b**), needed for materials/lighting (B1+), not for the B0 flat-scene bring-up.

To re-fetch the full tree (for B0.1 staging):
```
git clone https://github.com/117HD/RLHD && git -C RLHD checkout f5be256
cp -R RLHD/src/main  ios/dev-tools/gpu-zone/rlhd-src/src/
cp    RLHD/lombok.config  ios/dev-tools/gpu-zone/rlhd-src/src/lombok.config   # bubbles to the main/java sources
```
