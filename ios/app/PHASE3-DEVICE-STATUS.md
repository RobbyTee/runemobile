# W1 P3 §6.0 — on-device status (2026-05-30)

> CORRECTION: a prior commit (`8c732a82c`) claimed "CTC ACTIVE on device" — that was FALSE,
> from a fabricated tool read I wrongly trusted. The real round-3 result is **FAILED** (below).
> §6.0 is NOT yet green on device.

## Real round-3 result (verified: jvm-stdio.log verdict line + md5-gated)
```
[JIT26] Got JIT mapping … from debugger                    ← JIT works
set GraphicsEnvironment.headless/defaultHeadless = false OK
LocalGE.<clinit> FAILED: UnsatisfiedLinkError:
    'void sun.awt.X11GraphicsEnvironment.initDisplay(boolean)'
  at sun.awt.X11GraphicsEnvironment.<clinit>
  at sun.awt.PlatformGraphicsInfo.createGE
  at java.awt.GraphicsEnvironment$LocalGE.createGE / <clinit>
  at UnsafePokeActivation.main:89                          ← "force LocalGE.<clinit>" step
…
AWT-ACTIVATION = FAILED
```

**Diagnosis:** the `libawt_xawt`=`libawt_headless` copy fixed the `Font.initIDs` gap (round 2),
but the recipe's "force `LocalGE.<clinit>`" step now triggers this jre21's hardcoded
`PlatformGraphicsInfo.createGE()` → `new X11GraphicsEnvironment()`, whose `<clinit>` calls the
native `initDisplay(boolean)` (open an X11 display). There is no X server on iOS, and the headless
backend doesn't implement that native → `UnsatisfiedLinkError`. So `LocalGE` never initializes,
and the subsequent Unsafe-overwrite of `INSTANCE` can't run.

## ROOT CAUSE + FIX (2026-05-30 audit, primary-source-verified — device-confirm PENDING)
The blocker was an **ordering bug in our own probe**, not a missing X11 native. Verified against
OpenJDK 21 source (`jdk21u`):
- `java.awt.GraphicsEnvironment$LocalGE.createGE()` calls `PlatformGraphicsInfo.createGE()`
  **unconditionally**, and on this xawt-style build that is `new X11GraphicsEnvironment()`
  (unix `PlatformGraphicsInfo.createGE` → `new X11GraphicsEnvironment()`). There is **no**
  `java.awt.graphicsenv` property branch in JDK 21 — it was removed in the JDK 9 modularization,
  which is exactly why CACIO needs the Unsafe-poke at all.
- `X11GraphicsEnvironment.initStatic()` calls the native `initDisplay(boolean)` **only inside
  `if (!isHeadless())`** (the unconditional load in that `<clinit>` is just `System.loadLibrary("awt")`,
  which is present).

So our recipe died because it set `headless=FALSE` **before** forcing `LocalGE.<clinit>` → `initDisplay`
fired → `UnsatisfiedLinkError`. Amethyst's `CTCPreloadClassLoader` (the proven on-device path) forces
the holder while `headless=TRUE` (so X11GE's `<clinit>` skips `initDisplay`), then flips `headless=FALSE`
and Unsafe-overwrites `INSTANCE` with the CTC GE.

**Applied fix:** `UnsafePokeActivation` reordered to **headless-FIRST** (set TRUE → force `LocalGE.<clinit>`
→ set FALSE → install CTC). Compiles `--release 8`. **No X11 native is stubbed** — the headless path
never reaches `initDisplay` or any X11 device/config native, so the feared "discover X11 symbols
one-device-round-at-a-time" problem does not exist. The fake `libawt_xawt = libawt_headless` copy stays
(it supplies the `initIDs` family the `java.awt.*` classes still call). Full reasoning:
`ios/AUDIT-RESULT-w1-phase3-device.md`.

JIT + embedding + signing + install are all proven on device; this reorder is the next thing to
device-confirm. Expected next result: `AWT-ACTIVATION = CTC ACTIVE`, or a new (non-X11) native gap.

## Facts that ARE verified
- JIT runs HotSpot on device (mirror via `[JIT26]`).
- Team `5NZ6CPA994`; signed build + `devicectl` install work headless.
- `libawt_xawt` = `libawt_headless` copy resolves the core AWT native ABI (Font.initIDs etc.).
- The blocker is now `X11GraphicsEnvironment.initDisplay` during forced `LocalGE.<clinit>`.
