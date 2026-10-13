# StikDebug JIT script — `runelite-jit.js`

The piece that makes the W1 shell actually boot HotSpot on the iPhone 17 Pro Max (iOS 26.5, TXM).

## Why it exists
On a TXM device the app cannot map executable (RX) memory itself; StikDebug (the attached debugger)
must do it on the JVM's behalf. Our jre21's libjvm requests code-cache memory via a single thunk:

```
char* BreakGetJITMapping(size_t bytes)   ==   brk #0x69 ; ret
```

HotSpot calls it with the requested **size in x0** and expects the address of a freshly-mapped **RX
region back in x0**. StikDebug's stock `universal.js` only returns a stub (`0xE0000069`) for `brk #0x69`,
which HotSpot then tries to execute → crash (this is the crash we hit on the first device run).
`runelite-jit.js` provides a real handler: allocate an RX region of `size` (`_M …,rx`), wire it up for
TXM execution (`prepare_memory_region`), and return its address in x0. It stays attached and services
every request (the code cache grows during the run).

It uses only StikDebug's documented builtins (`get_pid`, `send_command`, `prepare_memory_region`, `log`),
the GDB remote protocol, and the AArch64 BRK encoding — no app-side native glue required.

### Why it ALSO maps on fault (not just brk #0x69)
On-device disassembly showed libjvm's brk path lives in `os::pd_reserve_memory(size, executable)` →
`get_debug_jit_mapping`, gated behind `MirrorMappedCodeCache==1 && DeviceRequiresTXMWorkaround()`. That TXM
check reads `/private/preboot/.../Ap,TrustedExecutionMonitor.img4`, which the **app sandbox can't open**, so it
returns 0 → libjvm falls back to `mmap(PROT_READ|PROT_EXEC)`, which on TXM is **not actually executable** →
HotSpot jumps into it and takes an instruction-abort. The first device run showed exactly this (faults at
generated-code PCs, never a `brk #0x69`). So the script handles **both**: real `brk #0x69` requests *and*
instruction-abort faults — on a fault it calls `prepare_memory_region` for the faulting 16 KB page and retries
(continue without re-delivering the signal), turning the debugger into the on-demand RX mapper.

## Installing StikDebug without SideStore (the reliable path)

SideStore is a single point of failure: when its Apple-ID sign-in breaks, it cannot sign anything, and every
install — StikDebug included — sits on a spinner forever with no error. We do not need it. The paid team
already has a **wildcard** development profile (`<TEAM_ID>.*`, found under
`~/Library/Developer/Xcode/UserData/Provisioning Profiles/`), and a wildcard matches *any* bundle id, so the
stock StikDebug IPA can be signed under its own id and pushed straight over USB:

```sh
# 1. grab the release IPA (it ships UNSIGNED — that is normal; a sideload store signs it at install)
curl -sL -o StikDebug.ipa "$(gh api repos/StikDebug/StikDebug/releases/latest --jq '.assets[0].browser_download_url')"
unzip -q StikDebug.ipa -d stik && APP=stik/Payload/StikDebug.app

# 2. embed the WILDCARD profile (must list this device's hardware udid, not the CoreDevice uuid)
cp "~/Library/Developer/Xcode/UserData/Provisioning Profiles/<wildcard>.mobileprovision" "$APP/embedded.mobileprovision"

# 3. sign, keeping bundle id com.stik.stikdebug so it upgrades IN PLACE and keeps the pairing
#    file + per-app script assignments. ent.plist: application-identifier <TEAM_ID>.com.stik.stikdebug,
#    com.apple.developer.team-identifier, get-task-allow, keychain-access-groups.
codesign --force --sign "Apple Development: <you>" --entitlements ent.plist --generate-entitlement-der "$APP"

# 4. install
xcrun devicectl device install app --device <device-uuid> "$APP"
```

Why this is better than the store path: the signature is a 1-year paid-team one, so there is no weekly
refresh, and it works when SideStore is wedged. Keeping the original bundle id is the important bit — a
different id would install alongside as a second app and start from an empty container.

The bundle has no frameworks or extensions (one arm64 binary), so there is nothing to sign first.

