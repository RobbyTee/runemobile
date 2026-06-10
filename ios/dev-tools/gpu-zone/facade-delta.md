# Zone facade-delta — the B0.3 RouteCBridge.m native spec (Z0.4)

> Branch `gpu`, Phase **B0.3**. Output of `./scan-facade-delta.sh` (deterministic, re-runnable): the facade calls
> **RLHD invokes** minus the **100 calls the base Route C bridge already services** (`../gpu-routec/
> used-descriptors.txt`). Machine lists: `used-descriptors-rlhd.txt` (209 RLHD facade calls) and
> `facade-delta.txt` (the 117-call raw delta). This file **categorizes** that delta into what the iOS bridge must
> actually add for the **B0 flat-scene** gate vs. what's facade-Java-provided or deferrable.
>
> Scan provenance: RLHD `@7dded59`, staged jar `client-1.12.29-SNAPSHOT-rlhd.jar` (B0.1). Re-run after any rev bump.

## Headline

117 raw delta calls, but most are **not** native-bridge work:

| Bucket | Count | Bridge work? |
|---|---|---|
| **C — facade pure-Java** (`system/MemoryStack`, most of `MemoryUtil`, all `opencl/*`, `AWTContext.get*` stubs) | ~63 | **No** — already implemented in `facade/` (Java); ships in the gpu jar. |
| **A — GL forwarders to implement** (rendering-critical, run on the flat-scene path) | ~31 | **Yes** — straight `glXxx` forwarders / small shims in `RouteCBridge.m`. |
| **B — GL stub/no-op for B0** (compute/query/timer/debug/indirect — not on the zone *flat* path) | ~16 | **Stub** — register as no-op (or assert-unreached); real impl is B1+/perf. |
| **D — native off-heap** (`MemoryUtil.nmemAllocChecked`/`nmemFree`/`memAddress0`) | 3 | **Yes** — tiny: `malloc`/`free`/`GetDirectBufferAddress`. Used by `ModelCache`. |

So the genuine B0.3 native surface is ~**31 GL forwarders + 3 off-heap + the glTexBuffer→2D repack (B0.2)** ≈ 35
new native entries, on top of the base 100. The rest is either already in the facade Java or a safe B0 stub.

## Bucket A — GL forwarders to IMPLEMENT (RouteCBridge.m, run on the flat-scene path)

Most are 1:1 `glXxx` forwarders (ES 3.0 core). Notes flag the non-trivial ones.

- **FBO attach (zone G-buffer/shadow):** `glFramebufferTexture:(IIII)V`, `glFramebufferTexture2D:(IIIII)V`,
  `glFramebufferTextureLayer:(IIIII)V` *(ES3 core; `glFramebufferTexture` (no face/layer) → emulate via
  `glFramebufferTextureLayer`/`2D` as RLHD's attach target dictates — blocker #3, but the **non-array** attaches
  here are B0; the layered tiled-lighting array FBO is B5)*.
