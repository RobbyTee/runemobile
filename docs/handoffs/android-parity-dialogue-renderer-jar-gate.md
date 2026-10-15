# Handoff — Android parity: dialogue actions, renderer truth, staged-jar gate

Pushed to `origin` (private) ONLY — **not** to `publish`. `publish/master` is still at `13442cf996`.

`release-hosted.yml` fires on any push to master in the PUBLIC repo (`publish` = audaro/runemobile,
id 1296635776) and publishes a rolling prerelease. `origin` (audaro/Rune-Mobile, id 1253608468) fails
that job guard, so master lives there without shipping. Hold the push to `publish` until the GPU work
is done.

## What shipped

| Change | Commit | Verified |
|---|---|---|
| Dialogue continue/options go through the engine's own handler | `a7134396e6` | compiled; JNI symbols in the APK's `.so`. **Not played** |
| Staged client jar refused when its generated `runelite/` resources were swept | `ddb3e96c5d` | **both directions**: passes on the real jar, fails on a hollowed one |
| Renderer rows report what is running, not what was picked | `b7da6d30bf` | compiled; the five status strings are in the APK's dex. **Not seen on screen** |

**No Android device or emulator is attached** (`adb` exists at `~/Library/Android/sdk/platform-tools/adb`
but lists no devices; AVDs `rl_arm64` / `rl_16k` exist and the app ships arm64, so an emulator run is
possible). Everything above is reasoned, compiled and symbol/string-verified — none of it is played.

## Why the dialogue taps were wrong

Android carried the same three bugs iOS did, because both routes it could take look right and neither
reports failure:

* **Precise-clicking the CONTINUE widget walks the player.** The component carries no op listener and
  reads hidden while drawn, so the click resolves against the scene behind the relocated chatbox.
  Walking away ends the conversation, so the tap reads as half-working.
* **A synthesized SPACE comes back consumed, unmoved.** It reaches the gamepack's listener and is
  reported handled without advancing the dialogue. Consumed is not acted on. Typing an option digit
  failed the same way.

Both now call `HudVeneerBridge.continueDialogue` / `selectDialogueOption`, already in the shared jar and
already what iOS uses. The method ids are resolved **OPTIONAL**, so a jar predating them loses the
dialogue taps rather than the whole HUD veneer.

### The seq had to be made trustworthy first

`g_dlgSnapSeq` advances **before** the dialogue json is copied under `g_dlgLock`, so reading the two
separately can hand the shell a seq **newer** than the text it holds — the unsafe direction, where a
stale row answers the dialogue that replaced it. The publish seq is stored with the string under the
lock (`g_dlgPubSeq`) and read back under it.

**"Read the seq before the payload and a skew fails safe" was wrong, and the fix is in `b5ebdc115a`.**
It accounts only for the pick made on that one frame. Both shells re-tagged their rows **only when the
json text changed**, so a single skewed read **latches**: the rows carry a seq that no longer matches,
and `selectDialogueOption`'s guard refuses *every* tap until the dialogue text happens to change —
which on an options dialogue is never, while the player sits there deciding. A dialogue that silently
stops answering, which is exactly what the comment at the bump site warned about.

What holds now:

* **Android** takes the seq and the json from **one** critical section (`nativeDialoguePublish`), and
  the seq-only accessor was **deleted** so nobody can reach for it again.
* **iOS** cannot do that without a new shared-contract method, so it re-reads the seq after the json
  and retries while it moved (`getDialogueSeq` bumps only on a changed snapshot, so a seq that is
  stable either side of the json read is the one that json belongs to).
* **Both** re-tag their rows on **every** pass, not only on a text change. This is what kills the latch
  as a class — the rows always belong to the json currently held, so a stale seq corrects next pass.

Neither is airtight and the code says so: `publishDialogueJson` writes the payload *then* bumps the
seq, so a read catching that window sees an older seq beside newer json, and the native cache fill
reads the seq before the json so it can sit one publish apart for a frame. Both are the **safe**
direction (a pick is refused, never misapplied) and both self-correct on the next pass. **The write
order is load-bearing**: bumping the seq first would hand out a seq *newer* than its json, and a stale
row would answer the dialogue that had just replaced it.

### Renderer rows may not promise a restart they do not control

`"● running — off after restart"` and `"● selected — restart to apply"` assume the saved pref decides
the next boot. A `gpu.mode` / `rlhd.mode` marker overrides it on **every** launch, and a TV/leanback
device boots 117HD by design — a shipping path, not a dev-only one.