## Install + assign (on the iPhone)
0. **Keep StikDebug current — this is load-bearing, not hygiene.** Add the official AltSource
   `https://stikdebug.xyz/index.json` to SideStore/AltStore and update from there, so later versions are
   one tap. StikDebug mounts the Developer Disk Image during "Starting JIT", and that code has changed a
   lot: 3.1.9 fixed pairing-file handling, 3.1.11 moved DDI mounting to cryptex (and stopped requiring a
   reboot to remount on iOS 26.4+), 3.1.12 fixed DDI for iOS < 26.4 *and* improved TXM scripts — which is
   the exact mechanism `runelite-jit.js` relies on. An old StikDebug on a new iOS is a known hang.
   That source also lists **StikPair** (on-device pairing file, no computer), which needs **iOS 27+** and so
   runs on the 27.2 dev device — it retires the iloader step for pairing. It is unsigned and framework-free
   exactly like StikDebug, so the devicectl recipe above installs it too, as `com.stik.StikPair`.
1. Get `runelite-jit.js` onto the phone and into StikDebug's scripts:
   - AirDrop the file to the iPhone, or
   - **Files → On My iPhone → StikDebug → scripts/** and drop it there, or use StikDebug's in-app
     "add/import script" button.
2. In **StikDebug**, open the app list, select **RuneMobile**, and **assign** `runelite-jit.js` as its
   script. (StikDebug stores the per-app mapping; on iOS-26 TXM it runs the assigned script on attach.)
3. Make sure **LocalDevVPN** is connected.

## Run loop
1. Launch **RuneMobile** → it waits at "JIT: waiting…".
2. **Attach StikDebug** to RuneMobile (with `runelite-jit.js` assigned). `CS_DEBUGGED` flips green; the
   script attaches and sits in its continue-loop waiting for `brk #0x69`.
3. Tap **Start JVM**. Watch RuneMobile's console and StikDebug's log:
   - StikDebug log should show `[rl-jit] #1 brk #0x69 size=… -> RX 0x…` lines (one per code-cache grow).
   - RuneMobile should reach `=== RuneMobile: HotSpot reached main() ===` and print
     `VERDICT = JIT ACTIVE`.

## If it still crashes / hangs
Capture both logs. Useful signals:
- **Every SideStore install sits on the spinner (any app, not just ours)** → this is the sideloader, not the
  app being installed. Apple changed something server-side that breaks Apple-ID sign-in on SideStore **0.6.4
  and earlier** (fixed in 0.7.0-alpha); with no sign-in there is no signing certificate, so installs never
  start. Check the SideStore version before blaming the app, and note the `nightly` release asset is rebuilt
  continuously — it is much fresher than the newest numbered tag.
- **"Connection Error … the device or VPN closed the tunnel connection before setup finished",
  `ConnectionReset` to `10.7.0.1:49152`** → read this as a PAIRING problem, not a VPN problem. The tunnel
  reaching 10.7.0.1 at all proves the loopback VPN is routing; it is the handshake after connect that is
  being rejected, and a pairing file minted for an older iOS is the usual reason. The pairing format has
  changed across majors (pymobiledevice3's classic lockdown record is rejected outright with "missing field
  public key"), so a file that worked on the previous major can fail here while LocalDevVPN looks perfectly
  healthy. On **iOS 27+** mint a fresh one on-device with **StikPair** instead of going back to iloader:
  open StikPair → Pair, then on the same device Settings → Privacy & Security → Developer Mode → **Pair with
  StikPair**, and import the result into StikDebug. (If that entry is missing, force-quit StikPair and
  reopen.) StikPair needs a working network — turn Airplane Mode off rather than relying on Wi-Fi alone.
- **Spinner stuck on "Starting JIT for RuneMobile", never reaching attach** → this is *before* our script
  runs, so it is not a `runelite-jit.js` problem. In order: (a) StikDebug out of date against the iOS
  version — see step 0, the DDI mount is what stalls here; (b) LocalDevVPN running but not *connected*
  (the app being open is not the same as the tunnel being up, and only one VPN runs at a time — SideStore's
  must be off); (c) a stale pairing file; (d) a Mac holding the device's debug tunnel (quit Xcode; check
  `pgrep -xl Xcode`). Confirm the app itself is alive with
  `xcrun devicectl device info processes --device <udid> | grep -i runemobile` — if RuneMobile is running
  and the spinner never resolves, the fault is on the StikDebug side, not ours.
- **No `[rl-jit] attached` line** → the script wasn't assigned/run; re-check the assignment.
- **`_M alloc FAILED`** → RX allocation rejected (try a smaller `-XX:ReservedCodeCacheSize` in the app's
  JLI args, e.g. 64m).
- **`[rl-jit] non-0x69 stop …`** → HotSpot hit a different fault; paste the pc/instr line.
- **Reaches main but `VERDICT = INTERPRETER ONLY`** → JIT mapping worked for the interpreter but C1/C2
  didn't engage; we look at compiler threads next.
