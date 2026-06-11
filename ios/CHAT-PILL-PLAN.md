# iOS chat-tab "channel pill" redesign — implementation plan

> Branch `chat-pill` (off `master`). Native-only restructure of the chat-tab section of
> `ios/app/HelloShell/ViewController.m`. All file:line refs are as of the branch point and will
> drift once edits land — re-grep before trusting them.

## Goal
Replace the always-on 7-tab chat row with ONE collapsed **channel pill** + a hold-to-fan-out
channel selector that's friendlier for touch. Same engine chat underneath — no engine change.

## Locked interaction model
- **Collapsed (default):** one pill at the chat origin `(chatX, chatY)` shows the channel you have
  open (existing two-line sprite/label render = "the button displayed on top"). A small `⋯`/chevron
  hints it's holdable.
- **Quick tap** → toggle the chat message area open/collapsed (`setEngineChatCollapsed`). Tap to
  peek, hold to switch.
- **Hold (~0.15 s)** → the 7 channel chips fan out horizontally, enlarged (popped up so they're
  fat-finger targets).
- **Drag sideways** → highlights the chip under the finger. **Lift** → switch the open chat to it
  (`switchEngineChatTab`, op-replay). **No timer anywhere.**
- **Pull down off a chip's bottom edge** → that chip's filter menu drops open at the finger (reuse
  `openEngineMenuAtGameX` right-click + the MenuPeek native big-row overlay). Drag into a row, lift
  to apply; drag back **up** onto the row before lifting → filter cancels, back to SELECT. A `▾`
  hint reveals under the hovered chip as the finger nears its bottom edge (discoverability +
  deadzone). Only armed for chips that actually have filter options (snapshot `filter` non-empty).

### Why pull-down, not a dwell timer
Filtering is a **direction**, not a **wait**. A timer can't tell "paused to decide" from "wants to
filter," so any dwell-based filter misfires on a hesitation — the user's explicit worry. A
perpendicular pull-down is a motion you never make during a horizontal select, so accidental
activation goes to ~zero while still filtering ANY channel under the finger (no pre-switch needed).
Rejected: plain dwell, movement-gated dwell (still misfires on a deliberate pause).

## Scope: native-only (no Java)
Every primitive already exists and is device-proven, so this is expected to be a
`ViewController.m`-only change → **`.m`-only build loop (skip the jar rebuild + staging), nothing on
mainline Java.** Reuse map:
- **Chips / state:** the 7 `chatTabButtons` (`buildChatTabs` ~`:1422`) laid out + painted each tick
  by `updateChatTabs` (~`:1475`) from `JVMLauncher.chatSnapshot` — per channel: `name`, `selected`,
  `new`, `filter`, `filterColor`, `labelColor`, and the live engine-button bounds `x/y/w/h`.
- **Channel switch:** `switchEngineChatTab` → `HudVeneerBridge.switchChatTabNow` (op-replay, op 1 +
  `BUILD_CHATBOX`; works on hidden widgets).
- **Filter menu:** `openEngineMenuAtGameX:gameY:anchor:` (right-click button 3 at the engine button
  centre from `engineChatButtonCenterForTag`) → `menuBuilding` poll builds the native rows from
  `MenuPeekBridge` → `updateMenuHighlightForPoint` (drag) → `commitMenuSelection` / `cancelMenu`.
- **Collapse:** `setEngineChatCollapsed` → `HudVeneerBridge.setChatCollapsed`.
- **Selected highlight hold:** `optimisticChatTab` (engine tab sprite lags a redraw).
- **Gesture exclusion:** `gestureRecognizer:shouldReceiveTouch:` rejects `UIControl`, so a gesture on
  the pill never leaks to camera/walk. Cross-screen drag tracking uses `locationInView:self.view` +
  frame hit-testing (same as the menu drag-select), so a gesture that begins on the pill tracks over
  the fanned chips fine.
- Sprites (`chatbtn`/`chatbtn-selected`/`chatbtn-new`) + RuneScape font already loaded.