**Asking "which renderer is pinned?" is not enough, and the first attempt at this got it backwards.**
The pref is not merely overridden by a marker: `prefRenderer == "117hd"` satisfies the *same* condition
`rlhd.mode` and the TV default do, so a 117HD pick **outranks** a `gpu.mode` override and does take
effect. A row keyed off the pin alone therefore told the user their pick was being ignored when it was
about to be honoured — reachable by simply tapping 117HD with `gpu.mode` present, since the panel's
`renderer` state is the **live** pick, not the boot-time one.

So `JvmLauncher` publishes the two *persistent* override flags (`rlhdForcedNextBoot` /
`gpuForcedNextBoot`; intent extras, a Game Loop launch and the one-shot `rlhd.once` are correctly
excluded) and `nextBootRenderer(pref)` applies them **with the live pref, in the boot path's own
precedence**. Every row that mentions restarting is keyed off that one value, which made the status
table smaller as well as correct. Keep the precedence in that one function — duplicating it is what
produced the backwards answer.

## What needed no port (don't re-tread)

* **Plugin hub pin 1.13.1** — reaches Android through the shared jar; there is no Android `-D` for it.
* **GPU crash cookie** — the iOS bug was backgrounding retiring the cookie for the whole session.
  Android's `auto-gpu-armed` cookie is cleared **only** by the watchdog on genuine success (3 presented
  frames) and backgrounding never touches it, so the bug does not exist there. (Grepping
  `bootCookie|hdBoot` finds nothing on Android — it is named `auto-gpu-armed`; do not conclude from
  that grep that there is no cookie.)
* **Camera hold** — Android pans by gesture (`nativeCamera(dx, dy)`); there are no arrow buttons to hold.
* **Pinned Space/digit buttons** — Android has none, so there is no key-routing surface to fix.

## Gate status

`:app:assembleDebug` green; `release-gate.sh --contract-only` PASS (shared contract **unchanged**, 567
entries; auth 18 pins; hub-pin 1.13.1 at project 1.13.2); clang-format and ktlint clean (both blocked a
commit and were fixed, not bypassed).

**The full gate (`--skip-build`) FAILS at detekt, and did so before this work.** Measured on a worktree
at `7820e7698c`: **26** weighted issues against a baseline last refreshed **10 Jul**, in files nobody
touched here (ConfigScreen, PanelReflowScreens, QuestWorldScreens, PanelView, KeepaliveService…). CI
only runs `--contract-only`, which is why a stale baseline can sit red unnoticed — the same way the
hub-pin tripwire did until 2026-10-15. This work now adds exactly **one** finding on top of that 26:
`PluginRowItem` takes 6 parameters against a threshold of 5. It is left visible rather than baselined.

Two baseline entries were **re-keyed, not added** (`DialogueView.update`'s complexity and return count):
detekt keys a finding by the function's whole signature, so renaming a parameter silently converts
baselined debt into a "new" finding. Regenerate such an ID with `--create-baseline` and swap it in; the
file still holds 171 entries. **Do not refresh the whole baseline to get green** — that would bless the
26 inherited findings in one commit.

## Exact next step

Field-test on a device, in this order:

1. **Dialogue.** Talk to any NPC: continue must advance the conversation and must **not** walk the
   player; option rows must pick the right line; hardware BACK must advance a continue-only box.
2. **Renderer rows.** Open Plugins → All plugins with a GPU/117HD boot: the running row should read
   `● running`, and after switching the pick the other row should read `● selected — restart to apply`.

## Open risks

* **A possible false demotion on Android, not fixed.** The auto-GPU watchdog gives the renderer 240s to
  present 3 frames and otherwise leaves the cookie armed, demoting the next launch to CPU. If the app is
  backgrounded during a slow first boot, frames may never present and the next launch silently drops to
  CPU with the "HD renderer didn't start" toast. Plausible from the code; no device evidence either way.
* **Older staged jar.** Because the two dialogue method ids are OPTIONAL, a jar predating them makes the
  dialogue taps no-ops with nothing in the log. The new `verifyStagedClientJar` does not check for them
  (it checks the generated `runelite/` resources).
* The jar gate checks only `client-*-gpu.jar`. `client-*-rlhd.jar` is the 117HD side jar (590 entries)
  and legitimately has neither `runelite/index` nor assembled scripts — gating it would fail every build.
