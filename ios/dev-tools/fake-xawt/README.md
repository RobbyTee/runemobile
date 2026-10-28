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

## Round 2 fix — use the headless backend as libawt_xawt
The first fix copied the image's own `libawt_headless.dylib` → `libawt_xawt.dylib`. The headless backend
is a complete, on-device-proven AWT backend, so it carries the native ABI the `<clinit>`s need
(Font/Toolkit/etc. initIDs). CTC replaces the toolkit at the Java level, so the headless peers are never
actually driven. `awtxawt_stub.c` is kept as the record of why a JNI-only stub is insufficient.

## Round 3 — what the copy was expected to unlock
With the native ABI present, `CTCGraphicsEnvironment.<init>` / `SunGraphicsEnvironment` init should
proceed; the probe then does the Unsafe GE poke, which forces `GraphicsEnvironment$LocalGE.<clinit>`
→ the iOS `PlatformGraphicsInfo.createGE()` (the original §6.0 device unknown). So the next run
reaches either `AWT-ACTIVATION = CTC ACTIVE`, or a new failure to diagnose. The `.dylib` is not
committed (lands in the gitignored `jre/`); regenerate with `build-libawt_xawt.sh`.

## Round 4 — diagnosed: do NOT stub X11 natives (2026-05-30 audit)
Round 3 failed at `X11GraphicsEnvironment.initDisplay` — but the audit (checked against the jdk21u sources)
showed that is an **ordering bug in the probe, not a native gap**. `X11GraphicsEnvironment.initStatic()` calls
`initDisplay` only inside `if (!isHeadless())`; the probe was setting `headless=FALSE` before forcing
`LocalGE.<clinit>`. Fix (applied in `UnsafePokeActivation`): force the holder while `headless=TRUE` (X11GE
skips `initDisplay`), then flip headless off and overwrite `INSTANCE` with CTC — exactly Amethyst's
`CTCPreloadClassLoader` ordering. **So this fake lib does NOT need `initDisplay` or any X11 device/config
symbol** (the headless path never reaches them); `libawt_headless` already carries the `initIDs` family that
`java.awt.*` `<clinit>` calls.

## Current build — re-export libawt_headless plus headful `initIDs` stubs
The next step (W1 P3 §6.1) created real headful components (`new Frame`, `new Canvas`), and
`java.awt.Component.<clinit>` threw `UnsatisfiedLinkError: 'void java.awt.Component.initIDs()'`: the headful
`initIDs` family is in neither `libawt` nor `libawt_headless` on this image. So `build-libawt_xawt.sh` no longer
copies a file. It compiles `xawt_fake.c` into an arm64 iphoneos `libawt_xawt.dylib` that:
- **re-exports `libawt_headless`** (`-reexport_library`, `-rpath @loader_path`), keeping its real
  Font/FontDescriptor/PlatformFont `initIDs`;
- adds **no-op stubs** for the headful `initIDs` absent from both libraries (`Component`, `Container`, `Window`,
  `Frame`, …). That is safe because CACIO's pure-Java peers never run the native peer code that would read
  those field IDs. Natives that `libawt` already provides are deliberately not stubbed, so they aren't shadowed.

The script copies the result into `../jre/lib/`, where the app's `resign-jre.sh` build phase signs it.