## Implementation steps (ViewController.m)
1. **Pill view** anchored at `(chatX, chatY)`, ~96 pt wide, rendering the active channel from the
   snapshot's selected tab (reuse the two-line render). Chevron affordance. Built in
   `buildChatTabs`; shown/hidden + restated in `updateChatTabs`/`hideChatTabs`.
2. **Repurpose the 7 `chatTabButtons` as fan chips.** Add `fanOut()`/`fanIn()` (spring frame+alpha
   between "stacked at the pill" and "spread across `kChatW`, popped up ~14 px taller"). Keep
   `setEngineChatTabRowHeight=32` so the message area never reflows, and keep the engine tab row
   alive underneath so the filter right-click has valid button bounds.
3. **Driver gesture** — one `UILongPressGestureRecognizer` on the pill (min ~0.15 s) with a
   `SELECT`/`FILTER` mode:
   - *Began* → `fanOut()`, haptic, mode `SELECT`.
   - *Changed* → hit-test finger → chip `k` (frame contains `locationInView:self.view`); highlight
     `k`; reveal its `▾` as the finger nears its bottom; if the finger crosses below `k`'s bottom by
     the pull-down threshold AND `k` is filterable → mode `FILTER`:
     `openEngineMenuAtGameX(engineCenter(k))`, then `updateMenuHighlightForPoint` per move; if it
     returns above the row → `cancelMenu`, back to `SELECT`.
   - *Ended* → `SELECT`: `switchEngineChatTab(k)` + optimistic highlight (or just close if on the
     active chip / off-row); `FILTER`: `commitMenuSelection`. Then `fanIn()`.
   - *Cancelled/Failed* → `fanIn()` / `cancelMenu`.
   - **Quick tap** (gesture never begins; UIKit `cancelsTouchesInView` keeps them exclusive) →
     collapse toggle.
4. **`onDisplayTick`** drives only the fan animation + the `▾` reveal (no dwell timer). Keep the
   existing `updateChatTabs` call (~`:2155`) for per-tick state/layout.
5. **Guards** (reuse `updateChatTabs`'s): `topLevelId==164`, logged-in, chrome visible; suppress the
   fan while the keyboard / plugin panel / a world menu is open.
6. **Remove** the old per-tab `onChatTabButton` (TouchUpInside switch) and `onChatTabLongPress`
   (per-tab filter) wiring — superseded by the driver gesture + pull-down.

## Device-verify checklist
1. Quick tap toggles chat collapse; hold fans out (haptic); both feel distinct (no double-fire).
2. Drag-select switches to the chip under the finger on lift; release on the active chip / off-row =
   no change.
3. **Pull-down opens the filter menu under the finger** (the verify risk): confirm the native filter
   overlay lands at/below the pulled-down finger, not back up at the engine button. The engine builds
   the menu at the channel widget (top) and it opens downward, so the finger (chip-bottom + threshold)
   should land in the rows — verify. If it anchors wrong, re-anchor the native container at the
   finger (click-through is index-based, so the visual can move freely).
4. Pull down then back up onto the row before lifting cancels the filter (no stray apply).
5. `▾` reveal only on filterable chips; non-filterable pull-down is a no-op.
6. Tune (one-line constants): hold delay, pull-down distance, chip pop size, fan animation timing.

## Build & test loop (.m-only — jars unchanged)
```
xcodebuild -project ios/app/HelloShell.xcodeproj -scheme HelloShell \
  -destination "generic/platform=iOS" -derivedDataPath build/dd-gpu -allowProvisioningUpdates build
xcrun devicectl device install app --device AEA9E1BC-E149-57AB-A9DD-C4AEA668CD9B \
  build/dd-gpu/Build/Products/Debug-iphoneos/HelloShell.app
```
Device UDID `AEA9E1BC-E149-57AB-A9DD-C4AEA668CD9B` (iPhone 17 Pro Max). The user drives the StikDebug
launch + play; logs go to `Documents/jvm-stdio.log` (`[chat]` / `[shell]` lines; pull after a full
quit). No `gradlew`/`stage-ios-*.sh` needed unless a bridge method has to be added.
