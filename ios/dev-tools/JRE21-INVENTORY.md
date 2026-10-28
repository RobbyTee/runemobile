# JRE 21 (iOS arm64) — fetched, verified, checksum-pinned (2026-05-29)

The on-device HotSpot for Phase 1 / W1. **Settles Claim 2 locally** (the artifact exists, is a JIT HotSpot —
not Zero — and exposes the embedding entry point). The binary itself is **gitignored** (`dev-tools/jre/`,
~24 MB); this doc is the tracked record so a fresh machine can re-fetch + re-verify.

## Provenance
- **URL:** `https://crystall1ne.dev/cdn/amethyst-ios/jre21-ios-aarch64.zip` (Amethyst's CDN, referenced from its
  `Makefile` `jre:` target — alongside `jre8-` and `jre17-ios-aarch64.zip`). **Not** a durable Pojav GitHub
  release → pin the checksum.
- **SHA-256 (zip):** `2c405fcc8c1cf95773617a93a2b090192191c871874cf1408f7a52500d8f0c71`
- **Size / date:** 24,072,536 bytes; CDN `last-modified` 2025-09-16. Inner tarball:
  `jre21-ios-arm64-20250915-release.tar.xz`.

## Re-fetch + verify (per machine)
```sh
cd ios/dev-tools/jre   # gitignored
curl -fSL -o jre21-ios-aarch64.zip 'https://crystall1ne.dev/cdn/amethyst-ios/jre21-ios-aarch64.zip'
shasum -a 256 jre21-ios-aarch64.zip   # must equal the pin above
unzip -o jre21-ios-aarch64.zip && tar -xf jre21-ios-arm64-*-release.tar.xz
cd ../../.. && ios/dev-tools/patch-jre-txm.sh # REQUIRED: see below
ios/dev-tools/fake-xawt/build-libawt_xawt.sh   # REQUIRED: adds lib/libawt_xawt.dylib (see below)
```

## REQUIRED patch — TXM brk #0x69 gates (`patch-jre-txm.sh`)
On a sandboxed iOS app, libjvm's `get_debug_jit_mapping` skips the debugger `brk #0x69` code-cache path:
`DeviceRequiresTXMWorkaround()` can't read `/private/preboot/.../TrustedExecutionMonitor.img4` (sandbox) and
returns 0, and the `MirrorMappedCodeCache` global also reads 0 here, so it falls back to
`mmap(PROT_READ|PROT_EXEC)`, which is **non-executable on TXM** → HotSpot crashes generating code. On iOS 27 the
TXM probe itself crashes (SIGSEGV in `readdir()`) before any Java runs. `dev-tools/patch-jre-txm.sh` NOPs
**three** instructions in `get_debug_jit_mapping`, so it always calls `BreakGetJITMapping`:

| File offset | Original | What it is |
|---|---|---|
| `0x81fc80` | `b.ne` | the `MirrorMappedCodeCache` flag gate |
| `0x81fc88` | `bl DeviceRequiresTXMWorkaround` | the TXM probe call (crashes on iOS 27; its result is unused once both gates are gone) |
| `0x81fc8c` | `cbz w0` | the TXM result gate |

Each site's original bytes are checked before it is patched (`1f 20 03 d5` = `nop`); the script is idempotent.
The app build phase (`resign-jre.sh`) re-signs the patched dylib.

## REQUIRED addition — `libawt_xawt.dylib` (`fake-xawt/`)
This jre21 is an xawt-style build: `java.awt.Toolkit` loads `libawt_xawt`, which the image doesn't ship.
`dev-tools/fake-xawt/build-libawt_xawt.sh` builds one that re-exports `libawt_headless` and adds no-op stubs for
the headful AWT `initIDs` natives, and installs it into `jre/lib/` ([README](fake-xawt/README.md)).

## Layout (after extract)
`bin/java` (52 KB launcher) · `conf/` · `legal/` · `lib/` with `lib/libjli.dylib` (the JLI entry) and
`lib/server/libjvm.dylib` (18 MB HotSpot). Standard JRE module set (`release` MODULES includes `java.desktop`).

## Verification results (this machine, 2026-05-29)
| Check | Command | Result |
|---|---|---|
| Version | `cat release` | `JAVA_VERSION="21.0.8"`, `OS_ARCH="aarch64"`, `OS_NAME="Darwin"` |
| Arch | `file lib/server/libjvm.dylib` | Mach-O arm64 |
| **iOS platform** | `vtool -show-build lib/server/libjvm.dylib` | **`platform IOS`, minos 12.0, sdk 26.0** |
| **HotSpot not Zero** | `strings libjvm.dylib \| grep ...` | `OpenJDK 64-Bit Server VM` + `HotSpot 64-Bit Tiered Compilers`; **no Zero-VM marker** |
| **JIT compilers** | `strings -wE 'C1\|C2'` | C1 ×8, C2 ×5 (tiered) |
| **Embedding entry** | `nm -gU lib/libjli.dylib` | exports **`_JLI_Launch`** (+ `_JLI_InitArgProcessing`) |
| JNI entry | `nm -gU lib/server/libjvm.dylib` | exports `_JNI_CreateJavaVM` |
| Build | strings build-id | `for bsd-aarch64`, built 2025-09-16 with Apple clang 17.0.0 |
| **Signing** | `codesign -dvv lib/server/libjvm.dylib` | embedded CodeDirectory, **"no signature" (ad-hoc/linker-signed)**, TeamIdentifier not set |
| Linkage | `otool -L lib/server/libjvm.dylib` | libSystem, libc++, **CoreFoundation** only (all present on iOS) |

## What this does and does NOT settle
- **Settles (Claim 2):** a JDK 21 iOS-arm64 **JIT HotSpot** exists, downloads, and exposes `JLI_Launch` — we do
  not need to build the JVM ourselves. 17/8 are the fallbacks on the same CDN.
- **Still device-only (Claims 1, 3):** the **ad-hoc signature** is exactly why loading it inside an app-signed
  (free-cert AltStore) process needs Amethyst's `dyld_bypass_validation` — unproven on 26.5/TXM until we try it.
  And whether **StikDebug grants JIT** to our process on this TXM phone is the oracle prong. Neither is settled
  by inspection; both ride the device spike.
- **Since settled on device:** re-signing the JRE with the app's identity (`app/scripts/resign-jre.sh`) is enough
  to load it, with no dyld bypass, and the StikDebug attach grants JIT. The app embeds the VM with
  `JNI_CreateJavaVM`, not `JLI_Launch`.