- **Draw-buffer (MRT):** `glDrawBuffer:(I)V` → **forward to `glDrawBuffers(1, {buf})`** (ES3 has only the plural —
  blocker #11; the singular is the facade's job, RLHD stays unmodified).
- **State:** `glCullFace:(I)V`, `glFinish:()V`, `glFlush:()V`, `glVertexAttribDivisor:(II)V`.
- **Uniforms:** `glUniform2f/3f`, `glUniform2iv/3iv/3fv/4fv/4iv` *(array variants: `Get<...>ArrayRegion` →
  `glUniformNxv(loc,count,ptr)`)*.
- **Buffer up/copy:** `glBufferSubData:(IJL…ByteBuffer/FloatBuffer/IntBuffer;)V` *(NIO → addr+size, see base
  manifest cat 5/6)*, `glCopyBufferSubData:(IIJJJ)V`, `glMapBufferRange:(IJJI)…ByteBuffer`,
  `glMapBuffer:(IIL…ByteBuffer;)…ByteBuffer` *(ES3 `glMapBufferRange`; the `glMapBuffer` overload maps via
  range)*, `glGetBufferParameteri64:(II)J`.
- **Textures (3D / array uploads for material+shadow):** `glTexStorage3D:(IIIIII)V`,
  `glTexImage3D:(…ShortBuffer;)V`, `glTexSubImage3D:(…IntBuffer;)V` *(NIO marshalling; the BGRA swizzle that bit
  the Android real-Mali run lives here — watch it)*, `glTexParameterfv:(II[F)V`,
  `glTexBuffer:(III)V` → **B0.2 TBO→2D `glTexImage2D(GL_RGB32I,W,ceil(N/W))` repack** (byte-exact, `gpu-tbo2d`).
- **Queries / introspection used at init:** `glGetIntegerv:(I[I)V`, `glGetProgramiv:(II[I)V`,
  `glGetFramebufferAttachmentParameteri:(III)I`, `glGetProgramBinary:(I[I[IL…ByteBuffer;)V` *(program-binary
  cache; if EAGL lacks `GL_PROGRAM_BINARY_FORMATS` usefully, no-op the cache write — RLHD recompiles)*.
- **Sync (buffer fences — `GLFence.java`, real on the zone path):** `glFenceSync:(II)J`, `glClientWaitSync:(JIJ)I`,
  `glDeleteSync:(J)V` *(ES3 core sync objects; the `J` handle is `GLsync` as `intptr_t`)*.

## Bucket B — GL stub/no-op for B0 (not on the zone *flat* path; confirm via boot, then implement in B1+)

- **Compute:** `glDispatchCompute`, `glMemoryBarrier`, `glBindImageTexture`, `glBufferStorage` — `glDispatchCompute`
  is only `LegacyRenderer` (verified by source grep), never the zone path. Zone uses the CPU/streaming uploader at
  B0. **Stub.**
- **Indirect draw:** `glDrawArraysIndirect`, `glDrawElementsIndirect`, `glMultiDrawArraysIndirect` — `CommandBuffer.
  java`; gated by **blocker #2** (make `GL_DRAW_INDIRECT_BUFFER` bind/alloc conditional in `ZoneRenderer.java:252`/
  `RenderState.java:106`). For the B0 flat scene, keep indirect **off** → these don't fire. **Stub + Java gate.**
- **Timer queries (perf overlay):** `glGenQueries`, `glDeleteQueries`, `glQueryCounter`, `glGetQueryObjectiv`,
  `glGetQueryObjectui64` — ES3.0 has **no** timer queries (EXT only). No-op (gen returns a dummy id; getters
  return 0). RLHD's timing just reads 0. **Stub.**
- **Debug labels/groups:** `glObjectLabel`, `glPushDebugGroup`, `glPopDebugGroup`, `glDebugMessageControl` — KHR_debug
  is optional on EAGL. **No-op.**

## Bucket C — facade pure-Java (NO bridge work; already in `facade/`)

- `org/lwjgl/system/MemoryStack.*` (push/pop arena over a direct ByteBuffer; `malloc*`/`calloc*`/`pointers`) and
  most of `MemoryUtil.*` (`memAlloc*`/`memFree`/`memRealloc`/`memUTF8`/`memASCII`/`mem*Buffer`) — implemented in
  Java in the copied facade.
- All `org/lwjgl/opencl/*` (`CL10.*`, `CL12.*`, `CLImageFormat.calloc`, callbacks, `KHRGLSharing`, `APPLEGLSharing`)
  — **symbols-only**; the Zone path never creates a CL context, so no `cl*` ever runs. **No bridge work.**
- `AWTContext.getGLContext/getWGLHDC/getGLXDisplay/getCGLShareGroup` → `0L` stubs (read only by `OpenCLManager`,
  which doesn't run on Zone). `Callback.free`, `Platform.get` — Java.

## Bucket D — native off-heap (IMPLEMENT; tiny) — `ModelCache.java`

`MemoryUtil.nmemAllocChecked:(J)J` → `malloc`; `nmemFree:(J)V` → `free`; `memAddress0:(L…Buffer;)J` →
`(*env)->GetDirectBufferAddress`. RLHD's `ModelCache` keeps its model store off-heap; these 3 run on the zone
path and must be real. (`MemoryStack.java`'s 1 native + `MemoryUtil.nNewBuffer` are only reached by the CL path →
may stay unbound at B0.)

## How this maps to the bridge generator

`../gpu-routec/gen/routec_jni_gen.py` reads a `used-descriptors`-style list and emits the `RegisterNatives` table +
forwarders. The B0.3 task: feed it `used-descriptors-rlhd.txt` (or just the A+D subset), supply the shim bodies for
the non-1:1 cases noted above (glDrawBuffer→plural, glTexBuffer→2D repack, the NIO/sync marshallings, the 3 off-heap
funcs), and register the bucket-B stubs as no-ops. Then re-stage the **base gpu** jar with the grown facade so the
single in-jar facade covers both base + RLHD (Android model: facade ships once, in the gpu jar).
