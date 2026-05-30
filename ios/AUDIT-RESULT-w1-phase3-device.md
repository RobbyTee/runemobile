# Audit result — W1 P3 device activation (2026-05-30)

Answers the four questions in `AUDIT-ASK-w1-phase3-device.md`. **Verdict: the device blocker was an
ordering bug in our own `UnsafePokeActivation`, not a missing X11 native.** Fix applied (a reorder).
**CONFIRMED GREEN ON DEVICE (round 4, 2026-05-30): `AWT-ACTIVATION = CTC ACTIVE`** — `jvm-stdio.log`
pulled via `devicectl`, md5-gated bash verdict (`MEGAVERDICT = ALL_GREEN_AS_EXPECTED`), no
`FAILED`/`UnsatisfiedLinkError`/`initDisplay`. Verbatim log in `ios/app/PHASE3-DEVICE-STATUS.md`.
Everything below is from primary source — fetched JDK 21 source + Amethyst/PojavLauncher code — not
from notes.

## The three primary-source facts that settle it

1. **`java.awt.GraphicsEnvironment` (jdk21u, share):** `getLocalGraphicsEnvironment()` returns
   `LocalGE.INSTANCE`; `LocalGE.createGE()` is:
   ```java
   GraphicsEnvironment ge = PlatformGraphicsInfo.createGE();   // unconditional
   if (isHeadless()) ge = new HeadlessGraphicsEnvironment(ge);
   return ge;
   ```
   There is **no `java.awt.graphicsenv` property branch**. (Removed in the JDK 9 modularization —
   the reason CACIO must Unsafe-poke instead of just setting the property as it did on Java 8.)
   The spike's comment claiming the property is ignored on JDK 21 was **correct**.

2. **unix `sun.awt.PlatformGraphicsInfo` (jdk21u):** `createGE()` returns `new X11GraphicsEnvironment()`
   **unconditionally**. So both Amethyst and we *do* construct an X11GE when forcing the holder — the
   research claim that "X11GE is never constructed / it's a Cocoa build" is false (our own device
   round-1 proved the JRE does `System.loadLibrary("awt_xawt")`, and Amethyst ships a fake
   `libawt_xawt.dylib` — both are X11/Linux-derived builds).

3. **unix `sun.awt.X11GraphicsEnvironment.initStatic()` (jdk21u):**
   ```java
   System.loadLibrary("awt");          // unconditional — present in the image
   ...
   if (!isHeadless()) {
       ...
       initDisplay(glxRequested);      // the native that has no X server / no impl on iOS
   }
   ```
   `initDisplay` is gated on `!isHeadless()`.

**Therefore:** our recipe set `headless=FALSE` *before* forcing `LocalGE.<clinit>`, so `initDisplay`
fired → `UnsatisfiedLinkError` (device round-3). Amethyst's `CTCPreloadClassLoader` forces the holder
while `headless=TRUE` (X11GE `<clinit>` skips `initDisplay`), then flips `headless=FALSE` and
Unsafe-overwrites `INSTANCE` with the CTC GE. Our `UnsafePokeActivation` was already a faithful
reimplementation of `CTCPreloadClassLoader` in every respect **except this ordering**.

## Answers

**Q1 — keep Unsafe-poke + stub the X11 natives? NO.** Whack-a-mole *and unnecessary*. Amethyst stubs
**no** `X11GraphicsEnvironment` native (no `initDisplay`, no `X11GraphicsDevice`/`X11GraphicsConfig`).
Under the headless-first ordering the X11GE `<clinit>` never reaches `initDisplay`, and the throwaway
GE is discarded the instant `INSTANCE` is overwritten. Stubbing `initDisplay` would build a
half-initialized native GE for nothing.

**Q2 — pivot to `CTCPreloadClassLoader`? It is not a pivot — we already reimplemented it.** Our probe
does the same things (force toolkit field, null/reset `SurfaceManagerFactory`, Unsafe-overwrite
`LocalGE.INSTANCE` with `CTCGraphicsEnvironment`), with no instrumentation/self-attach — the
device-compatible path. The single material divergence was the headless ordering, now fixed. Minimum
to reproduce Amethyst exactly: headless-first ordering (done) + the JPMS flags we already pass
(`--add-exports java.desktop/{sun.awt,sun.awt.image,sun.java2d,java.awt,...}=ALL-UNNAMED`,
`--add-opens` for `java.awt`/`sun.java2d`/`java.lang.reflect`) + `-Xbootclasspath/a` cacio jars +
fake `libawt_xawt`. We have all of these.

**Q3 — third lever? YES, and it is the fix:** initialize `LocalGE` under `headless=TRUE` so X11GE's
`<clinit>` skips `initDisplay`, then set `headless=FALSE` and Unsafe-overwrite `INSTANCE` with CTC. No
instrumentation, no live display, no native stubbing. (This *is* Amethyst's lever.)

**Q4 — fake `libawt_xawt`: re-export headless, sound?** Functional and on-device-proven (it cleared
`Font.initIDs`), just heavier than Amethyst's hand-written ~45 no-op `initIDs` + UNIXToolkit/GTK-probe
stub. **Keep the headless copy.** The audit-ask's fear of an open-ended X11 symbol set **evaporates**:
once we init headless, `initDisplay` and all X11 device/config natives are never called; the only
unconditional load in X11GE `<clinit>` is `"awt"` (present). The symbol set is closed, not
discovered-per-round. (If a future round throws, expect a font-config or CTC-peer native — **not** an
X11 display native.)

## What changed in the tree
- `ios/dev-tools/awtsmoke/UnsafePokeActivation.java` — reordered to headless-FIRST (set TRUE → force
  `LocalGE.<clinit>` → set FALSE → install CTC). Header comment rewritten with the verified reasoning.
  Compiles `--release 8` against the cacio jars.
- `ios/app/PHASE3-DEVICE-STATUS.md` — root cause + fix recorded; "two candidate fixes" superseded.

## Outcome (done)
Rebuilt `probes.jar`, signed+installed via `devicectl`, ran on device under StikDebug
(`runelite-jit.js`). Round-4 log pulled via `devicectl` → md5-gated bash verdict GREEN. §6.0 device
activation CLOSED. **Next: §6.1 AwtSmoke** — realize a Frame+Canvas under the now-active CTC GE,
assert `getGraphics()` non-null, read back CTC pixels + fonts on device.
