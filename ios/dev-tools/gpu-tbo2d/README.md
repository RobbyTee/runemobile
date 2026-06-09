# gpu-tbo2d — texture-buffer → 2D integer-texture repack (117HD blocker #1), de-risked on iOS GLES

> Branch `gpu`. Self-contained iOS Simulator GLES harness (mirrors `gpu-depthsim`/`gpu-b1`/`gpu-clipremap`:
> EAGL ES3 offscreen FBO + `glReadPixels` + md5-gated verdict). Retires the single largest unproven piece of the
> 117HD-on-iOS port: the texture-buffer repack.
>
> Run: `xcodegen generate && xcodebuild -project GpuTbo2d.xcodeproj -scheme GpuTbo2d -destination "id=<booted-sim>" -derivedDataPath build/dd build`,
> then `simctl install/launch`, pull `Documents/gpu-tbo2d-verdict.log`, and `./tbo2d-verdict.sh`.

## The blocker

RLHD's Zone renderer feeds per-face data to its VERTEX shaders (`scene_vert`, `shadow_vert`) via a
`GL_TEXTURE_BUFFER`:

```glsl
uniform isamplerBuffer textureFaces;
ivec4 face = texelFetch(textureFaces, faceId);
```

`isamplerBuffer` / `GL_TEXTURE_BUFFER` is **ES 3.2 core** — present on Android's Mali-G715 (where 117HD runs
today), **absent on iOS EAGL GLES 3.0 *and* ANGLE-on-Metal ES 3.0**. So the port must repack those records into a
**2D integer texture** and rewrite the fetch with a computed `(x,y)`:

```glsl
uniform highp isampler2D textureFaces2D;       // int W = textureSize(textureFaces2D, 0).x;
ivec4 face = texelFetch(textureFaces2D, ivec2(faceId % W, faceId / W), 0);
```

This is blocker **#1** in `ios/HD-PORT-BLUEPRINT.md` §5 — "the big one" — and it is **backend-independent**
(needed on both EAGL and ANGLE) and **upstream of every Zone shader** (they won't compile until the fetch is
rewritten). Android never had to solve it (native TBO), so it is **0%-de-risked** by the Android success.

## Result (md5-sealed: `evidence/gpu-tbo2d-sim-verdict.log`)

```
GL_MAX_TEXTURE_SIZE=4096   faceCount=50000  ->  50000 > 4096, a 1D texture CANNOT hold the faces: 2D is REQUIRED
GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS=16  (vertex texture fetch supported)

PHASE A  RGBA32I 2048x25 upload .................. glErr=NONE
PHASE B  fragment own-pixel fetch ................ green=50000/50000  red=0  -> B_FRAG_EXACT = YES
PHASE C  vertex wrap fetch (gl_VertexID) ......... green=50000/50000  red=0  -> C_VERT_WRAP_EXACT = YES
VERDICT  tbo2d_repack_exact = YES
```

## What this proves

1. A **2D RGBA32I integer texture** is creatable + uploadable on iOS GLES 3.0 with no error.
2. The repacked `texelFetch(tex, ivec2(faceId % W, faceId / W))` is **byte-exact** vs a CPU reference for **all
   50,000 faces**, including across row boundaries and at large/negative/bitwise int values (format + sign +
   round-trip all correct).
3. It works in the **vertex stage** (Phase C drives `faceId` from `gl_VertexID`, independent of pixel position —
   the genuine wrap-arithmetic test, and the exact stage RLHD fetches in).
4. The chosen `faceCount` (50,000) **exceeds `GL_MAX_TEXTURE_SIZE` (4096)**, so a 1D layout is impossible — the 2D
   repack is not merely an ES-3.0 substitute, it is the only viable layout for real scene face counts.

## Confidence

Integer 2D textures, `texelFetch`, and vertex texture fetch are **ES 3.0 core** (not optional renderability like an
`RGBA16UI` color attachment), so this **software-renderer GREEN is high-confidence for the device Metal GPU** (a
superset). One device confirm remains cheap but low-risk.

## Next (turns this probe into the real port)

- Read RLHD's actual `textureFaces` record schema (`GLTextureBuffer.java`, `scene_vert.glsl:43`,
  `shadow_vert.glsl:42`) and match the texel format/stride exactly (this probe used a representative `ivec4`/RGBA32I
  record; confirm RLHD's is `ivec4`-shaped or adjust to its component count).
- Implement the repack as a downporter rewrite (`isamplerBuffer` → `isampler2D`, `texelFetch(buf, i)` →
  `texelFetch(tex, ivec2(i % W, i / W), 0)`, inject `W = textureSize(...).x`) plus the Java-side buffer→2D-texture
  upload, behind the Route C facade.
