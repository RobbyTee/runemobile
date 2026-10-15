# Handoff — engine input, dialogue actions, camera hold

Merged to `master` (merge commit below). Pushed to `origin` (private) ONLY — **not** to `publish`.

`release-hosted.yml` fires on any push to master in the PUBLIC repo (`publish` = audaro/runemobile,
id 1296635776) and publishes a rolling prerelease of both apps. `origin` (audaro/Rune-Mobile, id
1253608468) fails that guard, so master lives there without shipping anything. The next push to
`publish` is what releases — hold it until the GPU bug is fixed.

## What shipped

| Fix | Commit | Verified |
|---|---|---|
| A printable key carries its character | `544942b748` | in shipped bytecode |
| Keys reach the canvas, not a focus owner that cannot exist | `4cec510634` | device: `consumed=true` |
| Dialogue tap no longer clicks through to the scene | `cf3a0ec611` | device, by the user |
| Continue advances the dialogue | `97348f4a14`, `a0cb76dc85` | device, by the user |
| Options can be picked | `5c74e731a7` | device, by the user |
| Arrow buttons turn the camera while held | `42f4cb8404` | **built, NOT yet verified** |

## The three root causes

1. **No synthesized key had ever worked.** `postEvent(KeyEvent)` is routed by the
   `KeyboardFocusManager` to the *focus owner*; headless there is no focused Window, so every posted
   KeyEvent was discarded. A posted `FOCUS_GAINED` dies the same way, so `ensureFocus` was a no-op
   since it was written. MouseEvents dispatch to the source, which is why taps always worked. Fixed by
   handing the event to the canvas's own `KeyListener`s on the EDT.
2. **The engine does not act on a synthesized key.** Even delivered and `consumed=true`, space and the
   option digits move no dialogue. Delivery is necessary, not sufficient — don't read `consumed` as
   success. Plugin hotkeys DO flow, via `Hooks.keyPressed` → `KeyManager` inside the gamepack listener.
3. **The CONTINUE component is not clickable.** `onOp=null` (so op-replay and `CC_OP` have no target)
   and `hidden=true` while drawn, so clicking its published bounds resolved against the SCENE behind
   the chatbox — that is what walked the player and ended the conversation.

## Rules established (device-confirmed)

- Dialogue is driven by `MenuAction.WIDGET_CONTINUE`, `identifier = 0`. It is a dedicated opcode, so
  that argument is NOT an op index — do not derive it from `clickMask`.
- `param0 = -1` for the **static** continue component; `param0 = child index`, `param1 = parent` for a
  **dynamic** option row. Both halves of the same dispatch rule.
- Resolve a widget target when the action fires, on the client thread. Never store an id: dialogue
  kinds publish continues through different branches, so a remembered id goes stale silently.
- Guard a pick with `getDialogueSeq()` (bumped only on change), read *before* the payload so a publish
  in between fails safe. A row count is not a guard — the next dialogue usually offers the same number
  of options, and in a quest those are different answers.
- Counting option rows: the publisher skips blank rows and the engine pads the array with them, so
  `children.length - 1` overcounts.

## Exact next step

Hold an arrow button on device. The camera should turn at a steady rate and stop on release. If a
direction feels inverted it is a sign flip in `onCameraHoldBegin` (`ViewController.m`); the signs follow
the drag convention and were not verified against the buttons in play. Then merge to master.

## Open risks, untouched

- **Classic layout (161) soft-locks the HUD:** `updateHudLayout` hides engine stones with no layout
  guard; the only escape is Settings → "Show tab rails" OFF. The code also mislabels 164 as "Classic"
  (164 is `RESIZABLE_VIEWPORT_BOTTOM_LINE`/Modern, 161 is `RESIZABLE_VIEWPORT_OLD_SCHOOL_BOX`/Classic).
- **`chatDialogueActive` is sticky:** `updateChatTabs` returns early in an unsupported layout without
  clearing it.
- **Release builds emit no diagnostics:** `appendLine` has no log file in Release and the console is
  `#if DEBUG`, so a field report (0.2.0) cannot produce a log. This blocked the original report.
- ~~Two fixes stranded on `jit/no-localdevvpn-no-stikdebug`~~ — **not stranded, both are on master**:
  `eac7d4f603` counts reaching the background as surviving the GPU boot, and `fb3d17ff13` keeps the
  previous launch's logs. Checked at merge time; the handoff said otherwise and was wrong.
- `remote-build.sh` assumes an unlocked keychain.

## Diagnostics worth keeping

Java `System.out` lands in `Documents/jvm-stdio.log`; the shell log in `Documents/hello-shell.log`.
Both pull over the network with
`xcrun devicectl device copy from --device <id> --domain-type appDataContainer --domain-identifier dev.audaro.runemobile --source <path> --destination <local>`,
so a device log needs nothing from the user. `[keydiag]` reports key delivery once per run; `[cont]`
and `[opt]` report each dialogue action and why a pick was dropped.
