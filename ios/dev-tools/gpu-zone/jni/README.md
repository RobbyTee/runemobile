# gpu-zone/jni — RLHD Zone JNI delta bridge

> Phase **B0.2/B0.3**. `routec_zone_jni.m` = the iOS-bridge half of the facade delta
> (`../facade-delta.txt`, written by `../scan-facade-delta.sh`): the GL + off-heap calls RLHD makes beyond the base
> gpu plugin's surface. It is **registered on top of** the base bridge (`../../gpu-routec/jni/routec_gl_jni.m`).
> **Compile-checked for iOS arm64** by `../zone-bridge-compile-check.sh` (`ZONE_BRIDGE_COMPILES_ARM64`). It is
> compiled into the app and runs on the phone and in the iOS Simulator (`../../run-sim.sh`).

## What it registers (59 entries)

- **56 entries on `org/lwjgl/opengl/GLALL`** (all GL facade classes are `extends GLALL {}` shims, so every GL
  native lives on GLALL). They cover every GL method in `facade-delta.txt`. A few replace a base-table entry of
  the same name and signature; registered later, they win.
  - **Bucket A** (35): GLES-3.0-core forwarders — FBO attach, uniforms, buffer up/copy/map, 3D-tex uploads, ES3
    sync fences, introspection getters. NIO buffers follow LWJGL's contract (data at `position()`, length
    `remaining()`). iOS GLES 3.0 rejects the desktop BGRA + `GL_UNSIGNED_INT_8_8_8_8_REV` uploads, so
    `glTexSubImage3D` repacks them as RGBA and `glTexSubImage2D` (the PBO variant, a base override) sends them as
    `GL_UNSIGNED_BYTE`.
  - **#11 / #1 shims** (2): `glDrawBuffer`→`glDrawBuffers(1,&buf)`; `glTexBuffer`→2D `GL_RGB32I` repack (blocker
    #1, byte-exact per `gpu-tbo2d`).
  - **#1 companion** (1): a `glBindTexture` override that remaps `GL_TEXTURE_BUFFER` to `GL_TEXTURE_2D`.
  - **RLHD v1.6.0.1 additions** (4): `glPolygonOffset`, `glDrawArraysInstanced`, and `glEnable`/`glDisable`
    overrides that drop the desktop-only `GL_PROGRAM_POINT_SIZE`.
  - **Bucket B** (14): no-op stubs for the compute / indirect-draw / timer-query / KHR_debug calls that are
    caps-gated OFF on the iOS zone path so they never fire — present only so the table is complete and a stray
    call can't `UnsatisfiedLinkError`. They reference **no** GL symbol (those are ES 3.1+/desktop-only and absent
    from iOS GLES).
- **3 off-heap on `org/lwjgl/system/MemoryUtil`**: `nmemAllocChecked`→`malloc`, `nmemFree`→`free`,
  `memAddress0`→`GetDirectBufferAddress` (RLHD `ModelCache`).

## App wiring

1. The runtime classpath is `client-shaded.jar` (staged by `stage-ios-gpu-jar.sh` with the grown `../facade/`
   superset by default, so the single in-jar facade declares all the delta natives) plus `client-rlhd.jar`.
2. `ios/app/project.yml` compiles `routec_zone_jni.m` into the app alongside `routec_gl_jni.m`.
3. After `JNI_CreateJavaVM`, whichever renderer is selected, `JVMLauncher.m` calls `RouteC_registerGLNatives(env)`
   **then** `RouteC_registerZoneNatives(env)` (the second is skipped only in the L1 kill-test).

## Open items in the source

- `glGetProgramBinary` still sizes its sink by `GetDirectBufferCapacity`, not `remaining()`.
- `glFramebufferTexture` (ES 3.2) is emulated as a layer-0 attach; a true layered attach is B5 (tiled lighting).
