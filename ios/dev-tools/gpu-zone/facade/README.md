# gpu-zone/facade — the iOS RLHD (117HD) LWJGL facade

> Phase **B0.1**. The pure-Java `org.lwjgl.{opengl,opencl,system}` + `net.runelite.rlawt.AWTContext`
> surface that the **unmodified RLHD** source compiles against (lwjgl physically stripped from the compile
> classpath), so a symbol RLHD uses but we don't declare is a `javac` error at stage time, not a runtime
> `NoClassDefFound`. Consumed by `../stage-ios-zone-jar.sh`.

## Provenance & relationship to the base-gpu facade

This is a **superset** of the base-gpu Route C facade (`../../gpu-routec/facade-native/`, 14 classes). It adds the
**22 classes RLHD references beyond base gpu** — the OpenCL symbols-only stub (`org/lwjgl/opencl/*`, the Zone path
never creates a CL context), the higher GL version classes (`GL20`/`GL40`/`GL41C`/`GL43`/`GL44`/`ARB*`), and
`org/lwjgl/system/{MemoryStack,MemoryUtil,Platform}` + `Version`. Most shared classes (`GL33C`, `GL45C`, …) are
byte-identical to the base-gpu facade; `GLALL`, `GLCapabilities`, `AWTContext`, `BufferUtils`, `PointerBuffer`
and `Configuration` have grown past it. Surface seeded from the Android RLHD facade (`android/dev-tools/gpu/
facade-src`, which compiled the same RLHD `@7dded59` green) since the device-native method **signatures** are
platform-agnostic; only the JNI binding differs.

## What's real vs pending

- **Compile-time (B0.1, DONE):** every method is declared so RLHD links. `stage-ios-zone-jar.sh` is GREEN — 304
  RLHD classes compile, 0 lwjgl leak.
- **Drop-in superset:** the **base** gpu plugin also compiles and runs against this facade, so
  `../../gpu-routec/stage-ios-gpu-jar.sh` stages this one by default (`FACADE_SRC`); `facade-native/` is only for
  a base-gpu-only jar.
- **Runtime (bound):** the `native` methods are bound by two `RegisterNatives` tables that `JVMLauncher.m` registers
  after `JNI_CreateJavaVM`: the base GL bridge (`../../gpu-routec/jni/routec_gl_jni.m`), then the RLHD delta on top
  of it (`../jni/routec_zone_jni.m`, skipped only in the L1 kill-test; its [README](../jni/README.md) lists what it
  covers). The OpenCL `cl*` methods stay unbound on purpose (no CL context on the Zone path).

At runtime the facade ships in the staged `client-shaded.jar`; `client-rlhd.jar` carries **no** `org/lwjgl` or
`rlawt` (one provider, all bridge-backed).
