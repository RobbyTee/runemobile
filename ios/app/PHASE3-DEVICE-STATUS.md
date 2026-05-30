# W1 P3 §6.0 — ON DEVICE: CTC ACTIVE ✅ (2026-05-30)

**§6.0 (CACIO CTC activation) is GREEN on the iPhone 17 Pro Max (iOS 26.5, JIT).** Verified from
the captured on-device `jvm-stdio.log` (verdict line read + md5-gated, not scrollback):

```
[JIT26] Got JIT mapping … from debugger
set GraphicsEnvironment.headless/defaultHeadless = false OK
forced LocalGE.<clinit> (platform GE built) OK          ← the open device unknown: SURVIVED
set Toolkit.toolkit = CTCToolkit OK
built CTCGraphicsEnvironment.getInstance() OK           ← Font.initIDs resolved (headless-xawt)
Unsafe-overwrote LocalGE.INSTANCE = CTCGraphicsEnvironment OK
set SurfaceManagerFactory.instance = CTCSurfaceManagerFactory OK
GraphicsEnvironment.isHeadless() = false
Toolkit.getDefaultToolkit() = …cacio.ctc.CTCToolkit
GraphicsEnvironment.getLocalGraphicsEnvironment() = …cacio.ctc.CTCGraphicsEnvironment
screenDevices=1 defaultBounds=Rectangle[0,0,1024,768]   ← CTC virtual screen
AWT-ACTIVATION = CTC ACTIVE
```

So on device, with JIT and NO instrumentation: the CACIO CTC toolkit + graphicsenv are active,
non-headless, the GE singleton is CTC, and a virtual screen device is queryable. The forced
`LocalGE.<clinit>` (iOS `PlatformGraphicsInfo.createGE()`) ran fine — the one unknown is retired.

## Signing / install (verified, artifact-inspected)
- **Team `5NZ6CPA994`** (cert OU; NOT the CN parenthetical `393QD89H88`). `project.yml` pins it.
- Signed device build via `xcodebuild -allowProvisioningUpdates DEVELOPMENT_TEAM=5NZ6CPA994`
  (embedded profile + `codesign --verify` + `get-task-allow`); installed + confirmed via `devicectl`.

## What it took — three device rounds (the fake-xawt work, §6.1)
1. `UnsatisfiedLinkError: can't load libawt_xawt.dylib` at `Toolkit.<clinit>` (this jre21 is an
   xawt-style build; absent lib). → added a JNI-only stub.
2. Stub loaded, got past Toolkit+CTCToolkit, then `UnsatisfiedLinkError: java.awt.Font.initIDs()`
   in `CTCGraphicsEnvironment.<init>` (toolkit backend must supply core AWT natives).
   → **`libawt_xawt` = copy of `libawt_headless`** (complete native ABI; exports `Font.initIDs`).
3. **CTC ACTIVE.** See `dev-tools/fake-xawt/` (build-libawt_xawt.sh copies headless → xawt).

## NEXT — §6.1 AwtSmoke and onward
§6.0 is closed on device. Next: §6.1 `AwtSmoke` (realize `Frame`+`Canvas`, `getGraphics()`
non-null, draw + read CTC pixels back, fonts), then §6.2 real-RuneLite buffer smoke, §6.3 input,
§6.4 bench. A natural point for the Codex audit of the consolidated device plan now that the
activation foundation is proven on hardware.

## Rebuild/redeploy from scratch
```
cd ios/dev-tools/fake-xawt && ./build-libawt_xawt.sh    # libawt_headless -> libawt_xawt in jre/lib
cd ../../app && ./build-probes.sh && xcodegen generate
xcodebuild -project HelloShell.xcodeproj -scheme HelloShell \
  -destination 'generic/platform=iOS' -configuration Debug \
  -allowProvisioningUpdates DEVELOPMENT_TEAM=5NZ6CPA994 build
xcrun devicectl device install app --device 00008150-001A18460C00401C \
  <DerivedData>/Build/Products/Debug-iphoneos/HelloShell.app
```
