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

## Two candidate fixes (next experiments)
1. **Stub the X11GraphicsEnvironment natives** in the fake `libawt_xawt` (at least
   `Java_sun_awt_X11GraphicsEnvironment_initDisplay`, plus whatever else its `<clinit>` calls):
   make `<clinit>` survive so `createGE()` returns a throwaway X11GE; we immediately overwrite
   `INSTANCE` with the CTC GE anyway. Iterate on missing symbols. (headless copy + added X11 stubs.)
2. **Avoid forcing the platform `createGE()` entirely** — rework the activation so we never trigger
   `LocalGE.<clinit>` via the platform path. This is harder (the holder runs `createGE()` on first
   init) and may need the `CTCPreloadClassLoader` approach Amethyst uses. Worth checking how
   Amethyst's fake libawt_xawt handles `initDisplay` before hand-stubbing blindly.

JIT + embedding + signing + install are all proven on device; the remaining work is making the
iOS jre21's GE init survivable so CTC can be slotted in.

## Facts that ARE verified
- JIT runs HotSpot on device (mirror via `[JIT26]`).
- Team `5NZ6CPA994`; signed build + `devicectl` install work headless.
- `libawt_xawt` = `libawt_headless` copy resolves the core AWT native ABI (Font.initIDs etc.).
- The blocker is now `X11GraphicsEnvironment.initDisplay` during forced `LocalGE.<clinit>`.
