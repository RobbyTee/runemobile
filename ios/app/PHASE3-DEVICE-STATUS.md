# W1 P3 §6.0 — on-device confirm status (2026-05-30)

Records the verified state of the §6.0 activation confirm on the device, and corrects an
earlier commit message that wrongly said the signed build needed an Xcode account sign-in.

## Verified (artifact-inspected, not just build logs)
- **Signing team = `5NZ6CPA994`** (the OU of the `Apple Development: jordansimps@me.com`
  cert). The CN parenthetical `393QD89H88` is a personal id, NOT the team — setting it as the
  team is what produced the bogus `No Account for Team "393QD89H88"` failure. `project.yml`
  now pins `DEVELOPMENT_TEAM: 5NZ6CPA994`.
- **Signed device build SUCCEEDS** with the correct team via
  `xcodebuild -allowProvisioningUpdates DEVELOPMENT_TEAM=5NZ6CPA994` (an Xcode account exists
  for the real team). Verified on the produced `.app`: `embedded.mobileprovision` present,
  `codesign --verify --deep --strict` passes, `get-task-allow` entitlement present.
- **Installed on device**: `HelloShell` (`dev.audaro.helloshell`) installed to the iPhone 17
  Pro Max (paired) via `xcrun devicectl device install app`; confirmed present with
  `devicectl device info apps`.
- The app runs `UnsafePokeActivation` alone (headless=false + the CACIO JPMS set), greppable
  verdict in `Documents/jvm-stdio.log`.

## Remaining — the irreducible JIT ritual (needs physical interaction)
1. In **StikDebug**: assign `ios/app/stikdebug/runelite-jit.js` to HelloShell, attach (JIT is
   per-launch).
2. Launch HelloShell; wait for `JIT: CS_DEBUGGED ✓`; tap **Run AWT activation (§6.0)**.
3. Pull the verdict:
   `xcrun devicectl device copy from --device 00008150-001A18460C00401C \
      --domain-type appDataContainer --domain-identifier dev.audaro.helloshell \
      --source Documents/jvm-stdio.log --destination ./jvm-stdio.log`
   then grep `AWT-ACTIVATION`:
   - `= CTC ACTIVE` → §6.0 de-risked on device; proceed to §6.1 `AwtSmoke`.
   - `= FAILED` → read the trace (likely `LocalGE.<clinit>` / iOS `PlatformGraphicsInfo.createGE()`);
     pursue `CTCPreloadClassLoader` or a fake `libawt_xawt`, fold into the brief before the audit.

## Rebuild/redeploy from scratch
```
cd ios/app
./build-probes.sh                     # regenerate jvm-resources/probes.jar
xcodegen generate
xcodebuild -project HelloShell.xcodeproj -scheme HelloShell \
  -destination 'generic/platform=iOS' -configuration Debug \
  -allowProvisioningUpdates DEVELOPMENT_TEAM=5NZ6CPA994 build
xcrun devicectl device install app --device 00008150-001A18460C00401C \
  /tmp/dd/Build/Products/Debug-iphoneos/HelloShell.app   # or the DerivedData path
```
