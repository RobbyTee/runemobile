# fake-xawt — minimal libawt_xawt stand-in for the iOS jre21

W1 P3 §6.1 artifact, surfaced by the §6.0 device confirm.

## The device finding (2026-05-30, verified from on-device log)
JIT worked end-to-end (`[JIT26] Got JIT mapping … from debugger`, HotSpot ran our code).
The §6.0 probe (`UnsafePokeActivation`) then threw on the **first touch of a Toolkit class**:

```
Exception in thread "main" java.lang.UnsatisfiedLinkError: Can't load library:
  …/HelloShell.app/jre/lib/libawt_xawt.dylib
    at java.desktop/java.awt.Toolkit.loadLibraries
    at java.desktop/java.awt.Toolkit.initStatic
    at java.desktop/java.awt.Toolkit.<clinit>
    at java.base/java.lang.Class.forName0
    at UnsafePokeActivation.main(UnsafePokeActivation.java:66)   // Class.forName(CTCToolkit)
```

So this jre21 is an **xawt-style (non-headless) build**: `java.awt.Toolkit.<clinit>` →
`loadLibraries()` does `System.loadLibrary("awt_xawt")`, and that dylib isn't in the image
(we ship `libawt` + `libawt_headless`, not `libawt_xawt`). It fails *before* CTC can install —
so §6.0 activation can't be exercised in isolation; the fake-xawt stub is a prerequisite.
This is exactly the brief's anticipated risk (§6a "what breaks first: UnsatisfiedLinkError for
libawt_xawt"), just hit earlier in the sequence than assumed.

## The stub (experiment 1 — "just load")
CTC supplies pure-Java peers, so the real X11 natives are never called; the file only needs to
**load**. `awtxawt_stub.c` is a loadable dylib whose `JNI_OnLoad` returns `JNI_VERSION_1_8`.
Build + install into the jre with `./build-libawt_xawt.sh`, then rebuild/reinstall the app.

Expected outcomes on the next device run:
- **`AWT-ACTIVATION = CTC ACTIVE`** → "just load" was enough; §6.0 done on device.
- **A NEW `UnsatisfiedLinkError` naming a specific symbol** (e.g. some `Java_sun_awt_X11_*`
  `initIDs`) → AWT calls into the lib during init; add a no-op for that symbol and rebuild.
  Iterate until activation passes (this is how we discover the minimal symbol set).

The `.dylib` itself is not committed (it lands in the gitignored `jre/`); regenerate it from
`awtxawt_stub.c`. If iteration is needed, prefer adding named no-op symbols here over guessing.
