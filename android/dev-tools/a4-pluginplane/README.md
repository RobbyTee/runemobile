# A4 — plugin control plane (seam first)

Wires RuneLite's plugin control plane (`MobileUiBridge`) into the Android host — the phase that delivers
"**all plugins** toggle/config natively" (see `../../PORT-PHASING.md` Phase A4, `../../BLUEPRINT.md` §C).
Built incrementally; this directory tracks the **seam** (the host↔engine path), with the UI on top next.

## A3.5 — bridge request/response thread (done)
ART threads are NOT attached to the embedded HotSpot VM, and the ART UI thread must never block on a HotSpot
call (ANR). So plugin-plane calls run on a dedicated `rl-bridge` background thread (`JvmLauncher.onBridge {}`)
that daemon-attaches to HotSpot in the native layer; strings cross the VM boundary as UTF-8
(`jvm_bootstrap.cpp` `nativeListPlugins/nativeMobileUiSeq/nativePollMobileUiEvents/nativeSetPluginEnabled`).

## A4 seam — host reaches MobileUiBridge (GREEN)
A-1b proved `listPlugins()` returns a live ~32 KB list **in-VM**. The seam proves the **host** can pull that
same list **across** the boundary on the rl-bridge thread — the path A4's UI uses. Census mode runs a
read-only host self-test (`MainActivity.runA4SeamSelfTest`): poll `listPlugins()` until the engine has booted
and `-Drunelite.mobileui` has wired it, then log the received length + the event seq.

```
( cd ../../.. && cd android && JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew :app:assembleDebug )
adb install -r ../../app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop dev.audaro.helloshell ; adb logcat -c
adb shell am start -n dev.audaro.helloshell/.MainActivity --ez census true
# after LOGGED_IN:
./check-a4seam.sh
```

### GREEN baseline (emulator `rl_arm64`, 2026-06-07)
```
ANDROID_A4_SEAM_GREEN listPluginsLen=<~32230> mobileUiSeq=<n>
```
A list length matching the in-VM A-1b figure (~32230) confirms the full plugin list crossed ART↔HotSpot intact.

## Next (this phase, not yet built)
- **Write path:** `setPluginEnabled` (toggle) + `setConfig`/`resetConfig`, with `getProcessedWriteId`/
  `pollMobileUiEvents` acks — the native externs are wired; the host self-test for the toggle round-trip is next.
- **UI:** a Jetpack Compose plugin list (switches) + a typed config screen (bool→Switch, int/double→field,
  enum→picker, color→picker, string→text), parsing the bridge JSON. Plugin Hub (`-Drunelite.hub`, pin
  `runelite.pluginhub.version` to a published rev — the `1.12.29-SNAPSHOT` has none → 404).
- `flushConfig()` on `onPause`/screen-close (config doesn't persist otherwise).
