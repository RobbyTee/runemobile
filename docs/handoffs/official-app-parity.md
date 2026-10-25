# Phone settings parity — screen awake, number pad, haptics, Game settings, drag hold

**Branch:** `parity/official-app` (not merged to master).
**Commits:** `643b92cbbd` (screen awake) · `46592c3a0a` (number pad + Paste) · `fcc5719b8f` (haptics) ·
`3b09ced2e5` (Game settings) · `b7b32f7657` (drag hold) · `2e95b1d5a8` (submenu haptic follow-up).
**Gates:** release gate, Android bridge census and iOS bridge census green before every commit; headless bridge
tests green for the two commits that touch the shared bridge.
**Device check:** see [Device check](#device-check) — pending on both phones at the time of writing.

## What shipped

| # | Change | Both ports |
|---|---|---|
| 1 | The screen stays awake while in game (Android `FLAG_KEEP_SCREEN_ON`, iOS `idleTimerDisabled`) and is released after leaving it. | yes |
| 2 | Amount prompts (quantity, GE price, X-amounts) open the number pad; the keyboard bar has a **Paste** button. `JavaBridge.getTextInputKind` tells the shells which keyboard to show. | yes |
| 3 | Haptics on four triggers, each with its own switch — Actions, Drag start, Menu open, Menu rows — plus a Light/Medium/Heavy strength. | yes |
| 4 | One native **Game settings** screen: long-press time, camera sensitivity, idle logout, frame-rate cap, and the haptics above. | yes |
| 5 | A drag from the inventory or a reorder pane moves the item only after a short hold; a quicker swipe scrolls the pane if it scrolls, and otherwise does nothing. | yes |

## Game settings — values and where they live

| Setting | Range | Default | Stored |
|---|---|---|---|
| Long-press time | 200–1000 ms, 50 ms steps | 400 ms | phone (SharedPreferences / NSUserDefaults) |
| Camera sensitivity | 0.1–4.0 | 2.0 (the drag speed the app always had) | phone |
| Idle logout | 5–30 min | 5 min | Logout Timer plugin (`logouttimer.idleTimeout`) |
| Frame-rate cap | Off, 20, 30, 40, 50, 60 | Off | FPS Control plugin (`fpscontrol.limitFps` / `maxFps`) |
| Haptics | four switches + strength | all on, Medium | phone |

- **Drag hold** = two thirds of the long-press time, at most 150 ms (150 ms at the default).
- The two game-side settings go through `MobileUiBridge.gameSettings` / `setFpsCap` / `setIdleLogoutMinutes`.
  Their policy (what each value writes, and what turning a plugin on implies) is in pure static helpers covered by
  `MobileUiBridgeTest`. Setting a cap or an idle time turns the plugin on if it is off; a cap that turns FPS
  Control on also hides its FPS counter. Both ports resolve the three calls as optional, so an older jar just
  shows "Available once the game is running."
- Android times the long-press itself now (`holdRunnable`, GestureDetector long-press off) because the system
  timeout can't be set per app. When the hold fires it sends the detector a cancel, so the lift can't also click
  — the guarantee the detector's own long-press used to give.
- iOS's longest tap follows the long-press time (plus 50 ms) instead of a fixed 0.45 s, so a long setting leaves no
  dead gap between tap and menu.

## Deliberate differences

- **Long-press default stays 400 ms.** Both shells have always used it, so the default doesn't change the feel
  for current players. Anyone who wants a longer hold can set it.
- **Idle logout floor is 5 minutes**, the Logout Timer plugin's own range.
- **Frame-rate caps above ~50 fps only bite with Unlock FPS on** in the GPU/117HD config: with it off the engine
  paces itself at about 50 fps whatever the cap says. The limiter runs on all three render paths (CPU, GPU,
  117HD).
- **The number pad has no k/m suffix keys.** Paste covers typing "10k"-style amounts.

## Device check

Walk this on the iPhone 17 Pro Max and the Pixel 7a:

1. In game, leave the phone untouched past its auto-lock: the screen stays on. Back on the login screen it locks
   as normal.
2. Bank → Withdraw-X, GE price, any "Enter amount": the number pad opens. Paste fills a copied number.
3. Long-press the scene: one Menu open buzz. Slide over rows: Menu rows ticks. Pick an option: Actions buzz.
   Drag an item: one Drag start buzz. Toggle each switch off and confirm only that trigger goes quiet. Strength
   changes the feel (and plays a sample).
4. Game settings: set long-press to 1000 ms — the menu takes a full second, a 0.6 s press still taps. Set camera
   sensitivity to 0.5 and 4.0 — the camera turns slower/faster, and slow drags at 0.5 still move it. Set idle
   logout and frame-rate cap, leave the screen and return: the values stick, and the FPS Control / Logout Timer
   plugin pages show the same values.
5. Inventory: a quick flick across an item does nothing (no move, no camera); rest a beat then drag and the item
   moves. In the bank, a quick swipe scrolls it.

## Open risks

- The self-timed Android hold is new code on the most-used gesture. Watch for taps that don't click or holds
  that don't open the menu, especially around the map (the hold is swallowed there, as before).
- A quick drag in the inventory used to move the item at once. It now needs the short hold, which is the point,
  but players used to flicking items will notice.
- Picking a cap or idle time turns a stock plugin on. That's visible in the plugin list and is intended.

## Next step

Finish the device check above on both phones, then merge `parity/official-app` to master.
