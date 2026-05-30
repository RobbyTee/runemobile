# W1 P3 §6.0 — on-device confirm status (2026-05-30)

## Signing / install (verified, artifact-inspected)
- **Team = `5NZ6CPA994`** (OU of the `Apple Development: jordansimps@me.com` cert). NOT
  `393QD89H88` (that CN parenthetical is a personal id; using it gave "No Account for Team").
  `project.yml` pins `DEVELOPMENT_TEAM: 5NZ6CPA994`.
- Signed device build SUCCEEDS via `xcodebuild -allowProvisioningUpdates DEVELOPMENT_TEAM=5NZ6CPA994`
  (embedded profile + `codesign --verify` pass + `get-task-allow`); installed on the iPhone 17
  Pro Max and confirmed with `devicectl device info apps`.

## First device run result (the §6.0 confirm) — verified from the on-device log
JIT works end-to-end: `[JIT26] Got JIT mapping … from debugger`, HotSpot ran our code. The probe
then threw on the first touch of a Toolkit class:

```
java.lang.UnsatisfiedLinkError: Can't load library: …/jre/lib/libawt_xawt.dylib
  at java.desktop/java.awt.Toolkit.loadLibraries / initStatic / <clinit>
  at UnsafePokeActivation.main(UnsafePokeActivation.java:66)   // Class.forName(CTCToolkit)
```

**Diagnosis:** this jre21 is an xawt-style (non-headless) build — `java.awt.Toolkit.<clinit>`
does `System.loadLibrary("awt_xawt")`, and `libawt_xawt.dylib` isn't in the image. The load
fails before CTC can install, so §6.0 can't be tested without a libawt_xawt present. This is the
brief's anticipated fake-`libawt_xawt` gap (§6a), hit earlier than the sequencing assumed —
NOT the `LocalGE.<clinit>`/`createGE` unknown I had flagged.

## Fix in flight — fake-xawt stub (experiment 1)
Built a minimal arm64 `libawt_xawt.dylib` (`dev-tools/fake-xawt/awtxawt_stub.c`, JNI_OnLoad
only — CTC's pure-Java peers mean the real X11 natives are never called, so it just needs to
LOAD), placed in `jre/lib/`, rebuilt + reinstalled the signed app.

## NEXT — re-run on device (StikDebug ritual)
1. StikDebug: assign `ios/app/stikdebug/runelite-jit.js` to HelloShell, attach.
2. Launch → wait `JIT: CS_DEBUGGED ✓` → tap **Run AWT activation (§6.0)**.
3. Pull the log:
   `xcrun devicectl device copy from --device 00008150-001A18460C00401C \
      --domain-type appDataContainer --domain-identifier dev.audaro.helloshell \
      --source Documents/jvm-stdio.log --destination ./jvm-stdio.log`
   - `AWT-ACTIVATION = CTC ACTIVE` → §6.0 done on device.
   - A NEW `UnsatisfiedLinkError` naming a symbol → add a no-op for it in `awtxawt_stub.c`,
     rebuild via `dev-tools/fake-xawt/build-libawt_xawt.sh`, redeploy, repeat.

## Rebuild/redeploy from scratch
```
cd ios/dev-tools/fake-xawt && ./build-libawt_xawt.sh    # stub -> jre/lib/
cd ../../app && ./build-probes.sh && xcodegen generate
xcodebuild -project HelloShell.xcodeproj -scheme HelloShell \
  -destination 'generic/platform=iOS' -configuration Debug \
  -allowProvisioningUpdates DEVELOPMENT_TEAM=5NZ6CPA994 build
xcrun devicectl device install app --device 00008150-001A18460C00401C \
  <DerivedData>/Build/Products/Debug-iphoneos/HelloShell.app
```
