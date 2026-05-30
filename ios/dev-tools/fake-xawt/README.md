# fake-xawt — providing libawt_xawt for the iOS jre21

W1 P3 §6.1 artifact, surfaced by the §6.0 device confirm. Device rounds (2026-05-30):

## Round 1 — the library is missing
JIT works (`[JIT26] Got JIT mapping … from debugger`). The §6.0 probe threw on the first touch
of a Toolkit class:
```
UnsatisfiedLinkError: Can't load library: …/jre/lib/libawt_xawt.dylib
  at java.awt.Toolkit.loadLibraries → initStatic → <clinit>
```
This jre21 is an xawt-style build: `java.awt.Toolkit.<clinit>` does `System.loadLibrary("awt_xawt")`,
and that dylib isn't in the image (it ships `libawt` + `libawt_headless` only).

## Round 2 — JNI-only stub loads, but the native ABI is missing
A minimal stub (`awtxawt_stub.c`, JNI_OnLoad only) made the load succeed and the probe got much
deeper — past Toolkit + CTCToolkit — then threw:
```
UnsatisfiedLinkError: 'void java.awt.Font.initIDs()'
  at java.awt.Font.initIDs (Native Method)
  at java.awt.Font.<clinit>
  at sun.java2d.SunGraphicsEnvironment.<init>
  at CTCGraphicsEnvironment.<init>:35      // Class.forName(CTCGraphicsEnvironment), main:67
```
The toolkit backend lib must provide the core AWT JNI natives (`Java_java_awt_Font_initIDs`, …)
that classes call during init. In headless mode `libawt_headless` provides them (that is why
GlyphSmoke's fonts worked); with the empty stub loaded as `libawt_xawt` and headless NOT loaded,
`Font.initIDs` is unresolved.

## Working fix — use the headless backend as libawt_xawt
`build-libawt_xawt.sh` copies the image's own `libawt_headless.dylib` → `libawt_xawt.dylib`. The
headless backend is a complete, on-device-proven AWT backend, so it carries the full native ABI
(Font/Toolkit/etc. initIDs and the rest) in one shot. CTC replaces the toolkit at the Java level,
so the headless peers are never actually driven. `awtxawt_stub.c` is kept as the record of why a
JNI-only stub is insufficient.

## Next outcome to expect (round 3)
With the native ABI present, `CTCGraphicsEnvironment.<init>` / `SunGraphicsEnvironment` init should
proceed; the probe then does the Unsafe GE poke, which forces `GraphicsEnvironment$LocalGE.<clinit>`
→ the iOS `PlatformGraphicsInfo.createGE()` (the original §6.0 device unknown). So the next run
reaches either `AWT-ACTIVATION = CTC ACTIVE`, or a new failure to diagnose. The `.dylib` is not
committed (lands in the gitignored `jre/`); regenerate with `build-libawt_xawt.sh`.
