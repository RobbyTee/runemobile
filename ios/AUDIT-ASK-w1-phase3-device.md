# Audit ask — W1 P3 device activation (2026-05-30)

Companion to `AUDIT-BRIEF-w1-phase3-awt.md`. The §6.0 spike has now RUN ON DEVICE and surfaced a
concrete blocker that questions the locked approach. This is the focused ask for the next review.
Self-contained; a reviewer need not have the repo loaded. Evidence files:
`ios/app/PHASE3-DEVICE-STATUS.md`, `ios/dev-tools/fake-xawt/README.md`, `ios/dev-tools/awtsmoke/`.

## Verified on device (iPhone 17 Pro Max, iOS 26.5, JIT)
- JIT runs HotSpot (`[JIT26] Got JIT mapping from debugger`); signing (team `5NZ6CPA994`) +
  `devicectl` install work headless.
- §6.0 **desktop** is solved: a no-instrumentation recipe (`UnsafePokeActivation`, mode e) reaches
  `AWT-ACTIVATION = CTC ACTIVE` on OpenJDK 21 — null `SurfaceManagerFactory` before each
  `setInstance` caller, then **Unsafe-overwrite the static-final `GraphicsEnvironment$LocalGE.INSTANCE`**
  with the CTC GE.
- §6.0 **device** is NOT solved. Three fake-xawt rounds:
  1. `libawt_xawt.dylib` absent → `Toolkit.<clinit>` `UnsatisfiedLinkError` (this jre21 is an
     xawt-style build: `System.loadLibrary("awt_xawt")`). Added a stub.
  2. Stub loaded; got past Toolkit+CTCToolkit; then `UnsatisfiedLinkError: java.awt.Font.initIDs()`
     (toolkit backend supplies core AWT natives). Fixed: `libawt_xawt` = copy of `libawt_headless`.
  3. **Current blocker:** the recipe's "force `LocalGE.<clinit>`" step runs the platform
     `PlatformGraphicsInfo.createGE()` → `new X11GraphicsEnvironment()`, whose `<clinit>` calls the
     native `sun.awt.X11GraphicsEnvironment.initDisplay(boolean)` (open an X server). No X11 on iOS,
     headless backend lacks the native → `UnsatisfiedLinkError`; `LocalGE` never inits.

## The core question for the audit
The no-instrumentation **Unsafe-poke FORCES the platform `createGE()`** (to initialize the holder
before overwriting `INSTANCE`). On this iOS jre21 that path builds `X11GraphicsEnvironment` and
fails on a native that can't exist on iOS. Amethyst — which runs this exact CACIO/jre21 stack on
iOS — instead uses **`CTCPreloadClassLoader` + `-Xbootclasspath/a` + a fake `libawt_xawt`** to install
CTC *without* ever triggering the platform `createGE()`.

**Adjudicate (verdict + reasoning each):**
1. **Keep the Unsafe-poke and stub the `X11GraphicsEnvironment` natives** in the fake `libawt_xawt`
   (`initDisplay`, and whatever else its `<clinit>`/construction calls) so `createGE()` returns a
   throwaway X11GE that init's without crashing, then overwrite `INSTANCE` with CTC. Risk: unknown
   number of X11 natives, discovered one device-round at a time; the throwaway GE must not touch a
   real display. Is this robust, or a whack-a-mole that ends in a half-initialized native GE?
2. **Pivot to reproducing Amethyst's `CTCPreloadClassLoader` path** (avoid forcing `createGE()`
   entirely). Is this the right call given it's the proven on-device approach? What's the minimum
   we must reproduce (classloader + bootclasspath + which JPMS flags + the fake-xawt symbol set)?
   GPLv3 → patterns only.
3. Is there a **third lever** to make `getLocalGraphicsEnvironment()` return CTC without either
   forcing the platform `createGE()` or instrumentation (e.g. seeding `LocalGE.INSTANCE` before the
   holder's `<clinit>` and suppressing the `<clinit>` clobber)?
4. For the fake `libawt_xawt`: is "re-export `libawt_headless` + add X11 no-op stubs" sound, or
   should it be built differently? What is the **complete** symbol set this jre21 needs (so we stop
   the one-per-device-round discovery)?

## Why this is worth auditing now
The device evidence directly challenges the locked "no-instrumentation Unsafe-poke" choice for the
device (it fights the platform GE init). An independent view on Unsafe-poke vs CTCPreloadClassLoader
— before sinking more device round-trips into stubbing X11 natives — is the cheapest way to avoid
building down the wrong path.

> Process note for whoever runs this: the session that produced these findings had a corrupted shell
> that fabricated/duplicated tool output, which caused several false "GREEN" claims (all corrected).
> The device facts here are from the user's screenshots + the pulled `jvm-stdio.log`, verified by
> md5-gated checks. Treat the desktop `AWT-ACTIVATION = CTC ACTIVE` and the device `= FAILED
> (X11GraphicsEnvironment.initDisplay)` as the trustworthy anchors.
