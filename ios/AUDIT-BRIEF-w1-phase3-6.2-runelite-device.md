# W1 P3 §6.2 brief — real RuneLite buffer on device (the W1↔W2 join)

Self-contained brief for the next build. §6.0 (CTC activation) and §6.1 (headful Canvas + CTC
Graphics/fonts) are GREEN on device (see `ios/app/PHASE3-DEVICE-STATUS.md`,
`ios/AUDIT-RESULT-w1-phase3-device.md`). §6.2 runs the **real RuneLite headless client** on the
device JIT JVM and captures the engine's composited buffer — the join between W2 (desktop-proven
headless RuneLite) and W1 (device JVM + AWT backend).

## What's already proven (don't re-litigate)
- **Desktop:** real RuneLite boots window-less under CACIO CTC (no OS window), engine drives
  `Hooks.draw` ~42 fps with `nullGraphics=0`, and `HeadlessCapture` dumps `mainBufferProvider`
  (`BufferProvider.getPixels()`, toolkit-independent) to PNG — login screen AND, with tokens, a
  logged-in frame compositing a real plugin overlay. Harness: `ios/dev-tools/headless/`
  (`CacioHeadlessTest` + `run-headless-cacio.sh`). Classes `RuneLiteHeadless` / `HeadlessClientUI` /
  `HeadlessCapture` live in `runelite-client` and ship inside the shaded jar.
- **Device:** JIT HotSpot + embedding + signing + install proven; CTC activation
  (`UnsafePokeActivation`, no agent/attach) GREEN; headful Canvas → live `SunGraphics2D` off a
  `CTCGraphicsConfiguration`, Java2D + fonts render + blit (§6.1).

## The cut (cheapest-correct, login-screen FIRST)
Do **not** start with auth. First milestone: **boot real RuneLite to the live-OSRS login screen on
device and capture ≥1 `mainBufferProvider` PNG** (the desktop "experiment 2" cut, no tokens). That
isolates "the real client + gamepack + Java2D pipeline runs on the device JIT JVM" from the auth/
network-login variables. §6.2-ii (mint tokens → `JX_*` handoff → synthesized Play-Now click →
`LOGGED_IN` + overlay capture) follows only once the login screen renders on device.

## The device adaptation (small, well-bounded)
The desktop harness = `@ExtendWith(CacioExtension.class)` (byte-buddy, OUT for device) + JUnit +
`RuneLiteHeadless.main("--safe-mode")` + sleep. Device version:

1. **New entrypoint class `DeviceHeadlessBoot`** (compiled against the shaded jar): runs the §6.0
   activation, then boots the client. Wiring:
   - Refactor `UnsafePokeActivation` to expose a `public static void activate()` (steps 1–7 of the
     current `main`, minus the trailing `AwtActivation.main`). Keep `main` = `activate()` +
     `AwtActivation.main` so §6.0/§6.1 stay byte-identical (their GREEN must not regress).
   - `DeviceHeadlessBoot.main`: `UnsafePokeActivation.activate();` then
     `RuneLiteHeadless.main(new String[]{"--safe-mode"});` then keep-alive (the launcher already
     keeps the VM up; HotSpot/EDT threads are non-daemon).
2. **Classpath on device:** the **shaded jar** (`client-1.12.28-SNAPSHOT-shaded.jar`, 29 MB) +
   the CACIO classes (already folded into `probes.jar`) + `DeviceHeadlessBoot`/`UnsafePokeActivation`.
   Simplest: bundle the shaded jar as an app resource and set
   `-Djava.class.path=<shaded.jar>:<probes.jar>`. (Confirm the shaded jar does NOT already contain
   cacio — desktop puts cacio on a separate cp entry, so it does not; keep both.)
3. **System properties** (mirror `run-headless-cacio.sh`): `-Drunelite.headless.capture.dir=<Documents>`,
   `-Drunelite.headless.capture.interval=50`, `-Dheadless.runSeconds=…`, plus a **writable RuneLite
   home** for the gamepack/cache download (`-Duser.home=<Documents>` or the runelite home property —
   verify which the client honors on a sandboxed iOS home). Drop `-Djdk.attach.allowAttachSelf=true`
   (unused without the agent).
4. **JPMS flags:** the device `JVMLauncher.m` set already equals `run-headless-cacio.sh`'s set
   (java.awt{,.peer}, sun.awt{,.image,.event,.datatransfer}, sun.java2d, java.awt.dnd.peer,
   sun.security.action; opens java.util, java.awt, sun.java2d, java.lang.reflect). Reuse as-is; add
   only if a device run throws `InaccessibleObjectException` naming a new package.
5. **Memory:** set an explicit `-Xmx` (RuneLite wants ~256–512 MB). Depends on the
   `increased-memory-limit` entitlement (intact on the local-`devicectl`/paid-profile install; would
   need checking under a SideStore re-sign). Keep `-XX:ReservedCodeCacheSize=64m` (the brk#0x69 RX
   reservation) — but a full client may want more code cache than 64 MB; flag as a tuning knob.

## Risks / what breaks first (watch these in the device log)
- **Memory / jetsam:** the full client + gamepack is far heavier than the probes; first run may be
  killed (SIGKILL, no Java trace). Mitigate with `-Xmx`, confirm `increased-memory-limit`, watch for
  jetsam in the device console.
- **Gamepack download + cache writability:** the client fetches the injected client at boot and
  writes a cache; iOS sandbox home must be writable and the property the client reads must point
  there. Failure mode: cache/IO exception, "Cache is ready" never logged.
- **JIT warmup cost:** Phase-1 found cold/OSR loops run ~interpreted under StikDebug; client BOOT is
  one big cold path → expect a slow first boot, then method-level JIT for the game loop (acceptable).
- **Native deps:** `--safe-mode` disables the GPU plugin (no OpenGL native). Confirm nothing else in
  the boot path `System.loadLibrary`s a native absent on device (the gamepack is pure-Java Java2D).
- **Activation ordering:** `activate()` MUST precede any RuneLite AWT touch (it installs toolkit/GE
  before `RuneLite.main`). Same ordering the desktop CacioExtension guaranteed.
- **Network to live OSRS** from the device JVM (login screen needs the gamepack + world list).

## Success verdict (greppable + md5-gateable, same discipline as §6.0/§6.1)
- `[headless] canvas …×… displayable=true`, `[headless-capture] … nullGraphics=0`, ≥1
  `frame-*.png` in Documents, no `Exception`/`redefinition`/jetsam. Pull the PNG + log via `devicectl`
  (local) and md5-gate a verdict line, e.g. `RUNELITE-DEVICE = LOGIN-SCREEN RENDERED`.
- Eyeball the PNG: it should be the **live-OSRS login screen** rendered by the real engine into our
  buffer, on the device JIT JVM — the W1↔W2 join, on device.

## Open question for the review
Bundle the 29 MB shaded jar into the app (doubles bundle size, simplest) vs. host it and have the
client/JVM load it from Documents (smaller app, extra plumbing)? Recommend **bundle** for the first
green, optimize later. Also: is `-Xmx` + `increased-memory-limit` enough headroom for the full client
under the iOS jetsam limit on a 17 Pro Max, or does the gamepack scene push past it?
